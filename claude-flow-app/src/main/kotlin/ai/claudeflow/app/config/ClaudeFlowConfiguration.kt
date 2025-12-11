package ai.claudeflow.app.config

import ai.claudeflow.api.rest.ClaudeFlowController
import ai.claudeflow.api.slack.SlackMessageSender
import ai.claudeflow.api.slack.SlackSocketModeBridge
import ai.claudeflow.api.slack.WebhookSender
import ai.claudeflow.core.config.SlackConfig
import ai.claudeflow.core.config.WebhookConfig
import ai.claudeflow.core.config.WebhookEndpoints
import ai.claudeflow.executor.ClaudeExecutor
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private val logger = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "claude-flow")
data class ClaudeFlowProperties(
    val slack: SlackProperties = SlackProperties(),
    val webhook: WebhookProperties = WebhookProperties(),
    val claude: ClaudeProperties = ClaudeProperties()
)

data class SlackProperties(
    val appToken: String = "",
    val botToken: String = "",
    val signingSecret: String = ""
)

data class WebhookProperties(
    val baseUrl: String = "http://localhost:5678",
    val mentionEndpoint: String = "/webhook/slack-mention",
    val messageEndpoint: String = "/webhook/slack-message",
    val reactionEndpoint: String = "/webhook/slack-reaction",
    val feedbackEndpoint: String = "/webhook/slack-feedback"
)

data class ClaudeProperties(
    val model: String = "claude-sonnet-4-20250514",
    val maxTokens: Int = 4096,
    val timeoutSeconds: Int = 300
)

@Configuration
@EnableConfigurationProperties(ClaudeFlowProperties::class)
class ClaudeFlowConfiguration(
    private val properties: ClaudeFlowProperties
) {
    private var socketModeBridge: SlackSocketModeBridge? = null

    @Bean
    fun slackConfig(): SlackConfig = SlackConfig(
        appToken = properties.slack.appToken,
        botToken = properties.slack.botToken,
        signingSecret = properties.slack.signingSecret
    )

    @Bean
    fun webhookConfig(): WebhookConfig = WebhookConfig(
        baseUrl = properties.webhook.baseUrl,
        endpoints = WebhookEndpoints(
            mention = properties.webhook.mentionEndpoint,
            message = properties.webhook.messageEndpoint,
            reaction = properties.webhook.reactionEndpoint,
            feedback = properties.webhook.feedbackEndpoint
        )
    )

    @Bean
    fun webhookSender(): WebhookSender = WebhookSender()

    @Bean
    fun slackMessageSender(slackConfig: SlackConfig): SlackMessageSender =
        SlackMessageSender(slackConfig.botToken)

    @Bean
    fun claudeExecutor(): ClaudeExecutor = ClaudeExecutor()

    @Bean
    fun claudeFlowController(
        claudeExecutor: ClaudeExecutor,
        slackMessageSender: SlackMessageSender
    ): ClaudeFlowController = ClaudeFlowController(claudeExecutor, slackMessageSender)

    @Bean
    fun slackSocketModeBridge(
        slackConfig: SlackConfig,
        webhookConfig: WebhookConfig,
        webhookSender: WebhookSender
    ): SlackSocketModeBridge = SlackSocketModeBridge(
        slackConfig = slackConfig,
        webhookConfig = webhookConfig,
        webhookSender = webhookSender
    ).also {
        socketModeBridge = it
    }

    @PostConstruct
    fun startSlackBridge() {
        if (properties.slack.appToken.isNotEmpty() && properties.slack.botToken.isNotEmpty()) {
            logger.info { "Starting Slack Socket Mode Bridge..." }
            socketModeBridge?.start()
        } else {
            logger.warn { "Slack tokens not configured, Socket Mode Bridge not started" }
        }
    }

    @PreDestroy
    fun stopSlackBridge() {
        logger.info { "Stopping Slack Socket Mode Bridge..." }
        socketModeBridge?.stop()
    }
}
