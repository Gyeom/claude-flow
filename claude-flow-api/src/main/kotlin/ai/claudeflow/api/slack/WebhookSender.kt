package ai.claudeflow.api.slack

import ai.claudeflow.core.event.WebhookPayload
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * n8n Webhook으로 이벤트 전송
 */
class WebhookSender {
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

    /**
     * Webhook 전송
     */
    suspend fun send(url: String, payload: WebhookPayload): Boolean {
        return try {
            logger.info { "Sending webhook to $url: eventType=${payload.eventType}, channel=${payload.channel}" }

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                logger.info { "Webhook sent successfully: ${response.status}" }
                true
            } else {
                val errorBody = response.bodyAsText()
                logger.warn { "Webhook failed: ${response.status} - $errorBody" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to send webhook to $url" }
            false
        }
    }

    /**
     * 리소스 정리
     */
    fun close() {
        client.close()
    }
}
