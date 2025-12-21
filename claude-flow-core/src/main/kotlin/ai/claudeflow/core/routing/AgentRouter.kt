package ai.claudeflow.core.routing

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.model.AgentMatch
import ai.claudeflow.core.model.RoutingMethod
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 에이전트 라우터
 *
 * 다단계 분류 파이프라인:
 * 1. 키워드 매칭 (0.95 confidence) - 가장 빠름
 * 2. 정규식 패턴 매칭 (0.85 confidence)
 * 3. 시맨틱 검색 (벡터 유사도) - 선택적
 * 4. 기본 에이전트 폴백 (0.5 confidence)
 */
class AgentRouter(
    initialAgents: List<Agent> = defaultAgents(),
    private val semanticRouter: SemanticRouter? = null
) {
    private val agents = initialAgents.toMutableList()

    companion object {
        fun defaultAgents() = listOf(
            Agent.REFACTOR,
            Agent.CODE_REVIEWER,
            Agent.BUG_FIXER,
            Agent.GENERAL
        )
    }

    /**
     * 메시지를 분석하여 가장 적합한 에이전트 선택
     */
    fun route(message: String): AgentMatch {
        val normalizedMessage = message.lowercase()
        val enabledAgents = agents.filter { it.enabled }

        // 1. 키워드 매칭 (가장 빠름, 0.95 confidence)
        keywordMatch(normalizedMessage, enabledAgents)?.let {
            logger.debug { "Keyword match: ${it.agent.id}" }
            return it
        }

        // 2. 정규식 패턴 매칭 (0.85 confidence)
        patternMatch(normalizedMessage, enabledAgents)?.let {
            logger.debug { "Pattern match: ${it.agent.id}" }
            return it
        }

        // 3. 시맨틱 검색 (벡터 유사도, 선택적)
        semanticRouter?.classify(message, enabledAgents)?.let {
            logger.debug { "Semantic match: ${it.agent.id}" }
            return it
        }

        // 4. 기본 에이전트로 폴백
        val defaultAgent = enabledAgents.find { it.id == "general" }
            ?: enabledAgents.firstOrNull()
            ?: Agent.GENERAL

        logger.debug { "Fallback to default: ${defaultAgent.id}" }
        return AgentMatch(
            agent = defaultAgent,
            confidence = 0.5,
            matchedKeyword = null
        )
    }

    /**
     * 키워드 매칭
     */
    private fun keywordMatch(message: String, agents: List<Agent>): AgentMatch? {
        for (agent in agents) {
            for (keyword in agent.keywords) {
                if (message.contains(keyword.lowercase())) {
                    return AgentMatch(
                        agent = agent,
                        confidence = 0.95,
                        matchedKeyword = keyword,
                        method = RoutingMethod.KEYWORD
                    )
                }
            }
        }
        return null
    }

    /**
     * 정규식 패턴 매칭
     */
    private fun patternMatch(message: String, agents: List<Agent>): AgentMatch? {
        val patterns = mapOf(
            Regex("(mr|merge request|pull request|pr)\\s*(#?\\d+)?", RegexOption.IGNORE_CASE) to "code-reviewer",
            Regex("(버그|bug|에러|error|오류|exception|crash)", RegexOption.IGNORE_CASE) to "bug-fixer",
            Regex("(리뷰|review|검토|코드\\s*리뷰)", RegexOption.IGNORE_CASE) to "code-reviewer",
            Regex("(수정|fix|고쳐|patch|debug)", RegexOption.IGNORE_CASE) to "bug-fixer",
            Regex("(설명|explain|뭐야|무엇|어떻게|how|what|why)", RegexOption.IGNORE_CASE) to "general"
        )

        for ((pattern, agentId) in patterns) {
            if (pattern.containsMatchIn(message)) {
                val agent = agents.find { it.id == agentId }
                if (agent != null) {
                    return AgentMatch(
                        agent = agent,
                        confidence = 0.85,
                        matchedKeyword = pattern.pattern,
                        method = RoutingMethod.PATTERN
                    )
                }
            }
        }
        return null
    }

    /**
     * 모든 활성화된 에이전트 목록 반환
     */
    fun listAgents(): List<Agent> = agents.filter { it.enabled }

    /**
     * 특정 에이전트 조회
     */
    fun getAgent(agentId: String): Agent? = agents.find { it.id == agentId && it.enabled }

    /**
     * 시맨틱 라우터 설정 여부
     */
    fun hasSemanticRouter(): Boolean = semanticRouter != null

    // ==================== 에이전트 CRUD ====================

    /**
     * 에이전트 추가
     */
    fun addAgent(agent: Agent): Boolean {
        if (agents.any { it.id == agent.id }) {
            logger.warn { "Agent already exists: ${agent.id}" }
            return false
        }
        agents.add(agent)
        logger.info { "Added agent: ${agent.id}" }
        return true
    }

    /**
     * 에이전트 업데이트
     */
    fun updateAgent(agentId: String, update: AgentUpdate): Boolean {
        val index = agents.indexOfFirst { it.id == agentId }
        if (index == -1) {
            logger.warn { "Agent not found: $agentId" }
            return false
        }

        val existing = agents[index]
        val updated = existing.copy(
            name = update.name ?: existing.name,
            description = update.description ?: existing.description,
            keywords = update.keywords ?: existing.keywords,
            systemPrompt = update.systemPrompt ?: existing.systemPrompt,
            model = update.model ?: existing.model,
            allowedTools = update.allowedTools ?: existing.allowedTools,
            workingDirectory = update.workingDirectory ?: existing.workingDirectory,
            enabled = update.enabled ?: existing.enabled,
            priority = update.priority ?: existing.priority,
            examples = update.examples ?: existing.examples,
            projectId = update.projectId ?: existing.projectId
        )
        agents[index] = updated
        logger.info { "Updated agent: $agentId" }
        return true
    }

    /**
     * 에이전트 삭제
     */
    fun removeAgent(agentId: String): Boolean {
        // 기본 에이전트는 삭제 불가
        if (agentId in listOf("general", "code-reviewer", "bug-fixer")) {
            logger.warn { "Cannot remove built-in agent: $agentId" }
            return false
        }

        val removed = agents.removeIf { it.id == agentId }
        if (removed) {
            logger.info { "Removed agent: $agentId" }
        }
        return removed
    }

    /**
     * 에이전트 활성화/비활성화
     */
    fun setAgentEnabled(agentId: String, enabled: Boolean): Boolean {
        val agent = agents.find { it.id == agentId }
        if (agent == null) {
            logger.warn { "Agent not found: $agentId" }
            return false
        }

        val index = agents.indexOf(agent)
        agents[index] = agent.copy(enabled = enabled)
        logger.info { "Agent $agentId enabled: $enabled" }
        return true
    }

    /**
     * 모든 에이전트 목록 (비활성화 포함)
     */
    fun listAllAgents(): List<Agent> = agents.toList()
}

/**
 * 에이전트 업데이트 요청
 */
data class AgentUpdate(
    val name: String? = null,
    val description: String? = null,
    val keywords: List<String>? = null,
    val systemPrompt: String? = null,
    val model: String? = null,
    val allowedTools: List<String>? = null,
    val workingDirectory: String? = null,
    val enabled: Boolean? = null,
    val priority: Int? = null,           // Claude Flow 스타일 우선순위
    val examples: List<String>? = null,  // 시맨틱 라우팅 예제
    val projectId: String? = null        // 프로젝트별 에이전트
)
