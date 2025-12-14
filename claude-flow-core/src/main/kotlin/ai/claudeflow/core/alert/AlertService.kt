package ai.claudeflow.core.alert

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Alert service for system notifications
 *
 * Supports multiple alert channels:
 * - Slack webhook (recommended for ops channel)
 * - Console logging (fallback)
 */
class AlertService(
    private val slackWebhookUrl: String? = null,
    private val alertCooldownMs: Long = 300_000 // 5 minutes cooldown between same alerts
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // Track last alert time for each type to prevent spam
    private val lastAlertTimes = ConcurrentHashMap<String, Long>()
    private val alertCounts = ConcurrentHashMap<String, AtomicLong>()

    enum class AlertLevel {
        INFO, WARNING, ERROR, CRITICAL
    }

    data class Alert(
        val level: AlertLevel,
        val title: String,
        val message: String,
        val component: String,
        val metadata: Map<String, Any> = emptyMap()
    )

    /**
     * Send an alert
     */
    fun alert(alert: Alert) {
        val alertKey = "${alert.component}:${alert.title}"

        // Check cooldown
        val lastAlert = lastAlertTimes[alertKey] ?: 0
        val now = System.currentTimeMillis()

        if (now - lastAlert < alertCooldownMs && alert.level != AlertLevel.CRITICAL) {
            // Increment suppressed count
            alertCounts.computeIfAbsent(alertKey) { AtomicLong(0) }.incrementAndGet()
            logger.debug { "Alert suppressed (cooldown): $alertKey" }
            return
        }

        lastAlertTimes[alertKey] = now
        val suppressedCount = alertCounts[alertKey]?.getAndSet(0) ?: 0

        scope.launch {
            sendAlert(alert, suppressedCount)
        }
    }

    /**
     * Convenience methods
     */
    fun info(component: String, title: String, message: String, metadata: Map<String, Any> = emptyMap()) {
        alert(Alert(AlertLevel.INFO, title, message, component, metadata))
    }

    fun warning(component: String, title: String, message: String, metadata: Map<String, Any> = emptyMap()) {
        alert(Alert(AlertLevel.WARNING, title, message, component, metadata))
    }

    fun error(component: String, title: String, message: String, metadata: Map<String, Any> = emptyMap()) {
        alert(Alert(AlertLevel.ERROR, title, message, component, metadata))
    }

    fun critical(component: String, title: String, message: String, metadata: Map<String, Any> = emptyMap()) {
        alert(Alert(AlertLevel.CRITICAL, title, message, component, metadata))
    }

    private suspend fun sendAlert(alert: Alert, suppressedCount: Long) {
        // Always log
        val logMessage = buildString {
            append("[${alert.level}] ")
            append("[${alert.component}] ")
            append("${alert.title}: ${alert.message}")
            if (suppressedCount > 0) {
                append(" (+ $suppressedCount suppressed)")
            }
            if (alert.metadata.isNotEmpty()) {
                append(" | ${alert.metadata}")
            }
        }

        when (alert.level) {
            AlertLevel.INFO -> logger.info { logMessage }
            AlertLevel.WARNING -> logger.warn { logMessage }
            AlertLevel.ERROR -> logger.error { logMessage }
            AlertLevel.CRITICAL -> logger.error { "🚨 CRITICAL: $logMessage" }
        }

        // Send to Slack if configured
        if (!slackWebhookUrl.isNullOrBlank()) {
            sendToSlack(alert, suppressedCount)
        }
    }

    private fun sendToSlack(alert: Alert, suppressedCount: Long) {
        try {
            val emoji = when (alert.level) {
                AlertLevel.INFO -> ":information_source:"
                AlertLevel.WARNING -> ":warning:"
                AlertLevel.ERROR -> ":x:"
                AlertLevel.CRITICAL -> ":rotating_light:"
            }

            val color = when (alert.level) {
                AlertLevel.INFO -> "#36a64f"
                AlertLevel.WARNING -> "#ffcc00"
                AlertLevel.ERROR -> "#ff6600"
                AlertLevel.CRITICAL -> "#ff0000"
            }

            val suppressedText = if (suppressedCount > 0) {
                "\n_(+$suppressedCount similar alerts suppressed)_"
            } else ""

            val metadataText = if (alert.metadata.isNotEmpty()) {
                "\n```${alert.metadata.entries.joinToString("\n") { "${it.key}: ${it.value}" }}```"
            } else ""

            val payload = """
                {
                    "attachments": [
                        {
                            "color": "$color",
                            "blocks": [
                                {
                                    "type": "section",
                                    "text": {
                                        "type": "mrkdwn",
                                        "text": "$emoji *[${alert.level}] ${alert.component}*\n*${alert.title}*\n${alert.message}$suppressedText$metadataText"
                                    }
                                }
                            ]
                        }
                    ]
                }
            """.trimIndent()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(slackWebhookUrl!!))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                logger.warn { "Failed to send Slack alert: ${response.statusCode()} - ${response.body()}" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send Slack alert" }
        }
    }

    /**
     * Get alert statistics
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "alertCounts" to alertCounts.mapValues { it.value.get() },
            "lastAlertTimes" to lastAlertTimes.toMap()
        )
    }
}
