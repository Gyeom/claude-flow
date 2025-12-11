package ai.claudeflow.api.slack

import ai.claudeflow.core.config.SlackConfig
import ai.claudeflow.core.config.WebhookConfig
import ai.claudeflow.core.event.SlackEvent
import ai.claudeflow.core.event.SlackEventType
import ai.claudeflow.core.event.SlackFile
import ai.claudeflow.core.event.WebhookPayload
import com.slack.api.Slack
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageEvent
import com.slack.api.model.event.ReactionAddedEvent
import com.slack.api.model.event.ReactionRemovedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import mu.KotlinLogging
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Slack Socket Mode 브릿지
 *
 * WebSocket을 통해 Slack과 연결하여 이벤트를 수신하고
 * n8n webhook으로 전달
 */
class SlackSocketModeBridge(
    private val slackConfig: SlackConfig,
    private val webhookConfig: WebhookConfig,
    private val webhookSender: WebhookSender
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private var socketModeApp: SocketModeApp? = null
    private var botUserId: String? = null

    // 피드백 이모지 (👍, 👎)
    private val feedbackReactions = setOf("+1", "-1", "thumbsup", "thumbsdown")

    /**
     * Socket Mode 연결 시작
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            logger.warn { "SlackSocketModeBridge is already running" }
            return
        }

        logger.info { "Starting Slack Socket Mode Bridge..." }

        try {
            val appConfig = AppConfig.builder()
                .singleTeamBotToken(slackConfig.botToken)
                .build()

            val app = App(appConfig).apply {
                // Bot User ID 조회
                initializeBotUserId()

                // 이벤트 핸들러 등록
                registerMentionHandler()
                registerMessageHandler()
                registerReactionHandlers()
            }

            socketModeApp = SocketModeApp(slackConfig.appToken, app).apply {
                startAsync()
            }

            logger.info { "Slack Socket Mode Bridge started successfully" }
        } catch (e: Exception) {
            isRunning.set(false)
            logger.error(e) { "Failed to start Slack Socket Mode Bridge" }
            throw e
        }
    }

    /**
     * 연결 종료
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        logger.info { "Stopping Slack Socket Mode Bridge..." }
        socketModeApp?.stop()
        socketModeApp = null
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
     * @멘션 이벤트 핸들러
     */
    private fun App.registerMentionHandler() {
        event(AppMentionEvent::class.java) { payload, ctx ->
            val event = payload.event

            // 봇 자신의 메시지는 무시
            if (event.user == botUserId) {
                return@event ctx.ack()
            }

            // 멘션 텍스트에서 봇 ID 제거
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
     * 일반 메시지 이벤트 핸들러
     */
    private fun App.registerMessageHandler() {
        event(MessageEvent::class.java) { payload, ctx ->
            val event = payload.event

            // 봇 메시지, 서브타입 있는 메시지 무시
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
     * 리액션 이벤트 핸들러
     */
    private fun App.registerReactionHandlers() {
        // 리액션 추가
        event(ReactionAddedEvent::class.java) { payload, ctx ->
            val event = payload.event

            val isFeedback = feedbackReactions.contains(event.reaction)
            val endpoint = if (isFeedback) {
                webhookConfig.endpoints.feedback
            } else {
                webhookConfig.endpoints.reaction
            }

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

            scope.launch {
                sendToWebhook(slackEvent, endpoint)
            }

            ctx.ack()
        }

        // 리액션 제거
        event(ReactionRemovedEvent::class.java) { payload, ctx ->
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

    private suspend fun sendToWebhook(event: SlackEvent, endpoint: String) {
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
        webhookSender.send(url, payload)
    }
}
