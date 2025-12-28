package ai.claudeflow.api.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 채팅 요청 DTO
 *
 * Vercel AI SDK 호환 형식
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatRequest(
    val messages: List<ChatMessage>,
    val projectId: String? = null,
    val agentId: String? = null,
    val userId: String? = null,
    val model: String? = null,
    val maxTurns: Int? = null,
    // 세션 컨텍스트 (후속 질문 시 에이전트 유지용)
    val sessionContext: SessionContext? = null,
    // 요청 소스 (chat, scheduled, slack, api 등)
    // scheduled → mr_review로 변환됨
    val source: String? = null,
    // MR 재분석 건너뛰기 (이미 컨텍스트 포함된 경우)
    val skipMrAnalysis: Boolean = false
)

/**
 * 세션 컨텍스트
 *
 * 대화 세션의 상태를 유지하여 후속 질문 시 동일 에이전트 사용
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionContext(
    val lastAgentId: String? = null,       // 마지막 사용된 에이전트
    val lastTopic: String? = null,          // 대화 주제 (mr-review, bug-fix 등)
    val mrNumber: Int? = null,              // MR 리뷰 중인 MR 번호
    val gitlabPath: String? = null,         // GitLab 프로젝트 경로
    val projectId: String? = null           // 선택된 프로젝트 ID
)

/**
 * 채팅 메시지
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatMessage(
    val role: String,  // "user" | "assistant" | "system"
    val content: String,
    val name: String? = null  // 사용자 이름 (optional)
)

/**
 * 채팅 스트리밍 이벤트
 *
 * SSE로 전송되는 이벤트 타입들
 */
sealed class ChatStreamEvent {
    /**
     * 텍스트 청크 (assistant 응답)
     */
    data class Text(
        val content: String
    ) : ChatStreamEvent()

    /**
     * 도구 호출 시작
     */
    data class ToolStart(
        val toolId: String,
        val toolName: String,
        val input: Map<String, Any?>
    ) : ChatStreamEvent()

    /**
     * 도구 호출 완료
     */
    data class ToolEnd(
        val toolId: String,
        val toolName: String,
        val result: String?,
        val success: Boolean
    ) : ChatStreamEvent()

    /**
     * 메타데이터 (라우팅 정보)
     */
    data class Metadata(
        val agentId: String,
        val agentName: String,
        val confidence: Double,
        val routingMethod: String
    ) : ChatStreamEvent()

    /**
     * 에러
     */
    data class Error(
        val message: String,
        val code: String? = null
    ) : ChatStreamEvent()

    /**
     * 완료
     */
    data class Done(
        val requestId: String,
        val agentId: String,
        val durationMs: Long,
        val usage: UsageInfo? = null,
        val cost: Double? = null
    ) : ChatStreamEvent()
}

/**
 * 토큰 사용량 정보
 */
data class UsageInfo(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0
)

/**
 * 채팅 응답 (비스트리밍용)
 */
data class ChatResponse(
    val requestId: String,
    val success: Boolean,
    val content: String?,
    val agentId: String,
    val agentName: String,
    val confidence: Double,
    val durationMs: Long,
    val usage: UsageInfo? = null,
    val cost: Double? = null,
    val error: String? = null
)
