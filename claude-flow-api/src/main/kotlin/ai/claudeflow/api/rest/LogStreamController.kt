package ai.claudeflow.api.rest

import ai.claudeflow.core.log.ExecutionLogManager
import ai.claudeflow.core.log.LogEvent
import ai.claudeflow.core.log.LogLevel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asPublisher
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * 실시간 로그 스트리밍 API
 *
 * SSE (Server-Sent Events)를 통해 실시간 로그 제공
 */
@RestController
@RequestMapping("/api/v1/logs")
@CrossOrigin(origins = ["*"])
class LogStreamController {

    private val logManager = ExecutionLogManager.instance

    /**
     * 전체 로그 스트림 (SSE)
     *
     * GET /api/v1/logs/stream
     */
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamAllLogs(): Flux<ServerSentEvent<LogEventDto>> {
        logger.info { "Client connected to log stream" }

        // 하트비트와 로그 스트림 병합
        val heartbeat = Flux.interval(Duration.ofSeconds(15))
            .map {
                ServerSentEvent.builder<LogEventDto>()
                    .event("heartbeat")
                    .comment("keepalive")
                    .build()
            }

        val logs = Flux.from(logManager.logFlow.asPublisher())
            .map { event ->
                ServerSentEvent.builder<LogEventDto>()
                    .id(event.timestamp.toEpochMilli().toString())
                    .event(event.level.name.lowercase())
                    .data(event.toDto())
                    .build()
            }

        return Flux.merge(heartbeat, logs)
            .doOnCancel { logger.info { "Client disconnected from log stream" } }
            .doOnError { e -> logger.error(e) { "Log stream error" } }
    }

    /**
     * 특정 실행의 로그 스트림 (SSE)
     *
     * GET /api/v1/logs/stream/{executionId}
     */
    @GetMapping("/stream/{executionId}", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamExecutionLogs(@PathVariable executionId: String): Flux<ServerSentEvent<LogEventDto>> {
        logger.info { "Client connected to log stream for execution: $executionId" }

        // 하트비트
        val heartbeat = Flux.interval(Duration.ofSeconds(15))
            .map {
                ServerSentEvent.builder<LogEventDto>()
                    .event("heartbeat")
                    .comment("keepalive")
                    .build()
            }

        // 먼저 기존 로그 전송
        val existingLogs = Flux.fromIterable(logManager.getExecutionLogs(executionId))
            .map { event ->
                ServerSentEvent.builder<LogEventDto>()
                    .id(event.timestamp.toEpochMilli().toString())
                    .event(event.level.name.lowercase())
                    .data(event.toDto())
                    .build()
            }

        // 실시간 스트림
        val newLogs = Flux.from(logManager.logFlow.filter { it.executionId == executionId }.asPublisher())
            .map { event ->
                ServerSentEvent.builder<LogEventDto>()
                    .id(event.timestamp.toEpochMilli().toString())
                    .event(event.level.name.lowercase())
                    .data(event.toDto())
                    .build()
            }

        return Flux.merge(heartbeat, Flux.concat(existingLogs, newLogs))
            .doOnCancel { logger.info { "Client disconnected from execution log stream: $executionId" } }
    }

    /**
     * 최근 로그 조회 (REST)
     *
     * GET /api/v1/logs/recent?limit=100
     */
    @GetMapping("/recent")
    fun getRecentLogs(@RequestParam(defaultValue = "100") limit: Int): Mono<List<LogEventDto>> {
        return Mono.just(logManager.getRecentLogs(limit).map { it.toDto() })
    }

    /**
     * 특정 실행의 로그 조회 (REST)
     *
     * GET /api/v1/logs/execution/{executionId}
     */
    @GetMapping("/execution/{executionId}")
    fun getExecutionLogs(@PathVariable executionId: String): Mono<List<LogEventDto>> {
        return Mono.just(logManager.getExecutionLogs(executionId).map { it.toDto() })
    }

    /**
     * 활성 실행 목록 조회
     *
     * GET /api/v1/logs/active
     */
    @GetMapping("/active")
    fun getActiveExecutions(): Mono<List<String>> {
        return Mono.just(logManager.getActiveExecutions())
    }

    /**
     * 하트비트 (SSE 연결 유지용)
     */
    @GetMapping("/heartbeat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun heartbeat(): Flux<ServerSentEvent<String>> {
        return Flux.interval(Duration.ofSeconds(30))
            .map {
                ServerSentEvent.builder<String>()
                    .event("heartbeat")
                    .data("ping")
                    .build()
            }
    }
}

// DTO
data class LogEventDto(
    val executionId: String,
    val timestamp: String,
    val level: String,
    val message: String,
    val details: Map<String, Any?>
)

private fun LogEvent.toDto() = LogEventDto(
    executionId = executionId,
    timestamp = timestamp.toString(),
    level = level.name,
    message = message,
    details = details
)
