package ai.claudeflow.core.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * 실행 이력 레코드
 *
 * 주요 기능:
 * - 구조화된 출력 지원 (structuredOutput)
 * - 캐시 토큰 추적 (cacheReadTokens, cacheCreationTokens)
 * - 라우팅 방법 추적 (routingMethod)
 * - API 지연 시간 분리 (durationApiMs)
 * - 메타데이터 저장 (metadata)
 * - 세션 ID 지원 (sessionId)
 */
data class ExecutionRecord(
    val id: String,
    val prompt: String,
    val result: String?,
    val status: String,  // SUCCESS, ERROR, TIMEOUT
    val agentId: String,
    val projectId: String?,
    val userId: String?,
    val channel: String?,
    val threadTs: String?,
    val replyTs: String?,
    val durationMs: Long,
    val durationApiMs: Long? = null,  // Claude API 실제 소요 시간
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,      // 캐시에서 읽은 토큰
    val cacheCreationTokens: Int = 0,  // 캐시에 생성된 토큰
    val cost: Double? = null,
    val error: String? = null,
    val model: String? = null,
    val instruction: String? = null,     // 에이전트 지침
    val userContext: String? = null,     // 사용자 컨텍스트 스냅샷
    val structuredOutput: String? = null, // JSON Schema 기반 구조화된 출력
    val routingMethod: String? = null,   // keyword, pattern, semantic, llm, fallback
    val routingConfidence: Double? = null,
    val sessionId: String? = null,       // 대화 세션 ID
    val source: String? = null,          // slack, webhook, api, etc.
    val metadata: String? = null,        // JSON 형식 추가 메타데이터
    val createdAt: Instant = Instant.now()
)

/**
 * 피드백 레코드
 *
 * 주요 기능:
 * - 카테고리 분류 (feedback, trigger, action)
 * - 점수 계산 지원
 */
data class FeedbackRecord(
    val id: String,
    val executionId: String,
    val userId: String,
    val reaction: String,  // thumbsup, thumbsdown, jira, wrench, one, two, etc.
    val category: String = categorizeReaction(reaction),  // feedback, trigger, action
    val createdAt: Instant = Instant.now()
) {
    companion object {
        fun categorizeReaction(reaction: String): String = when (reaction) {
            "thumbsup", "thumbsdown", "+1", "-1", "heart", "tada" -> "feedback"
            "jira", "ticket", "bug" -> "trigger"
            "wrench", "hammer", "one", "two", "three", "four", "five" -> "action"
            else -> "other"
        }

        fun isFeedbackReaction(reaction: String): Boolean =
            categorizeReaction(reaction) == "feedback"

        fun isPositiveReaction(reaction: String): Boolean =
            reaction in listOf("thumbsup", "+1", "heart", "tada")

        fun isNegativeReaction(reaction: String): Boolean =
            reaction in listOf("thumbsdown", "-1")

        /**
         * 피드백 점수 계산 (0-100)
         */
        fun calculateScore(positive: Int, negative: Int): Int? {
            val total = positive + negative
            if (total == 0) return null
            return ((positive.toDouble() / total) * 100).toInt()
        }
    }
}

/**
 * 사용자 컨텍스트
 *
 * 주요 기능:
 * - 사용자 규칙 저장
 * - AI 생성 대화 요약
 * - 최근 대화 추적
 * - 자동 요약 지원 (분산 잠금)
 */
data class UserContext(
    val userId: String,
    val displayName: String?,
    val preferredLanguage: String = "ko",
    val domain: String? = null,
    val lastSeen: Instant = Instant.now(),
    val totalInteractions: Int = 0,
    val summary: String? = null,              // AI 생성 대화 요약
    val summaryUpdatedAt: Instant? = null,    // 마지막 요약 시간
    val summaryLockId: String? = null,        // 요약 분산 잠금 ID
    val summaryLockAt: Instant? = null,       // 잠금 획득 시간
    val totalChars: Long = 0                  // 총 대화 문자 수 (요약 필요 여부 판단용)
)

/**
 * 사용자 규칙
 */
data class UserRule(
    val id: String,
    val userId: String,
    val rule: String,
    val createdAt: Instant = Instant.now()
)

/**
 * 사용자 컨텍스트 조회 응답
 */
data class UserContextResponse(
    val rules: List<String>,
    val summary: String?,
    val recentConversations: List<RecentConversation>,
    val totalConversationCount: Int,
    val needsSummary: Boolean,
    val summaryLocked: Boolean,
    val lockId: String? = null
) {
    companion object {
        // 요약 임계값 상수
        const val CONTEXT_CHAR_THRESHOLD = 8000L
        const val CONTEXT_COUNT_THRESHOLD = 5
        const val MIN_SUMMARY_INTERVAL_SECS = 300L  // 5분
        const val MIN_CONVERSATIONS_FOR_SUMMARY = 3
        const val SUMMARY_LOCK_TTL_SECS = 300L     // 5분

        fun needsSummary(
            totalChars: Long,
            conversationCount: Int,
            lastSummaryAt: Instant?,
            currentSummary: String?
        ): Boolean {
            if (conversationCount < MIN_CONVERSATIONS_FOR_SUMMARY) return false

            val now = Instant.now()
            val intervalOk = lastSummaryAt?.let {
                now.epochSecond - it.epochSecond >= MIN_SUMMARY_INTERVAL_SECS
            } ?: true

            if (!intervalOk) return false

            return totalChars >= CONTEXT_CHAR_THRESHOLD ||
                   conversationCount >= CONTEXT_COUNT_THRESHOLD ||
                   currentSummary.isNullOrBlank()
        }
    }
}

/**
 * 최근 대화 기록
 */
data class RecentConversation(
    val id: String,
    val userMessage: String,
    val response: String?,
    val createdAt: String,
    val hasReactions: Boolean
)

/**
 * 프로젝트 설정
 */
data class ProjectConfig(
    val id: String,
    val name: String,
    val description: String? = null,
    val workingDirectory: String,
    val gitRemote: String? = null,
    val defaultBranch: String = "main",
    val systemPrompt: String? = null,          // 프로젝트 글로벌 시스템 프롬프트
    val allowedTools: List<String>? = null,    // 허용 도구
    val disallowedTools: List<String>? = null, // 차단 도구
    val isDefault: Boolean = false,            // 기본 프로젝트 여부
    val enableUserContext: Boolean = true,     // 사용자 컨텍스트 활성화
    val fallbackAgent: String = "general",     // 분류 실패 시 폴백 에이전트
    val classifyModel: String = "haiku",       // 분류용 모델
    val classifyTimeout: Int = 30,             // 분류 타임아웃 (초)
    val rateLimitRpm: Int = 60,                // 분당 요청 제한
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * 에이전트 설정
 */
data class AgentConfig(
    val id: String,
    val projectId: String? = null,
    val name: String,
    val description: String = "",
    val keywords: List<String> = emptyList(),   // 정규식 지원 (/pattern/)
    val examples: List<String> = emptyList(),   // 시맨틱 라우팅 예제
    val systemPrompt: String,
    val instruction: String? = null,             // 에이전트 특화 지침
    val model: String = "claude-sonnet-4-20250514",
    val maxTokens: Int = 4096,
    val timeout: Int = 300,                      // 실행 타임아웃 (초)
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 0,                       // 우선순위 (높을수록 먼저 매칭)
    val staticResponse: Boolean = false,         // true면 instruction을 응답으로 반환
    val outputSchema: String? = null,            // JSON Schema (구조화된 출력)
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * 분류 결과
 */
@Serializable
data class ClassifyResult(
    val agent: String,
    val instruction: String?,
    val model: String,
    val confidence: Double,
    val method: String,  // keyword, pattern, semantic, llm, fallback
    val matchedKeyword: String? = null,
    val reasoning: String? = null
)

// ==================== Analytics DTOs ====================

/**
 * 피드백 통계
 */
data class FeedbackStats(
    val positive: Long,
    val negative: Long,
    val satisfactionRate: Double,
    val pendingFeedback: Long
)

/**
 * 시계열 데이터 포인트
 */
data class TimeSeriesPoint(
    val timestamp: String,
    val requests: Long,
    val successful: Long,
    val failed: Long,
    val cost: Double,
    val tokens: Long,
    val avgDurationMs: Long,
    val p95DurationMs: Long
)

/**
 * 에러 통계
 */
data class ErrorStats(
    val errorType: String,
    val count: Long,
    val percentage: Double,
    val trend: String  // "up", "down", "stable"
)

/**
 * 사용자 통계
 */
data class UserStats(
    val userId: String,
    val displayName: String?,
    val totalRequests: Long,
    val successRate: Double,
    val totalTokens: Long,
    val totalCost: Double,
    val lastSeen: String?
)
