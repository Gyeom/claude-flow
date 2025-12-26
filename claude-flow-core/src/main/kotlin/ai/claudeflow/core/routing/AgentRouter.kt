package ai.claudeflow.core.routing

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.model.AgentMatch
import ai.claudeflow.core.model.RoutingMethod
import ai.claudeflow.core.rag.FeedbackLearningService
import ai.claudeflow.core.rag.AgentRecommendation
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * 에이전트 라우터
 *
 * 다단계 분류 파이프라인:
 * 1. 피드백 학습 기반 추천 (0.9 confidence) - 유사 쿼리 분석
 * 2. 키워드 매칭 (0.95 confidence) - 가장 빠름
 * 3. 정규식 패턴 매칭 (0.85 confidence)
 * 4. 시맨틱 검색 (벡터 유사도) - 선택적
 * 5. 기본 에이전트 폴백 (0.5 confidence)
 */
class AgentRouter(
    initialAgents: List<Agent> = defaultAgents(),
    private val semanticRouter: SemanticRouter? = null,
    private val feedbackLearningService: FeedbackLearningService? = null
) {
    private val agents = initialAgents.toMutableList()

    companion object {
        fun defaultAgents() = listOf(
            Agent.REFACTOR,
            Agent.CODE_REVIEWER,
            Agent.BUG_FIXER,
            Agent.GENERAL
        )

        // Regex 패턴 캐싱 (매번 생성 방지로 성능 최적화)
        private val PATTERNS: List<Pair<Regex, String>> = listOf(
            Regex("(mr|merge request|pull request|pr)\\s*(#?\\d+)?", RegexOption.IGNORE_CASE) to "code-reviewer",
            Regex("(버그|bug|에러|error|오류|exception|crash)", RegexOption.IGNORE_CASE) to "bug-fixer",
            Regex("(리뷰|review|검토|코드\\s*리뷰)", RegexOption.IGNORE_CASE) to "code-reviewer",
            Regex("(수정|fix|고쳐|patch|debug)", RegexOption.IGNORE_CASE) to "bug-fixer",
            Regex("(설명|explain|뭐야|무엇|어떻게|how|what|why)", RegexOption.IGNORE_CASE) to "general"
        )

        /**
         * 라우팅 결과 캐시 - 동일한 메시지는 5분간 캐싱
         * 성능 최적화: 반복되는 라우팅 연산 회피
         */
        private val routingCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build<String, AgentMatch>()

        // 캐시 통계
        private val cacheHits = AtomicLong(0)
        private val cacheMisses = AtomicLong(0)

        /**
         * 라우팅 캐시 통계 반환
         */
        fun getCacheStats(): Map<String, Any> {
            val hits = cacheHits.get()
            val misses = cacheMisses.get()
            val total = hits + misses
            val hitRate = if (total > 0) (hits.toDouble() / total * 100).let { "%.2f%%".format(it) } else "N/A"
            return mapOf(
                "cacheHits" to hits,
                "cacheMisses" to misses,
                "cacheSize" to routingCache.estimatedSize(),
                "hitRate" to hitRate
            )
        }

        /**
         * 라우팅 캐시 초기화
         */
        fun clearCache() {
            routingCache.invalidateAll()
            cacheHits.set(0)
            cacheMisses.set(0)
            logger.info { "Routing cache cleared" }
        }
    }

    /**
     * 메시지를 분석하여 가장 적합한 에이전트 선택
     *
     * 성능 최적화:
     * - 라우팅 결과 캐싱: 동일한 메시지는 5분간 캐싱
     * - 캐시 키: 정규화된 메시지
     *
     * @param message 사용자 메시지
     * @param userId 사용자 ID (피드백 학습용, 선택적)
     */
    fun route(message: String, userId: String? = null): AgentMatch {
        val normalizedMessage = message.lowercase().trim()
        val enabledAgents = agents.filter { it.enabled }

        // 0. 캐시 확인 - userId가 없는 일반 라우팅만 캐싱 (피드백 학습은 사용자별로 다를 수 있음)
        if (userId == null) {
            val cached = routingCache.getIfPresent(normalizedMessage)
            if (cached != null) {
                cacheHits.incrementAndGet()
                logger.debug { "Routing cache hit: ${cached.agent.id}" }
                return cached
            }
            cacheMisses.incrementAndGet()
        }

        // 1. 피드백 학습 기반 추천 (유사 쿼리 분석)
        if (userId != null && feedbackLearningService != null) {
            feedbackLearningMatch(message, userId, enabledAgents)?.let {
                logger.debug { "Feedback learning match: ${it.agent.id} (confidence: ${it.confidence})" }
                return it
            }
        }

        // 2. 키워드 매칭 (가장 빠름, 0.95 confidence)
        keywordMatch(normalizedMessage, enabledAgents)?.let { match ->
            // 피드백으로 점수 조정
            val adjustedMatch = adjustMatchWithFeedback(match, userId)
            logger.debug { "Keyword match: ${adjustedMatch.agent.id}" }
            // 캐시 저장 (userId가 없는 경우만)
            if (userId == null) routingCache.put(normalizedMessage, adjustedMatch)
            return adjustedMatch
        }

        // 3. 정규식 패턴 매칭 (0.85 confidence)
        patternMatch(normalizedMessage, enabledAgents)?.let { match ->
            val adjustedMatch = adjustMatchWithFeedback(match, userId)
            logger.debug { "Pattern match: ${adjustedMatch.agent.id}" }
            if (userId == null) routingCache.put(normalizedMessage, adjustedMatch)
            return adjustedMatch
        }

        // 4. 시맨틱 검색 (벡터 유사도, 선택적)
        semanticRouter?.classify(message, enabledAgents)?.let { match ->
            val adjustedMatch = adjustMatchWithFeedback(match, userId)
            logger.debug { "Semantic match: ${adjustedMatch.agent.id}" }
            if (userId == null) routingCache.put(normalizedMessage, adjustedMatch)
            return adjustedMatch
        }

        // 5. 기본 에이전트로 폴백
        val defaultAgent = enabledAgents.find { it.id == "general" }
            ?: enabledAgents.firstOrNull()
            ?: Agent.GENERAL

        logger.debug { "Fallback to default: ${defaultAgent.id}" }
        val fallbackMatch = AgentMatch(
            agent = defaultAgent,
            confidence = 0.5,
            matchedKeyword = null
        )
        if (userId == null) routingCache.put(normalizedMessage, fallbackMatch)
        return fallbackMatch
    }

    /**
     * 피드백 학습 기반 에이전트 매칭
     */
    private fun feedbackLearningMatch(
        message: String,
        userId: String,
        agents: List<Agent>
    ): AgentMatch? {
        val recommendation = feedbackLearningService?.recommendAgentFromSimilar(
            query = message,
            userId = userId,
            topK = 5
        ) ?: return null

        // 높은 신뢰도 (0.8 이상)만 사용
        if (recommendation.confidence < 0.8f) return null

        val agent = agents.find { it.id == recommendation.agentId } ?: return null

        return AgentMatch(
            agent = agent,
            confidence = recommendation.confidence.toDouble().coerceAtMost(0.9),
            matchedKeyword = recommendation.reason,
            method = RoutingMethod.FEEDBACK_LEARNING
        )
    }

    /**
     * 피드백 기반 점수 조정
     */
    private fun adjustMatchWithFeedback(match: AgentMatch, userId: String?): AgentMatch {
        if (userId == null || feedbackLearningService == null) return match

        val adjustedScore = feedbackLearningService.adjustRoutingScore(
            userId = userId,
            agentId = match.agent.id,
            baseScore = match.confidence.toFloat()
        )

        return match.copy(confidence = adjustedScore.toDouble())
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
     * 정규식 패턴 매칭 (캐싱된 Regex 사용으로 성능 최적화)
     */
    private fun patternMatch(message: String, agents: List<Agent>): AgentMatch? {
        for ((pattern, agentId) in PATTERNS) {
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

    /**
     * 피드백 학습 서비스 설정 여부
     */
    fun hasFeedbackLearning(): Boolean = feedbackLearningService != null

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
