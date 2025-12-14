package ai.claudeflow.api.slack

import ai.claudeflow.core.alert.AlertService
import ai.claudeflow.core.config.ActionTriggerConfig
import ai.claudeflow.core.config.SlackConfig
import ai.claudeflow.core.config.WebhookConfig
import ai.claudeflow.core.event.ActionPayload
import ai.claudeflow.core.event.SlackEvent
import ai.claudeflow.core.event.SlackEventType
import ai.claudeflow.core.event.SlackFile
import ai.claudeflow.core.event.WebhookPayload
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
 * health monitoring, and message retry queue
 */
class SlackSocketModeBridge(
    private val slackConfig: SlackConfig,
    private val webhookConfig: WebhookConfig,
    private val webhookSender: WebhookSender,
    private val actionTriggerConfig: ActionTriggerConfig = ActionTriggerConfig(),
    private val alertService: AlertService? = null,
    private val maxReconnectAttempts: Int = 10,
    private val initialReconnectDelayMs: Long = 1000,
    private val maxReconnectDelayMs: Long = 60000
) {
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

    // Feedback emojis
    private val feedbackReactions = setOf("+1", "-1", "thumbsup", "thumbsdown")
    private val actionTriggerEmojis = actionTriggerConfig.triggers.keys

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED
    }

    data class FailedMessage(
        val event: SlackEvent,
        val endpoint: String,
        val actionPayload: ActionPayload? = null,
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
                // TODO: Store in dead letter storage
                continue
            }

            val success = sendToWebhookInternal(msg.event, msg.endpoint, msg.actionPayload)
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

        // Process remaining failed messages
        if (failedMessageQueue.isNotEmpty()) {
            logger.warn { "Stopping with ${failedMessageQueue.size} messages in queue" }
            // TODO: Persist to storage for recovery on restart
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
                sendToWebhook(slackEvent, webhookConfig.endpoints.mention)
            }

            ctx.ack()
        }
    }

    /**
     * Message event handler
     */
    private fun App.registerMessageHandler() {
        event(MessageEvent::class.java) { payload, ctx ->
            lastEventTime.set(System.currentTimeMillis())
            val event = payload.event

            if (event.user == botUserId || event.subtype != null) {
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
                sendToWebhook(slackEvent, webhookConfig.endpoints.message)
            }

            ctx.ack()
        }
    }

    /**
     * Reaction event handlers
     */
    private fun App.registerReactionHandlers() {
        event(ReactionAddedEvent::class.java) { payload, ctx ->
            lastEventTime.set(System.currentTimeMillis())
            val event = payload.event
            val reaction = event.reaction

            val isActionTrigger = actionTriggerEmojis.contains(reaction)
            val isFeedback = feedbackReactions.contains(reaction)

            val (eventType, endpoint) = when {
                isActionTrigger -> SlackEventType.ACTION_TRIGGER to webhookConfig.endpoints.actionTrigger
                isFeedback -> SlackEventType.REACTION_ADDED to webhookConfig.endpoints.feedback
                else -> SlackEventType.REACTION_ADDED to webhookConfig.endpoints.reaction
            }

            val actionPayload = if (isActionTrigger) {
                actionTriggerConfig.triggers[reaction]?.let { trigger ->
                    ActionPayload(
                        actionType = trigger.action,
                        emoji = trigger.emoji,
                        description = trigger.description,
                        targetMessageTs = event.item.ts
                    )
                }
            } else null

            val slackEvent = SlackEvent(
                id = UUID.randomUUID().toString(),
                type = eventType,
                channel = event.item.channel,
                user = event.user,
                text = "",
                timestamp = event.item.ts,
                reaction = reaction,
                receivedAt = Clock.System.now()
            )

            scope.launch {
                sendToWebhook(slackEvent, endpoint, actionPayload)
            }

            if (isActionTrigger) {
                logger.info { "Action trigger detected: $reaction -> ${actionPayload?.actionType}" }
            }

            ctx.ack()
        }

        event(ReactionRemovedEvent::class.java) { payload, ctx ->
            lastEventTime.set(System.currentTimeMillis())
            val event = payload.event

            if (!feedbackReactions.contains(event.reaction)) {
                return@event ctx.ack()
            }

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

            scope.launch {
                sendToWebhook(slackEvent, webhookConfig.endpoints.feedback)
            }

            ctx.ack()
        }
    }

    private suspend fun sendToWebhook(
        event: SlackEvent,
        endpoint: String,
        actionPayload: ActionPayload? = null
    ) {
        val success = sendToWebhookInternal(event, endpoint, actionPayload)
        if (!success) {
            // Queue for retry
            if (failedMessageQueue.size < maxQueueSize) {
                failedMessageQueue.offer(FailedMessage(event, endpoint, actionPayload))
                logger.warn { "Message queued for retry: ${event.id} (queue size: ${failedMessageQueue.size})" }
            } else {
                logger.error { "Failed message queue full, dropping message: ${event.id}" }
            }
        }
    }

    private suspend fun sendToWebhookInternal(
        event: SlackEvent,
        endpoint: String,
        actionPayload: ActionPayload? = null
    ): Boolean {
        val payload = WebhookPayload(
            eventId = event.id,
            eventType = event.type,
            channel = event.channel,
            user = event.user,
            text = event.text,
            threadTs = event.threadTs,
            timestamp = event.timestamp,
            reaction = event.reaction,
            files = event.files,
            action = actionPayload
        )

        val url = "${webhookConfig.baseUrl}$endpoint"
        return webhookSender.send(url, payload)
    }
}
