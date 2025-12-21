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
 *
 * NOTE: baseUrl 기본값은 개발 환경용입니다.
 * 프로덕션에서는 환경변수로 설정하세요:
 * - N8N_URL 또는 claude-flow.webhook.base-url
 */
data class WebhookConfig(
    val baseUrl: String = DEFAULT_N8N_URL,
    val endpoints: WebhookEndpoints = WebhookEndpoints()
) {
    companion object {
        const val DEFAULT_N8N_URL = "http://localhost:5678"
    }
}

data class WebhookEndpoints(
    val mention: String = "/webhook/slack-mention",
    val message: String = "/webhook/slack-message",
    val reaction: String = "/webhook/slack-reaction",
    val feedback: String = "/webhook/slack-feedback",
    val actionTrigger: String = "/webhook/slack-action",
    val alertBot: String = "/webhook/slack-alert-bot",      // 알람 봇 메시지
    val issueCreation: String = "/webhook/slack-issue-creation"  // 이슈 생성 확인
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

/**
 * 이슈 자동 생성 설정
 */
data class IssueCreationConfig(
    val reactions: IssueCreationReactions = IssueCreationReactions(),
    val enabled: Boolean = true
)

/**
 * 이슈 생성 확인용 리액션 이모지
 */
data class IssueCreationReactions(
    val approve: String = "white_check_mark",   // ✅ 생성 승인
    val reject: String = "x"                     // ❌ 생성 거절
) {
    fun isIssueCreationReaction(reaction: String): Boolean {
        return reaction in setOf(approve, reject)
    }

    fun getAction(reaction: String): IssueCreationAction? {
        return when (reaction) {
            approve -> IssueCreationAction.APPROVE
            reject -> IssueCreationAction.REJECT
            else -> null
        }
    }
}

enum class IssueCreationAction {
    APPROVE,  // 이슈 생성 승인
    REJECT    // 이슈 생성 거절
}
