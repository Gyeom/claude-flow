package ai.claudeflow.api.slack

import ai.claudeflow.core.alert.AlertService
import ai.claudeflow.core.config.SlackConfig
import ai.claudeflow.core.config.WebhookConfig
import ai.claudeflow.core.event.SlackEvent
import ai.claudeflow.core.event.SlackEventType
import ai.claudeflow.core.event.SlackFile
import ai.claudeflow.core.event.WebhookPayload
import ai.claudeflow.core.storage.Storage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.slack.api.Slack
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.socket_mode.SocketModeClient
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageEvent
import com.slack.api.model.event.ReactionAddedEvent
import com.slack.api.model.event.ReactionRemovedEvent
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Slack Socket Mode Bridge with Auto-Reconnect
 *
 * WebSocket connection to Slack with automatic reconnection,
 * health monitoring, and message retry queue.
 *
 * 모든 이벤트는 slack-router 워크플로우로 전송되어 n8n에서 분류됩니다.
 */
class SlackSocketModeBridge(
    private val slackConfig: SlackConfig,
    private val webhookConfig: WebhookConfig,
    private val webhookSender: WebhookSender,
    private val storage: Storage? = null,
    private val alertService: AlertService? = null,
    private val maxReconnectAttempts: Int = 10,
    private val initialReconnectDelayMs: Long = 1000,
    private val maxReconnectDelayMs: Long = 60000
) {
    private val objectMapper = jacksonObjectMapper()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private var socketModeApp: SocketModeApp? = null
    private var botUserId: String? = null

    // Connection state tracking
    private val connectionState = AtomicReference(ConnectionState.DISCONNECTED)
    private val lastEventTime = AtomicLong(0)
    private val reconnectAttempts = AtomicInteger(0)
    private val totalReconnects = AtomicInteger(0)
    private var connectionStartTime: Instant? = null

    // Failed message queue for retry
    private val failedMessageQueue = ConcurrentLinkedQueue<FailedMessage>()
    private val maxQueueSize = 1000

    // Health check job
    private var healthCheckJob: Job? = null
    private var retryJob: Job? = null

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED
    }

    data class FailedMessage(
        val event: SlackEvent,
        val endpoint: String,
        val failedAt: Instant = Clock.System.now(),
        val retryCount: Int = 0
    )

    /**
     * Get current connection status
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "state" to connectionState.get().name,
            "isRunning" to isRunning.get(),
            "lastEventTime" to lastEventTime.get(),
            "reconnectAttempts" to reconnectAttempts.get(),
            "totalReconnects" to totalReconnects.get(),
            "failedMessageQueueSize" to failedMessageQueue.size,
            "uptime" to (connectionStartTime?.let {
                Clock.System.now().toEpochMilliseconds() - it.toEpochMilliseconds()
            } ?: 0),
            "botUserId" to (botUserId ?: "unknown")
        )
    }

    /**
     * Check if connection is healthy
     */
    fun isHealthy(): Boolean {
        if (connectionState.get() != ConnectionState.CONNECTED) {
            return false
        }
        // Consider unhealthy if no events for 5 minutes (Slack sends heartbeats)
        val lastEvent = lastEventTime.get()
        if (lastEvent > 0) {
            val timeSinceLastEvent = System.currentTimeMillis() - lastEvent
            return timeSinceLastEvent < 300_000 // 5 minutes
        }
        return true
    }

    /**
     * Start Socket Mode connection with auto-reconnect
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            logger.warn { "SlackSocketModeBridge is already running" }
            return
        }

        logger.info { "Starting Slack Socket Mode Bridge with auto-reconnect..." }
        connectionStartTime = Clock.System.now()

        // Start connection
        scope.launch {
            connectWithRetry()
        }

        // Start health check
        startHealthCheck()

        // Start retry processor
        startRetryProcessor()
    }

    private suspend fun connectWithRetry() {
        while (isRunning.get() && reconnectAttempts.get() < maxReconnectAttempts) {
            try {
                connectionState.set(
                    if (reconnectAttempts.get() > 0) ConnectionState.RECONNECTING
                    else ConnectionState.CONNECTING
                )

                connect()

                // Connection successful
                connectionState.set(ConnectionState.CONNECTED)
                reconnectAttempts.set(0)
                logger.info { "Socket Mode connected successfully" }

                // Wait for disconnection
                while (isRunning.get() && socketModeApp != null) {
                    delay(1000)
                }

            } catch (e: Exception) {
                val attempt = reconnectAttempts.incrementAndGet()
                totalReconnects.incrementAndGet()

                val delay = calculateBackoffDelay(attempt)
                logger.error(e) { "Connection failed (attempt $attempt/$maxReconnectAttempts), retrying in ${delay}ms..." }

                // Alert on repeated failures
                if (attempt >= 3) {
                    alertService?.warning(
                        component = "SlackSocketModeBridge",
                        title = "Connection Retry",
                        message = "Slack connection failed, retry attempt $attempt/$maxReconnectAttempts",
                        metadata = mapOf(
                            "error" to (e.message ?: "Unknown"),
                            "nextRetryMs" to delay
                        )
                    )
                }

                connectionState.set(ConnectionState.RECONNECTING)
                delay(delay)
            }
        }

        if (reconnectAttempts.get() >= maxReconnectAttempts) {
            connectionState.set(ConnectionState.FAILED)
            logger.error { "Max reconnect attempts reached. Socket Mode Bridge stopped." }

            // Critical alert - manual intervention needed
            alertService?.critical(
                component = "SlackSocketModeBridge",
                title = "Connection Failed",
                message = "Slack Socket Mode connection failed after $maxReconnectAttempts attempts. Manual restart required!",
                metadata = mapOf(
                    "totalReconnects" to totalReconnects.get(),
                    "failedMessageQueueSize" to failedMessageQueue.size
                )
            )
        }
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        val delay = initialReconnectDelayMs * (1L shl minOf(attempt - 1, 6))
        return minOf(delay, maxReconnectDelayMs)
    }

    private fun connect() {
        logger.info { "Connecting to Slack Socket Mode (JavaWebSocket backend)..." }

        val appConfig = AppConfig.builder()
            .singleTeamBotToken(slackConfig.botToken)
            .build()

        val app = App(appConfig).apply {
            initializeBotUserId()
            registerMentionHandler()
            registerMessageHandler()
            registerReactionHandlers()
        }

        // Use JavaWebSocket backend (more stable for long-running connections)
        socketModeApp = SocketModeApp(
            slackConfig.appToken,
            SocketModeClient.Backend.JavaWebSocket,
            app
        )
        socketModeApp?.start()

        logger.info { "Socket Mode connected (JavaWebSocket)" }
    }

    private fun startHealthCheck() {
        healthCheckJob = scope.launch {
            while (isRunning.get()) {
                delay(60_000) // Check every minute

                if (!isHealthy() && connectionState.get() == ConnectionState.CONNECTED) {
                    logger.warn { "Connection appears unhealthy, triggering reconnect..." }

                    alertService?.warning(
                        component = "SlackSocketModeBridge",
                        title = "Connection Unhealthy",
                        message = "No events received for 5+ minutes, triggering reconnect",
                        metadata = getStatus()
                    )

                    triggerReconnect()
                }

                // Alert if message queue is growing
                if (failedMessageQueue.size > 100) {
                    alertService?.warning(
                        component = "SlackSocketModeBridge",
                        title = "Message Queue Growing",
                        message = "Failed message queue has ${failedMessageQueue.size} messages",
                        metadata = mapOf("queueSize" to failedMessageQueue.size)
                    )
                }

                // Log status periodically
                logger.debug { "Health check: ${getStatus()}" }
            }
        }
    }

    private fun startRetryProcessor() {
        retryJob = scope.launch {
            while (isRunning.get()) {
                delay(10_000) // Process every 10 seconds

                processFailedMessages()
            }
        }
    }

    private suspend fun processFailedMessages() {
        if (failedMessageQueue.isEmpty()) return

        val toRetry = mutableListOf<FailedMessage>()
        repeat(minOf(10, failedMessageQueue.size)) {
            failedMessageQueue.poll()?.let { toRetry.add(it) }
        }

        for (msg in toRetry) {
            if (msg.retryCount >= 5) {
                logger.error { "Message permanently failed after ${msg.retryCount} retries: ${msg.event.id}" }
                // Store in dead letter storage
                saveToDeadLetter(msg, "Max retry count exceeded")
                continue
            }

            val success = sendToWebhookInternal(msg.event, msg.endpoint)
            if (!success) {
                // Re-queue with incremented retry count
                if (failedMessageQueue.size < maxQueueSize) {
                    failedMessageQueue.offer(msg.copy(retryCount = msg.retryCount + 1))
                }
            } else {
                logger.info { "Successfully retried message: ${msg.event.id}" }
            }
        }
    }

    private fun triggerReconnect() {
        scope.launch {
            try {
                socketModeApp?.stop()
            } catch (e: Exception) {
                logger.warn(e) { "Error stopping socket mode app" }
            }
            socketModeApp = null
        }
    }

    /**
     * Stop connection
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        logger.info { "Stopping Slack Socket Mode Bridge..." }
        connectionState.set(ConnectionState.DISCONNECTED)

        healthCheckJob?.cancel()
        retryJob?.cancel()

        try {
            socketModeApp?.stop()
        } catch (e: Exception) {
            logger.warn(e) { "Error stopping socket mode app" }
        }
        socketModeApp = null

        // Persist remaining failed messages to storage for recovery on restart
        if (failedMessageQueue.isNotEmpty()) {
            logger.warn { "Stopping with ${failedMessageQueue.size} messages in queue, persisting for recovery..." }
            persistFailedMessagesForRecovery()
        }
    }

    /**
     * Save a failed message to dead letter storage
     */
    private fun saveToDeadLetter(msg: FailedMessage, errorMessage: String) {
        try {
            storage?.saveToDeadLetter(
                eventId = msg.event.id,
                eventType = msg.event.type.name,
                channel = msg.event.channel,
                userId = msg.event.user,
                text = msg.event.text,
                endpoint = msg.endpoint,
                payload = objectMapper.writeValueAsString(msg),
                errorMessage = errorMessage,
                retryCount = msg.retryCount
            )
            logger.info { "Message saved to dead letter queue: ${msg.event.id}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save message to dead letter queue: ${msg.event.id}" }
        }
    }

    /**
     * Persist failed messages to storage for recovery on restart
     */
    private fun persistFailedMessagesForRecovery() {
        var persisted = 0
        while (failedMessageQueue.isNotEmpty()) {
            val msg = failedMessageQueue.poll() ?: break
            try {
                storage?.saveToDeadLetter(
                    eventId = msg.event.id,
                    eventType = msg.event.type.name,
                    channel = msg.event.channel,
                    userId = msg.event.user,
                    text = msg.event.text,
                    endpoint = msg.endpoint,
                    payload = objectMapper.writeValueAsString(msg),
                    errorMessage = "Service shutdown - pending recovery",
                    retryCount = msg.retryCount
                )
                persisted++
            } catch (e: Exception) {
                logger.error(e) { "Failed to persist message for recovery: ${msg.event.id}" }
            }
        }
        logger.info { "Persisted $persisted messages for recovery on restart" }
    }

    /**
     * Load failed messages from storage on startup (call after start())
     */
    fun loadPersistedMessages() {
        try {
            val messages = storage?.getDeadLetterMessages(100) ?: return
            if (messages.isEmpty()) return

            logger.info { "Loading ${messages.size} persisted messages for retry..." }

            for (dlm in messages) {
                try {
                    val msg = objectMapper.readValue(dlm.payload, FailedMessage::class.java)
                    if (failedMessageQueue.size < maxQueueSize) {
                        failedMessageQueue.offer(msg)
                        storage?.deleteFromDeadLetter(dlm.id)
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to deserialize persisted message: ${dlm.id}" }
                }
            }

            logger.info { "Loaded ${failedMessageQueue.size} messages for retry" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load persisted messages" }
        }
    }

    private fun App.initializeBotUserId() {
        try {
            val client = Slack.getInstance().methods(slackConfig.botToken)
            val response = client.authTest { it }
            if (response.isOk) {
                botUserId = response.userId
                logger.info { "Bot user ID: $botUserId" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get bot user ID" }
        }
    }

    /**
     * @mention event handler
     */
    private fun App.registerMentionHandler() {
        event(AppMentionEvent::class.java) { payload, ctx ->
            lastEventTime.set(System.currentTimeMillis())
            val event = payload.event

            if (event.user == botUserId) {
                return@event ctx.ack()
            }

            val cleanText = event.text
                .replace(Regex("<@[A-Z0-9]+>"), "")
                .trim()

            if (cleanText.isEmpty()) {
                logger.debug { "Empty mention, skipping" }
                return@event ctx.ack()
            }

            val slackEvent = SlackEvent(
                id = UUID.randomUUID().toString(),
                type = SlackEventType.MENTION,
                channel = event.channel,
                user = event.user,
                text = cleanText,
                threadTs = event.threadTs ?: event.ts,
                timestamp = event.ts,
                receivedAt = Clock.System.now()
            )

            scope.launch {
                sendToWebhook(slackEvent, webhookConfig.endpoints.unified)
            }

            ctx.ack()
        }
    }

    /**
     * Message event handler
     * - 봇 메시지 (Sentry, DataDog 등) → 자동 트리거
     * - 사람 메시지 → 기존 로직 유지
     */
    private fun App.registerMessageHandler() {
        event(MessageEvent::class.java) { payload, ctx ->
            lastEventTime.set(System.currentTimeMillis())
            val event = payload.event

            // 자기 자신(Claude Flow 봇)의 메시지는 무시
            if (event.user == botUserId) {
                return@event ctx.ack()
            }

            // 다른 봇/앱의 메시지 → 알람 트리거 처리
            if (event.botId != null || event.subtype == "bot_message") {
                scope.launch {
                    handleAlertBotMessage(event)
                }
                return@event ctx.ack()
            }

            // 사람 메시지 (subtype 있는 특수 메시지는 무시)
            if (event.subtype != null) {
                return@event ctx.ack()
            }

            val files = event.files?.map { file ->
                SlackFile(
                    id = file.id,
                    name = file.name ?: "unknown",
                    mimeType = file.mimetype ?: "application/octet-stream",
                    url = file.urlPrivate ?: ""
                )
            } ?: emptyList()

            val slackEvent = SlackEvent(
                id = UUID.randomUUID().toString(),
                type = SlackEventType.MESSAGE,
                channel = event.channel,
                user = event.user,
                text = event.text ?: "",
                threadTs = event.threadTs,
                timestamp = event.ts,
                files = files,
                receivedAt = Clock.System.now()
            )

            scope.launch {
                sendToWebhook(slackEvent, webhookConfig.endpoints.unified)
            }

            ctx.ack()
        }
    }

    /**
     * 알람 봇 메시지 처리 (Sentry, DataDog, GitLab 등)
     */
    private suspend fun handleAlertBotMessage(event: MessageEvent) {
        logger.info { "Alert bot message detected: botId=${event.botId}" }

        val slackEvent = SlackEvent(
            id = UUID.randomUUID().toString(),
            type = SlackEventType.ALERT_BOT_MESSAGE,
            channel = event.channel,
            user = event.botId ?: event.user ?: "unknown",
            text = event.text ?: "",
            threadTs = event.ts,
            timestamp = event.ts,
            receivedAt = Clock.System.now(),
            botId = event.botId,
            appId = null
        )

        sendToWebhook(slackEvent, webhookConfig.endpoints.unified)
    }

    /**
     * Reaction event handlers
     * - thumbsup/thumbsdown → feedback 엔드포인트 (피드백 수집)
     * - 기타 리액션 → unified 엔드포인트 (액션 트리거 등)
     */
    private fun App.registerReactionHandlers() {
        val feedbackReactions = setOf("thumbsup", "thumbsdown", "+1", "-1")

        event(ReactionAddedEvent::class.java) { payload, ctx ->
            lastEventTime.set(System.currentTimeMillis())
            val event = payload.event

            val slackEvent = SlackEvent(
                id = UUID.randomUUID().toString(),
                type = SlackEventType.REACTION_ADDED,
                channel = event.item.channel,
                user = event.user,
                text = "",
                timestamp = event.item.ts,
                reaction = event.reaction,
                receivedAt = Clock.System.now()
            )

            // 피드백 리액션은 feedback 엔드포인트로, 나머지는 unified로
            val endpoint = if (event.reaction in feedbackReactions) {
                webhookConfig.endpoints.feedback
            } else {
                webhookConfig.endpoints.unified
            }

            scope.launch {
                sendToWebhook(slackEvent, endpoint)
            }

            ctx.ack()
        }

        event(ReactionRemovedEvent::class.java) { payload, ctx ->
            lastEventTime.set(System.currentTimeMillis())
            val event = payload.event

            val slackEvent = SlackEvent(
                id = UUID.randomUUID().toString(),
                type = SlackEventType.REACTION_REMOVED,
                channel = event.item.channel,
                user = event.user,
                text = "",
                timestamp = event.item.ts,
                reaction = event.reaction,
                receivedAt = Clock.System.now()
            )

            // 피드백 리액션은 feedback 엔드포인트로, 나머지는 unified로
            val endpoint = if (event.reaction in feedbackReactions) {
                webhookConfig.endpoints.feedback
            } else {
                webhookConfig.endpoints.unified
            }

            scope.launch {
                sendToWebhook(slackEvent, endpoint)
            }

            ctx.ack()
        }
    }

    private suspend fun sendToWebhook(event: SlackEvent, endpoint: String) {
        val success = sendToWebhookInternal(event, endpoint)
        if (!success) {
            if (failedMessageQueue.size < maxQueueSize) {
                failedMessageQueue.offer(FailedMessage(event, endpoint))
                logger.warn { "Message queued for retry: ${event.id} (queue size: ${failedMessageQueue.size})" }
            } else {
                logger.error { "Failed message queue full, dropping message: ${event.id}" }
            }
        }
    }

    private suspend fun sendToWebhookInternal(event: SlackEvent, endpoint: String): Boolean {
        val payload = WebhookPayload(
            eventId = event.id,
            eventType = event.type,
            channel = event.channel,
            user = event.user,
            text = event.text,
            threadTs = event.threadTs,
            timestamp = event.timestamp,
            reaction = event.reaction,
            files = event.files
        )

        val url = "${webhookConfig.baseUrl}$endpoint"
        return webhookSender.send(url, payload)
    }
}
