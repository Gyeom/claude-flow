package ai.claudeflow.api.rest

import ai.claudeflow.api.slack.SlackMessageSender
import ai.claudeflow.executor.ClaudeExecutor
import ai.claudeflow.executor.ExecutionRequest
import ai.claudeflow.executor.ExecutionResult
import ai.claudeflow.executor.ExecutionStatus
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * claude-flow REST API
 *
 * n8n 워크플로우에서 호출하는 API 엔드포인트
 */
@RestController
@RequestMapping("/api/v1")
class ClaudeFlowController(
    private val claudeExecutor: ClaudeExecutor,
    private val slackMessageSender: SlackMessageSender
) {
    /**
     * Claude 실행 API
     *
     * n8n에서 호출하여 Claude Code 실행
     */
    @PostMapping("/execute")
    fun execute(@RequestBody request: ExecuteRequest): Mono<ResponseEntity<ExecuteResponse>> = mono {
        logger.info { "Execute request: prompt=${request.prompt.take(50)}..." }

        val executionRequest = ExecutionRequest(
            prompt = request.prompt,
            systemPrompt = request.systemPrompt,
            workingDirectory = request.workingDirectory,
            model = request.model,
            maxTurns = request.maxTurns,
            allowedTools = request.allowedTools,
            deniedTools = request.deniedTools
        )

        val result = claudeExecutor.execute(executionRequest)

        val response = ExecuteResponse(
            requestId = result.requestId,
            success = result.status == ExecutionStatus.SUCCESS,
            result = result.result,
            error = result.error,
            durationMs = result.durationMs,
            usage = result.usage?.let {
                TokenUsageDto(
                    inputTokens = it.inputTokens,
                    outputTokens = it.outputTokens
                )
            }
        )

        if (result.status == ExecutionStatus.SUCCESS) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.status(500).body(response)
        }
    }

    /**
     * Slack 메시지 전송 API
     */
    @PostMapping("/slack/message")
    fun sendSlackMessage(@RequestBody request: SlackMessageRequest): Mono<ResponseEntity<SlackMessageResponse>> = mono {
        logger.info { "Sending Slack message to ${request.channel}" }

        val result = slackMessageSender.sendMessage(
            channel = request.channel,
            text = request.text,
            threadTs = request.threadTs,
            blocks = request.blocks
        )

        result.fold(
            onSuccess = { ts ->
                ResponseEntity.ok(SlackMessageResponse(success = true, timestamp = ts))
            },
            onFailure = { e ->
                ResponseEntity.status(500).body(
                    SlackMessageResponse(success = false, error = e.message)
                )
            }
        )
    }

    /**
     * Slack 리액션 추가 API
     */
    @PostMapping("/slack/reaction")
    fun addSlackReaction(@RequestBody request: SlackReactionRequest): Mono<ResponseEntity<Map<String, Boolean>>> = mono {
        val success = if (request.remove) {
            slackMessageSender.removeReaction(request.channel, request.timestamp, request.emoji)
        } else {
            slackMessageSender.addReaction(request.channel, request.timestamp, request.emoji)
        }

        ResponseEntity.ok(mapOf("success" to success))
    }

    /**
     * Health check
     */
    @GetMapping("/health")
    fun health(): Mono<ResponseEntity<Map<String, String>>> = mono {
        ResponseEntity.ok(mapOf("status" to "ok"))
    }
}

// Request/Response DTOs
data class ExecuteRequest(
    val prompt: String,
    val systemPrompt: String? = null,
    val workingDirectory: String? = null,
    val model: String? = null,
    val maxTurns: Int? = null,
    val allowedTools: List<String>? = null,
    val deniedTools: List<String>? = null
)

data class ExecuteResponse(
    val requestId: String,
    val success: Boolean,
    val result: String? = null,
    val error: String? = null,
    val durationMs: Long = 0,
    val usage: TokenUsageDto? = null
)

data class TokenUsageDto(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0
)

data class SlackMessageRequest(
    val channel: String,
    val text: String,
    val threadTs: String? = null,
    val blocks: String? = null
)

data class SlackMessageResponse(
    val success: Boolean,
    val timestamp: String? = null,
    val error: String? = null
)

data class SlackReactionRequest(
    val channel: String,
    val timestamp: String,
    val emoji: String,
    val remove: Boolean = false
)
