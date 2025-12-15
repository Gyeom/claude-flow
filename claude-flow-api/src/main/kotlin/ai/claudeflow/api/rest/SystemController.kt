package ai.claudeflow.api.rest

import ai.claudeflow.api.slack.SlackSocketModeBridge
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * System monitoring and health check API
 */
@RestController
@RequestMapping("/api/v1/system")
class SystemController(
    private val slackSocketModeBridge: SlackSocketModeBridge
) {
    /**
     * Comprehensive health check
     */
    @GetMapping("/health")
    fun health(): Mono<ResponseEntity<SystemHealthResponse>> = mono {
        val slackStatus = slackSocketModeBridge.getStatus()
        val slackHealthy = slackSocketModeBridge.isHealthy()

        val overallHealthy = slackHealthy

        ResponseEntity.ok(SystemHealthResponse(
            status = if (overallHealthy) "healthy" else "degraded",
            components = mapOf(
                "slack" to ComponentHealth(
                    status = if (slackHealthy) "healthy" else "unhealthy",
                    details = slackStatus
                )
            )
        ))
    }

    /**
     * Slack connection status
     */
    @GetMapping("/slack/status")
    fun slackStatus(): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val status = slackSocketModeBridge.getStatus()
        ResponseEntity.ok(status)
    }

    /**
     * Slack connection health check
     */
    @GetMapping("/slack/health")
    fun slackHealth(): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val healthy = slackSocketModeBridge.isHealthy()
        val status = slackSocketModeBridge.getStatus()

        ResponseEntity.ok(mapOf(
            "healthy" to healthy,
            "state" to (status["state"] ?: "unknown"),
            "uptime" to (status["uptime"] ?: 0),
            "totalReconnects" to (status["totalReconnects"] ?: 0),
            "failedMessageQueueSize" to (status["failedMessageQueueSize"] ?: 0)
        ))
    }

    /**
     * Force Slack reconnection (for debugging)
     */
    @PostMapping("/slack/reconnect")
    fun forceReconnect(): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.warn { "Force reconnect requested" }

        try {
            slackSocketModeBridge.stop()
            Thread.sleep(1000)
            slackSocketModeBridge.start()
            ResponseEntity.ok(mapOf("success" to true, "message" to "Reconnection initiated"))
        } catch (e: Exception) {
            logger.error(e) { "Failed to force reconnect" }
            ResponseEntity.ok(mapOf("success" to false, "error" to (e.message ?: "Unknown error")))
        }
    }
}

data class SystemHealthResponse(
    val status: String,
    val components: Map<String, ComponentHealth>
)

data class ComponentHealth(
    val status: String,
    val details: Map<String, Any>
)
