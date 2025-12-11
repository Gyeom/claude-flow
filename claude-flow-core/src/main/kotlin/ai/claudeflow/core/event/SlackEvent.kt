package ai.claudeflow.core.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Slack 이벤트 타입
 */
@Serializable
enum class SlackEventType {
    MENTION,        // @멘션
    MESSAGE,        // 일반 메시지
    REACTION_ADDED, // 리액션 추가
    REACTION_REMOVED, // 리액션 제거
    COMMAND         // 슬래시 커맨드
}

/**
 * Slack에서 수신한 이벤트
 */
@Serializable
data class SlackEvent(
    val id: String,
    val type: SlackEventType,
    val channel: String,
    val user: String,
    val text: String,
    val threadTs: String? = null,
    val timestamp: String,
    val reaction: String? = null,
    val files: List<SlackFile> = emptyList(),
    val receivedAt: Instant
)

/**
 * Slack 첨부 파일
 */
@Serializable
data class SlackFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val url: String
)

/**
 * n8n으로 전송할 웹훅 페이로드
 */
@Serializable
data class WebhookPayload(
    val eventId: String,
    val eventType: SlackEventType,
    val channel: String,
    val user: String,
    val userName: String? = null,
    val text: String,
    val threadTs: String? = null,
    val timestamp: String,
    val reaction: String? = null,
    val files: List<SlackFile> = emptyList(),
    val matchedAgent: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
