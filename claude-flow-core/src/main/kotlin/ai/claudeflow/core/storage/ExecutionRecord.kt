package ai.claudeflow.core.storage

import java.time.Instant

/**
 * 실행 이력 레코드
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
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cost: Double? = null,
    val error: String? = null,
    val createdAt: Instant = Instant.now()
)

/**
 * 피드백 레코드
 */
data class FeedbackRecord(
    val id: String,
    val executionId: String,
    val userId: String,
    val reaction: String,  // thumbsup, thumbsdown, etc.
    val createdAt: Instant = Instant.now()
)

/**
 * 사용자 컨텍스트
 */
data class UserContext(
    val userId: String,
    val displayName: String?,
    val preferredLanguage: String = "ko",
    val domain: String? = null,
    val lastSeen: Instant = Instant.now(),
    val totalInteractions: Int = 0
)
