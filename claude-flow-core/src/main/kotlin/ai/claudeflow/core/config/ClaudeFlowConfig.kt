package ai.claudeflow.core.config

import ai.claudeflow.core.model.Project

/**
 * claude-flow 전체 설정
 */
data class ClaudeFlowConfig(
    val slack: SlackConfig,
    val webhook: WebhookConfig,
    val projects: Map<String, Project> = emptyMap(),
    val defaultProjectId: String = "default"
)

/**
 * Slack 연결 설정
 */
data class SlackConfig(
    val appToken: String,       // xapp-xxx (Socket Mode용)
    val botToken: String,       // xoxb-xxx (Bot Token)
    val signingSecret: String? = null,
    val botUserId: String? = null
)

/**
 * n8n Webhook 설정
 */
data class WebhookConfig(
    val baseUrl: String = "http://localhost:5678",
    val endpoints: WebhookEndpoints = WebhookEndpoints()
)

data class WebhookEndpoints(
    val mention: String = "/webhook/slack-mention",
    val message: String = "/webhook/slack-message",
    val reaction: String = "/webhook/slack-reaction",
    val feedback: String = "/webhook/slack-feedback"
)
