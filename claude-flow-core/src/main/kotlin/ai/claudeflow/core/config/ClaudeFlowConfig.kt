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
    val feedback: String = "/webhook/slack-feedback",
    val actionTrigger: String = "/webhook/slack-action"
)

/**
 * Action Trigger 설정
 * 특정 이모지 리액션으로 작업 실행
 */
data class ActionTriggerConfig(
    val triggers: Map<String, ActionTrigger> = defaultTriggers()
) {
    companion object {
        fun defaultTriggers() = mapOf(
            "jira" to ActionTrigger("jira", "create_ticket", "Jira 티켓 생성"),
            "wrench" to ActionTrigger("wrench", "fix_code", "코드 수정 요청"),
            "memo" to ActionTrigger("memo", "summarize", "내용 요약"),
            "eyes" to ActionTrigger("eyes", "review", "코드 리뷰 요청"),
            "rocket" to ActionTrigger("rocket", "deploy", "배포 요청"),
            "one" to ActionTrigger("one", "select_option", "옵션 1 선택"),
            "two" to ActionTrigger("two", "select_option", "옵션 2 선택"),
            "three" to ActionTrigger("three", "select_option", "옵션 3 선택")
        )
    }
}

data class ActionTrigger(
    val emoji: String,
    val action: String,
    val description: String
)
