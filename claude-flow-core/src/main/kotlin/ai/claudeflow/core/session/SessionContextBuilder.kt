package ai.claudeflow.core.session

import ai.claudeflow.core.model.Agent
import mu.KotlinLogging
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * 세션 컨텍스트 빌더
 *
 * Claudio 대비 차별화 기능:
 * 1. 지능형 대화 요약
 * 2. 세션 복원 (컨텍스트 윈도우 관리)
 * 3. 사용자 컨텍스트 통합
 * 4. 동적 시스템 프롬프트 생성
 * 5. 토큰 최적화
 */
class SessionContextBuilder(
    private val maxContextTokens: Int = 100_000,
    private val maxHistoryMessages: Int = 50,
    private val summaryThreshold: Int = 20,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) {
    /**
     * 세션 컨텍스트 빌드
     */
    fun build(request: SessionContextRequest): SessionContext {
        val startTime = System.currentTimeMillis()

        // 1. 시스템 프롬프트 구성
        val systemPrompt = buildSystemPrompt(request)
        val systemTokens = tokenEstimator.estimate(systemPrompt)

        // 2. 사용자 컨텍스트 구성
        val userContextBlock = buildUserContextBlock(request.userContext)
        val userContextTokens = tokenEstimator.estimate(userContextBlock)

        // 3. 대화 히스토리 구성 (토큰 제한 고려)
        val remainingTokens = maxContextTokens - systemTokens - userContextTokens - 4000 // 응답용 여유
        val historyResult = buildHistory(request.conversationHistory, remainingTokens)

        // 4. 최종 메시지 배열 구성
        val messages = mutableListOf<Message>()

        // 시스템 메시지
        messages.add(Message(Role.SYSTEM, systemPrompt))

        // 사용자 컨텍스트 (있는 경우)
        if (userContextBlock.isNotEmpty()) {
            messages.add(Message(Role.SYSTEM, userContextBlock))
        }

        // 요약된 히스토리 (있는 경우)
        if (historyResult.summary != null) {
            messages.add(Message(Role.SYSTEM, historyResult.summary))
        }

        // 대화 히스토리
        messages.addAll(historyResult.messages)

        // 현재 메시지
        messages.add(Message(Role.USER, request.currentMessage))

        val buildTimeMs = System.currentTimeMillis() - startTime
        val totalTokens = systemTokens + userContextTokens + historyResult.totalTokens +
            tokenEstimator.estimate(request.currentMessage)

        logger.debug {
            "Session context built: ${messages.size} messages, ~$totalTokens tokens, ${buildTimeMs}ms"
        }

        return SessionContext(
            messages = messages,
            totalTokens = totalTokens,
            includedHistoryCount = historyResult.includedCount,
            summarizedHistoryCount = historyResult.summarizedCount,
            hasUserContext = userContextBlock.isNotEmpty(),
            buildTimeMs = buildTimeMs
        )
    }

    /**
     * 시스템 프롬프트 빌드
     */
    private fun buildSystemPrompt(request: SessionContextRequest): String {
        val parts = mutableListOf<String>()

        // 에이전트 기본 프롬프트
        parts.add(request.agent.systemPrompt)

        // 날짜/시간 컨텍스트
        parts.add("\n\n현재 시각: ${Instant.now()}")

        // 프로젝트 컨텍스트
        request.projectContext?.let { project ->
            parts.add("""

## 프로젝트 컨텍스트
- 프로젝트: ${project.name}
- 설명: ${project.description}
- 주요 기술: ${project.technologies.joinToString(", ")}
            """.trimIndent())
        }

        // 사용자 규칙
        if (request.userRules.isNotEmpty()) {
            parts.add("""

## 사용자 커스텀 규칙
${request.userRules.mapIndexed { i, rule -> "${i + 1}. $rule" }.joinToString("\n")}
            """.trimIndent())
        }

        // 추가 지시사항
        request.additionalInstructions?.let { instructions ->
            parts.add("\n## 추가 지시사항\n$instructions")
        }

        return parts.joinToString("")
    }

    /**
     * 사용자 컨텍스트 블록 빌드
     */
    private fun buildUserContextBlock(userContext: SessionUserContext?): String {
        if (userContext == null) return ""

        val parts = mutableListOf<String>()
        parts.add("## 사용자 컨텍스트")

        // 사용자 요약
        userContext.summary?.let { summary ->
            parts.add("\n### 이전 대화 요약")
            parts.add(summary)
        }

        // 사용자 선호도
        if (userContext.preferences.isNotEmpty()) {
            parts.add("\n### 사용자 선호도")
            userContext.preferences.forEach { (key, value) ->
                parts.add("- $key: $value")
            }
        }

        // 활성 세션 정보
        userContext.sessionInfo?.let { session ->
            parts.add("\n### 활성 세션")
            parts.add("- 세션 시작: ${session.startedAt}")
            parts.add("- 주제: ${session.topic}")
        }

        return parts.joinToString("\n")
    }

    /**
     * 대화 히스토리 빌드 (토큰 제한 고려)
     */
    private fun buildHistory(
        history: List<ConversationMessage>,
        maxTokens: Int
    ): HistoryResult {
        if (history.isEmpty()) {
            return HistoryResult(emptyList(), null, 0, 0, 0)
        }

        val messages = mutableListOf<Message>()
        var totalTokens = 0
        var includedCount = 0

        // 최신 메시지부터 역순으로 추가 (토큰 제한까지)
        val reversedHistory = history.takeLast(maxHistoryMessages).reversed()

        for (msg in reversedHistory) {
            val content = msg.content
            val tokens = tokenEstimator.estimate(content)

            if (totalTokens + tokens > maxTokens) {
                break
            }

            val role = when (msg.role.lowercase()) {
                "user" -> Role.USER
                "assistant" -> Role.ASSISTANT
                else -> Role.USER
            }

            messages.add(0, Message(role, content)) // 앞에 추가 (역순 복원)
            totalTokens += tokens
            includedCount++
        }

        // 포함되지 않은 메시지가 있으면 요약 생성
        val summarizedCount = history.size - includedCount
        val summary = if (summarizedCount > summaryThreshold) {
            buildHistorySummary(history.take(summarizedCount))
        } else {
            null
        }

        summary?.let { totalTokens += tokenEstimator.estimate(it) }

        return HistoryResult(messages, summary, totalTokens, includedCount, summarizedCount)
    }

    /**
     * 히스토리 요약 생성 (간단한 추출 기반)
     */
    private fun buildHistorySummary(messages: List<ConversationMessage>): String {
        if (messages.isEmpty()) return ""

        val topics = mutableSetOf<String>()
        val keyPoints = mutableListOf<String>()

        for (msg in messages) {
            // 키워드 추출 (간단한 휴리스틱)
            val keywords = extractKeywords(msg.content)
            topics.addAll(keywords.take(3))

            // 중요 문장 추출 (질문이나 결론)
            if (msg.content.contains("?") || msg.content.contains("결론") || msg.content.contains("요약")) {
                keyPoints.add(msg.content.take(100))
            }
        }

        return buildString {
            append("## 이전 대화 요약 (${messages.size}개 메시지)\n\n")
            if (topics.isNotEmpty()) {
                append("### 주요 주제\n")
                append(topics.take(5).joinToString(", "))
                append("\n\n")
            }
            if (keyPoints.isNotEmpty()) {
                append("### 주요 포인트\n")
                keyPoints.take(3).forEach { append("- $it\n") }
            }
        }
    }

    /**
     * 키워드 추출 (간단한 휴리스틱)
     */
    private fun extractKeywords(text: String): List<String> {
        val stopwords = setOf("이", "가", "을", "를", "의", "에", "에서", "로", "으로", "와", "과", "도", "만",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could", "should")

        return text
            .lowercase()
            .split(Regex("[\\s,.!?;:()\\[\\]{}\"']+"))
            .filter { it.length >= 2 && it !in stopwords }
            .groupBy { it }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key }
    }

    /**
     * 세션 복원 (이전 세션에서 컨텍스트 복구)
     */
    fun restoreSession(
        sessionId: String,
        previousMessages: List<ConversationMessage>,
        userContext: SessionUserContext?
    ): RestoredSession {
        // 세션 시간 확인
        val lastMessage = previousMessages.lastOrNull()
        val lastMessageTime = lastMessage?.timestamp?.let {
            try { Instant.parse(it) } catch (e: Exception) { null }
        }
        val sessionAge = if (lastMessageTime != null) {
            ChronoUnit.HOURS.between(lastMessageTime, Instant.now())
        } else {
            Long.MAX_VALUE
        }

        // 24시간 이상 된 세션은 요약만 유지
        val shouldSummarize = sessionAge > 24 || previousMessages.size > summaryThreshold

        return if (shouldSummarize) {
            val summary = buildHistorySummary(previousMessages)
            RestoredSession(
                sessionId = sessionId,
                isFullRestore = false,
                summary = summary,
                recentMessages = previousMessages.takeLast(5),
                userContext = userContext
            )
        } else {
            RestoredSession(
                sessionId = sessionId,
                isFullRestore = true,
                summary = null,
                recentMessages = previousMessages,
                userContext = userContext
            )
        }
    }
}

// ==================== 데이터 클래스 ====================

/**
 * 대화 메시지
 */
data class ConversationMessage(
    val role: String,   // "user", "assistant", "system"
    val content: String,
    val timestamp: String? = null
)

/**
 * 사용자 컨텍스트 (세션용)
 */
data class SessionUserContext(
    val summary: String? = null,
    val preferences: Map<String, String> = emptyMap(),
    val sessionInfo: SessionInfo? = null
)

data class SessionInfo(
    val startedAt: String,
    val topic: String
)

data class SessionContextRequest(
    val agent: Agent,
    val currentMessage: String,
    val conversationHistory: List<ConversationMessage> = emptyList(),
    val userContext: SessionUserContext? = null,
    val userRules: List<String> = emptyList(),
    val projectContext: ProjectContext? = null,
    val additionalInstructions: String? = null
)

data class SessionContext(
    val messages: List<Message>,
    val totalTokens: Int,
    val includedHistoryCount: Int,
    val summarizedHistoryCount: Int,
    val hasUserContext: Boolean,
    val buildTimeMs: Long
)

data class Message(
    val role: Role,
    val content: String
)

enum class Role {
    SYSTEM, USER, ASSISTANT
}

data class ProjectContext(
    val name: String,
    val description: String,
    val technologies: List<String> = emptyList(),
    val conventions: List<String> = emptyList()
)

data class HistoryResult(
    val messages: List<Message>,
    val summary: String?,
    val totalTokens: Int,
    val includedCount: Int,
    val summarizedCount: Int
)

data class RestoredSession(
    val sessionId: String,
    val isFullRestore: Boolean,
    val summary: String?,
    val recentMessages: List<ConversationMessage>,
    val userContext: SessionUserContext?
)

// ==================== 토큰 추정기 ====================

interface TokenEstimator {
    fun estimate(text: String): Int
}

class DefaultTokenEstimator : TokenEstimator {
    override fun estimate(text: String): Int {
        // 간단한 추정: 영어 ~4자, 한글 ~2자 = 1 토큰
        val koreanChars = text.count { it in '가'..'힣' }
        val otherChars = text.length - koreanChars

        return (koreanChars / 2) + (otherChars / 4) + 1
    }
}
