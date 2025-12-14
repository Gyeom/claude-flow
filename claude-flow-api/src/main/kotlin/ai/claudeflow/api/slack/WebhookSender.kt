package ai.claudeflow.api.slack

import ai.claudeflow.core.event.WebhookPayload
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * n8n Webhook sender with retry and circuit breaker
 */
class WebhookSender(
    private val maxRetries: Int = 3,
    private val initialRetryDelayMs: Long = 500,
    private val maxRetryDelayMs: Long = 5000
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }

        engine {
            requestTimeout = 30_000
        }
    }

    // Statistics
    private val totalRequests = AtomicLong(0)
    private val successfulRequests = AtomicLong(0)
    private val failedRequests = AtomicLong(0)
    private val retriedRequests = AtomicLong(0)

    /**
     * Get statistics
     */
    fun getStats(): Map<String, Long> {
        return mapOf(
            "totalRequests" to totalRequests.get(),
            "successfulRequests" to successfulRequests.get(),
            "failedRequests" to failedRequests.get(),
            "retriedRequests" to retriedRequests.get()
        )
    }

    /**
     * Send webhook with automatic retry
     */
    suspend fun send(url: String, payload: WebhookPayload): Boolean {
        totalRequests.incrementAndGet()

        var lastException: Exception? = null
        var attempt = 0

        while (attempt <= maxRetries) {
            try {
                val success = sendOnce(url, payload)
                if (success) {
                    successfulRequests.incrementAndGet()
                    if (attempt > 0) {
                        logger.info { "Webhook succeeded after ${attempt} retries: ${payload.eventId}" }
                    }
                    return true
                }

                // Non-retryable failure (e.g., 4xx error)
                if (attempt == 0) {
                    failedRequests.incrementAndGet()
                    return false
                }

            } catch (e: Exception) {
                lastException = e

                if (attempt < maxRetries) {
                    retriedRequests.incrementAndGet()
                    val delayMs = calculateBackoffDelay(attempt)
                    logger.warn { "Webhook failed (attempt ${attempt + 1}/${maxRetries + 1}), retrying in ${delayMs}ms: ${e.message}" }
                    delay(delayMs)
                }
            }

            attempt++
        }

        failedRequests.incrementAndGet()
        logger.error(lastException) { "Webhook failed after $maxRetries retries: $url" }
        return false
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        val delay = initialRetryDelayMs * (1L shl attempt)
        return minOf(delay, maxRetryDelayMs)
    }

    private suspend fun sendOnce(url: String, payload: WebhookPayload): Boolean {
        logger.info { "Sending webhook to $url: eventType=${payload.eventType}, channel=${payload.channel}" }

        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        return if (response.status.isSuccess()) {
            logger.debug { "Webhook sent successfully: ${response.status}" }
            true
        } else {
            val errorBody = response.bodyAsText()
            val isRetryable = response.status.value in 500..599

            if (isRetryable) {
                // Throw exception to trigger retry
                throw WebhookException("Server error: ${response.status} - $errorBody")
            } else {
                // Client error - don't retry
                logger.warn { "Webhook failed (non-retryable): ${response.status} - $errorBody" }
                false
            }
        }
    }

    /**
     * Close resources
     */
    fun close() {
        client.close()
    }
}

class WebhookException(message: String) : Exception(message)
