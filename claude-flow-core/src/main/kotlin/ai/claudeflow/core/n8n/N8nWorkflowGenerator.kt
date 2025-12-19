package ai.claudeflow.core.n8n

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * n8n 워크플로우 JSON 생성기
 *
 * 자연어 설명을 바탕으로 n8n 워크플로우 JSON을 생성합니다.
 * Claude와 연동하여 복잡한 워크플로우도 자동 생성 가능합니다.
 */
class N8nWorkflowGenerator {
    private val mapper = jacksonObjectMapper()

    /**
     * 자연어 설명을 n8n 워크플로우 JSON으로 변환
     *
     * @param description 워크플로우 설명 (예: "Slack 메시지 받으면 GPT로 응답")
     * @param options 추가 옵션 (트리거 타입, 환경 설정 등)
     * @return n8n 워크플로우 JSON
     */
    fun generate(description: String, options: GeneratorOptions = GeneratorOptions()): N8nWorkflow {
        val analysis = analyzeDescription(description)

        logger.info { "Generating workflow: $description" }
        logger.debug { "Analysis: trigger=${analysis.triggerType}, actions=${analysis.actions}" }

        val nodes = mutableListOf<N8nNode>()
        val connections = mutableMapOf<String, N8nConnections>()

        var position = 250
        var prevNodeName: String? = null

        // 1. 트리거 노드 추가
        val triggerNode = createTriggerNode(analysis.triggerType, position)
        nodes.add(triggerNode)
        prevNodeName = triggerNode.name
        position += 200

        // 2. 액션 노드들 추가
        for (action in analysis.actions) {
            val actionNode = createActionNode(action, position)
            nodes.add(actionNode)

            // 연결 추가
            if (prevNodeName != null) {
                connections[prevNodeName] = N8nConnections(
                    main = listOf(listOf(N8nConnection(node = actionNode.name, type = "main", index = 0)))
                )
            }

            prevNodeName = actionNode.name
            position += 200
        }

        return N8nWorkflow(
            name = generateWorkflowName(description),
            nodes = nodes,
            connections = connections,
            active = false,
            settings = mapOf("executionOrder" to "v1")
        )
    }

    /**
     * 템플릿 기반 워크플로우 생성
     */
    fun generateFromTemplate(templateId: String, config: Map<String, Any> = emptyMap()): N8nWorkflow {
        return when (templateId) {
            "slack-mention-handler" -> generateSlackMentionHandler(config)
            "gitlab-mr-review" -> generateGitLabMrReview(config)
            "daily-report" -> generateDailyReport(config)
            "jira-auto-fix" -> generateJiraAutoFix(config)
            "slack-feedback-handler" -> generateSlackFeedbackHandler(config)
            "webhook-to-slack" -> generateWebhookToSlack(config)
            "schedule-api-call" -> generateScheduleApiCall(config)
            else -> throw IllegalArgumentException("Unknown template: $templateId")
        }
    }

    /**
     * Claude에게 보낼 프롬프트 생성 (AI 기반 생성용)
     */
    fun generatePromptForClaude(description: String): String {
        return """
You are an expert n8n workflow designer. Generate a complete n8n workflow JSON based on the following description.

Description: $description

Requirements:
1. Use n8n's standard node types
2. Include proper error handling where appropriate
3. Use webhook triggers for external integrations
4. Include all necessary parameters for each node
5. Set appropriate timeout values

Output only valid JSON in the following format:
{
  "name": "Workflow Name",
  "nodes": [...],
  "connections": {...},
  "active": false,
  "settings": {"executionOrder": "v1"}
}

Common node types:
- n8n-nodes-base.webhook (HTTP trigger)
- n8n-nodes-base.scheduleTrigger (cron/interval trigger)
- n8n-nodes-base.httpRequest (HTTP calls)
- n8n-nodes-base.slack (Slack integration)
- n8n-nodes-base.code (JavaScript execution)
- n8n-nodes-base.if (conditional branching)
- n8n-nodes-base.set (set variables)
- n8n-nodes-base.gitlab (GitLab API)
- n8n-nodes-base.jira (Jira API)

For Claude Flow integration, use HTTP requests to:
- POST http://host.docker.internal:8080/api/v1/execute-with-routing (AI processing)
- POST http://host.docker.internal:8080/api/v1/slack/message (send Slack message)
- GET http://host.docker.internal:8080/api/v1/health (health check)
""".trimIndent()
    }

    // ==================== 분석 ====================

    private fun analyzeDescription(description: String): WorkflowAnalysis {
        val lower = description.lowercase()

        val triggerType = detectTriggerType(lower)
        val actions = detectActions(lower)

        return WorkflowAnalysis(
            triggerType = triggerType,
            actions = actions,
            hasCondition = lower.contains("만약") || lower.contains("경우") || lower.contains("if"),
            needsErrorHandling = lower.contains("에러") || lower.contains("실패") || lower.contains("error")
        )
    }

    private fun detectTriggerType(text: String): TriggerType {
        return when {
            text.contains("매일") || text.contains("매시간") || text.contains("매주") -> TriggerType.SCHEDULE
            text.contains("cron") -> TriggerType.SCHEDULE
            text.contains("시간마다") || text.contains("분마다") -> TriggerType.INTERVAL
            text.contains("slack") && (text.contains("메시지") || text.contains("멘션")) -> TriggerType.SLACK_WEBHOOK
            text.contains("gitlab") && (text.contains("mr") || text.contains("push")) -> TriggerType.GITLAB_WEBHOOK
            text.contains("jira") && text.contains("이슈") -> TriggerType.JIRA_WEBHOOK
            text.contains("webhook") || text.contains("http") -> TriggerType.WEBHOOK
            else -> TriggerType.MANUAL
        }
    }

    private fun detectActions(text: String): List<ActionType> {
        val actions = mutableListOf<ActionType>()

        if (text.contains("gpt") || text.contains("ai") || text.contains("claude") ||
            text.contains("응답") || text.contains("생성") || text.contains("분석")) {
            actions.add(ActionType.AI_PROCESS)
        }

        if (text.contains("slack") && (text.contains("전송") || text.contains("보내") || text.contains("알림"))) {
            actions.add(ActionType.SLACK_SEND)
        }

        if (text.contains("jira") && (text.contains("이슈") || text.contains("티켓"))) {
            actions.add(ActionType.JIRA_API)
        }

        if (text.contains("gitlab") && (text.contains("mr") || text.contains("리뷰") || text.contains("코멘트"))) {
            actions.add(ActionType.GITLAB_API)
        }

        if (text.contains("http") || text.contains("api") || text.contains("호출")) {
            actions.add(ActionType.HTTP_REQUEST)
        }

        if (text.contains("저장") || text.contains("기록") || text.contains("로그")) {
            actions.add(ActionType.LOG)
        }

        // 기본: 액션이 없으면 HTTP 요청 추가
        if (actions.isEmpty()) {
            actions.add(ActionType.HTTP_REQUEST)
        }

        return actions
    }

    // ==================== 노드 생성 ====================

    private fun createTriggerNode(type: TriggerType, position: Int): N8nNode {
        return when (type) {
            TriggerType.WEBHOOK, TriggerType.SLACK_WEBHOOK -> N8nNode(
                id = generateId(),
                name = "Webhook",
                type = "n8n-nodes-base.webhook",
                typeVersion = 2,
                position = listOf(position, 300),
                parameters = mapOf(
                    "httpMethod" to "POST",
                    "path" to "webhook-${System.currentTimeMillis()}",
                    "responseMode" to "onReceived",
                    "options" to emptyMap<String, Any>()
                )
            )

            TriggerType.SCHEDULE -> N8nNode(
                id = generateId(),
                name = "Schedule Trigger",
                type = "n8n-nodes-base.scheduleTrigger",
                typeVersion = 1,
                position = listOf(position, 300),
                parameters = mapOf(
                    "rule" to mapOf(
                        "interval" to listOf(
                            mapOf("field" to "hours", "hoursInterval" to 24)
                        )
                    )
                )
            )

            TriggerType.INTERVAL -> N8nNode(
                id = generateId(),
                name = "Schedule Trigger",
                type = "n8n-nodes-base.scheduleTrigger",
                typeVersion = 1,
                position = listOf(position, 300),
                parameters = mapOf(
                    "rule" to mapOf(
                        "interval" to listOf(
                            mapOf("field" to "minutes", "minutesInterval" to 30)
                        )
                    )
                )
            )

            TriggerType.GITLAB_WEBHOOK -> N8nNode(
                id = generateId(),
                name = "GitLab Trigger",
                type = "n8n-nodes-base.webhook",
                typeVersion = 2,
                position = listOf(position, 300),
                parameters = mapOf(
                    "httpMethod" to "POST",
                    "path" to "gitlab-webhook",
                    "responseMode" to "onReceived"
                )
            )

            TriggerType.JIRA_WEBHOOK -> N8nNode(
                id = generateId(),
                name = "Jira Trigger",
                type = "n8n-nodes-base.webhook",
                typeVersion = 2,
                position = listOf(position, 300),
                parameters = mapOf(
                    "httpMethod" to "POST",
                    "path" to "jira-webhook",
                    "responseMode" to "onReceived"
                )
            )

            TriggerType.MANUAL -> N8nNode(
                id = generateId(),
                name = "Manual Trigger",
                type = "n8n-nodes-base.manualTrigger",
                typeVersion = 1,
                position = listOf(position, 300),
                parameters = emptyMap()
            )
        }
    }

    private fun createActionNode(type: ActionType, position: Int): N8nNode {
        return when (type) {
            ActionType.AI_PROCESS -> N8nNode(
                id = generateId(),
                name = "AI Process",
                type = "n8n-nodes-base.httpRequest",
                typeVersion = 4.2,
                position = listOf(position, 300),
                parameters = mapOf(
                    "method" to "POST",
                    "url" to "http://host.docker.internal:8080/api/v1/execute-with-routing",
                    "sendBody" to true,
                    "specifyBody" to "json",
                    "jsonBody" to """={{ JSON.stringify({ prompt: ${'$'}json.text || ${'$'}json.body?.text || "Hello", maxTurns: 10 }) }}""",
                    "options" to mapOf("timeout" to 300000)
                ),
                continueOnFail = true
            )

            ActionType.SLACK_SEND -> N8nNode(
                id = generateId(),
                name = "Send Slack Message",
                type = "n8n-nodes-base.httpRequest",
                typeVersion = 4.2,
                position = listOf(position, 300),
                parameters = mapOf(
                    "method" to "POST",
                    "url" to "http://host.docker.internal:8080/api/v1/slack/message",
                    "sendBody" to true,
                    "specifyBody" to "json",
                    "jsonBody" to """={{ JSON.stringify({ channel: ${'$'}json.channel, text: ${'$'}json.result || ${'$'}json.message || "Done" }) }}""",
                    "options" to mapOf("timeout" to 10000)
                ),
                continueOnFail = true
            )

            ActionType.JIRA_API -> N8nNode(
                id = generateId(),
                name = "Jira API",
                type = "n8n-nodes-base.httpRequest",
                typeVersion = 4.2,
                position = listOf(position, 300),
                parameters = mapOf(
                    "method" to "GET",
                    "url" to "http://host.docker.internal:8080/api/v1/plugins/jira/execute",
                    "sendBody" to true,
                    "specifyBody" to "json",
                    "jsonBody" to """={{ JSON.stringify({ command: "search", args: { jql: "project=PROJ AND status=Open" } }) }}""",
                    "options" to mapOf("timeout" to 30000)
                ),
                continueOnFail = true
            )

            ActionType.GITLAB_API -> N8nNode(
                id = generateId(),
                name = "GitLab API",
                type = "n8n-nodes-base.httpRequest",
                typeVersion = 4.2,
                position = listOf(position, 300),
                parameters = mapOf(
                    "method" to "POST",
                    "url" to "http://host.docker.internal:8080/api/v1/plugins/gitlab/execute",
                    "sendBody" to true,
                    "specifyBody" to "json",
                    "jsonBody" to """={{ JSON.stringify({ command: "mr-list", args: {} }) }}""",
                    "options" to mapOf("timeout" to 30000)
                ),
                continueOnFail = true
            )

            ActionType.HTTP_REQUEST -> N8nNode(
                id = generateId(),
                name = "HTTP Request",
                type = "n8n-nodes-base.httpRequest",
                typeVersion = 4.2,
                position = listOf(position, 300),
                parameters = mapOf(
                    "method" to "GET",
                    "url" to "https://api.example.com/endpoint",
                    "options" to mapOf("timeout" to 30000)
                ),
                continueOnFail = true
            )

            ActionType.LOG -> N8nNode(
                id = generateId(),
                name = "Log Data",
                type = "n8n-nodes-base.code",
                typeVersion = 2,
                position = listOf(position, 300),
                parameters = mapOf(
                    "jsCode" to """
                        console.log('Workflow executed:', JSON.stringify(${'$'}input.item.json, null, 2));
                        return ${'$'}input.item;
                    """.trimIndent()
                )
            )

            ActionType.CONDITION -> N8nNode(
                id = generateId(),
                name = "IF",
                type = "n8n-nodes-base.if",
                typeVersion = 2,
                position = listOf(position, 300),
                parameters = mapOf(
                    "conditions" to mapOf(
                        "boolean" to listOf(
                            mapOf(
                                "value1" to "={{ \$json.success }}",
                                "value2" to true
                            )
                        )
                    )
                )
            )
        }
    }

    // ==================== 템플릿 기반 생성 ====================

    private fun generateSlackMentionHandler(config: Map<String, Any>): N8nWorkflow {
        val claudeFlowUrl = config["claudeFlowUrl"] as? String ?: "http://host.docker.internal:8080"

        return N8nWorkflow(
            name = "Slack Mention Handler (Generated)",
            nodes = listOf(
                N8nNode(
                    id = generateId(),
                    name = "Webhook",
                    type = "n8n-nodes-base.webhook",
                    typeVersion = 2,
                    position = listOf(250, 300),
                    parameters = mapOf(
                        "httpMethod" to "POST",
                        "path" to "slack-mention",
                        "responseMode" to "onReceived"
                    )
                ),
                N8nNode(
                    id = generateId(),
                    name = "Process with Claude",
                    type = "n8n-nodes-base.httpRequest",
                    typeVersion = 4.2,
                    position = listOf(450, 300),
                    parameters = mapOf(
                        "method" to "POST",
                        "url" to "$claudeFlowUrl/api/v1/execute-with-routing",
                        "sendBody" to true,
                        "specifyBody" to "json",
                        "jsonBody" to """={{ JSON.stringify({ prompt: ${'$'}json.body.text, maxTurns: 50 }) }}""",
                        "options" to mapOf("timeout" to 300000)
                    ),
                    continueOnFail = true
                ),
                N8nNode(
                    id = generateId(),
                    name = "Send Reply",
                    type = "n8n-nodes-base.httpRequest",
                    typeVersion = 4.2,
                    position = listOf(650, 300),
                    parameters = mapOf(
                        "method" to "POST",
                        "url" to "$claudeFlowUrl/api/v1/slack/message",
                        "sendBody" to true,
                        "specifyBody" to "json",
                        "jsonBody" to """={{ JSON.stringify({ channel: $('Webhook').item.json.body.channel, text: ${'$'}json.result || 'Error occurred', threadTs: $('Webhook').item.json.body.threadTs }) }}"""
                    ),
                    continueOnFail = true
                )
            ),
            connections = mapOf(
                "Webhook" to N8nConnections(main = listOf(listOf(N8nConnection("Process with Claude", "main", 0)))),
                "Process with Claude" to N8nConnections(main = listOf(listOf(N8nConnection("Send Reply", "main", 0))))
            ),
            active = false,
            settings = mapOf("executionOrder" to "v1")
        )
    }

    private fun generateGitLabMrReview(config: Map<String, Any>): N8nWorkflow {
        val claudeFlowUrl = config["claudeFlowUrl"] as? String ?: "http://host.docker.internal:8080"

        return N8nWorkflow(
            name = "GitLab MR Auto Review (Generated)",
            nodes = listOf(
                N8nNode(
                    id = generateId(),
                    name = "GitLab Webhook",
                    type = "n8n-nodes-base.webhook",
                    typeVersion = 2,
                    position = listOf(250, 300),
                    parameters = mapOf(
                        "httpMethod" to "POST",
                        "path" to "gitlab-mr-review",
                        "responseMode" to "onReceived"
                    )
                ),
                N8nNode(
                    id = generateId(),
                    name = "Check Event Type",
                    type = "n8n-nodes-base.if",
                    typeVersion = 2,
                    position = listOf(450, 300),
                    parameters = mapOf(
                        "conditions" to mapOf(
                            "string" to listOf(
                                mapOf(
                                    "value1" to "={{ \$json.body.object_kind }}",
                                    "operation" to "equals",
                                    "value2" to "merge_request"
                                )
                            )
                        )
                    )
                ),
                N8nNode(
                    id = generateId(),
                    name = "Review with Claude",
                    type = "n8n-nodes-base.httpRequest",
                    typeVersion = 4.2,
                    position = listOf(650, 200),
                    parameters = mapOf(
                        "method" to "POST",
                        "url" to "$claudeFlowUrl/api/v1/gitlab/review",
                        "sendBody" to true,
                        "specifyBody" to "json",
                        "jsonBody" to """={{ JSON.stringify({ projectId: ${'$'}json.body.project.id, mrIid: ${'$'}json.body.object_attributes.iid }) }}""",
                        "options" to mapOf("timeout" to 600000)
                    ),
                    continueOnFail = true
                )
            ),
            connections = mapOf(
                "GitLab Webhook" to N8nConnections(main = listOf(listOf(N8nConnection("Check Event Type", "main", 0)))),
                "Check Event Type" to N8nConnections(main = listOf(
                    listOf(N8nConnection("Review with Claude", "main", 0)),
                    emptyList()
                ))
            ),
            active = false,
            settings = mapOf("executionOrder" to "v1")
        )
    }

    private fun generateDailyReport(config: Map<String, Any>): N8nWorkflow {
        val claudeFlowUrl = config["claudeFlowUrl"] as? String ?: "http://host.docker.internal:8080"
        val slackChannel = config["slackChannel"] as? String ?: "#general"
        val cronExpression = config["cron"] as? String ?: "0 9 * * 1-5" // 평일 9시

        return N8nWorkflow(
            name = "Daily Report (Generated)",
            nodes = listOf(
                N8nNode(
                    id = generateId(),
                    name = "Schedule",
                    type = "n8n-nodes-base.scheduleTrigger",
                    typeVersion = 1.2,
                    position = listOf(250, 300),
                    parameters = mapOf(
                        "rule" to mapOf(
                            "interval" to listOf(
                                mapOf(
                                    "field" to "cronExpression",
                                    "expression" to cronExpression
                                )
                            )
                        )
                    )
                ),
                N8nNode(
                    id = generateId(),
                    name = "Generate Report",
                    type = "n8n-nodes-base.httpRequest",
                    typeVersion = 4.2,
                    position = listOf(450, 300),
                    parameters = mapOf(
                        "method" to "POST",
                        "url" to "$claudeFlowUrl/api/v1/execute-with-routing",
                        "sendBody" to true,
                        "specifyBody" to "json",
                        "jsonBody" to """{ "prompt": "오늘의 Jira 이슈 현황과 GitLab MR 상태를 요약해주세요", "maxTurns": 20 }""",
                        "options" to mapOf("timeout" to 300000)
                    ),
                    continueOnFail = true
                ),
                N8nNode(
                    id = generateId(),
                    name = "Send to Slack",
                    type = "n8n-nodes-base.httpRequest",
                    typeVersion = 4.2,
                    position = listOf(650, 300),
                    parameters = mapOf(
                        "method" to "POST",
                        "url" to "$claudeFlowUrl/api/v1/slack/message",
                        "sendBody" to true,
                        "specifyBody" to "json",
                        "jsonBody" to """={{ JSON.stringify({ channel: "$slackChannel", text: "📊 *Daily Report*\\n" + (${'$'}json.result || "Report generation failed") }) }}"""
                    ),
                    continueOnFail = true
                )
            ),
            connections = mapOf(
                "Schedule" to N8nConnections(main = listOf(listOf(N8nConnection("Generate Report", "main", 0)))),
                "Generate Report" to N8nConnections(main = listOf(listOf(N8nConnection("Send to Slack", "main", 0))))
            ),
            active = false,
            settings = mapOf("executionOrder" to "v1")
        )
    }

    private fun generateJiraAutoFix(config: Map<String, Any>): N8nWorkflow {
        val claudeFlowUrl = config["claudeFlowUrl"] as? String ?: "http://host.docker.internal:8080"

        return N8nWorkflow(
            name = "Jira Auto Fix Scheduler (Generated)",
            nodes = listOf(
                N8nNode(
                    id = generateId(),
                    name = "Schedule",
                    type = "n8n-nodes-base.scheduleTrigger",
                    typeVersion = 1.2,
                    position = listOf(250, 300),
                    parameters = mapOf(
                        "rule" to mapOf(
                            "interval" to listOf(
                                mapOf("field" to "hours", "hoursInterval" to 4)
                            )
                        )
                    )
                ),
                N8nNode(
                    id = generateId(),
                    name = "Analyze Stale Issues",
                    type = "n8n-nodes-base.httpRequest",
                    typeVersion = 4.2,
                    position = listOf(450, 300),
                    parameters = mapOf(
                        "method" to "POST",
                        "url" to "$claudeFlowUrl/api/v1/jira/analyze-stale",
                        "sendBody" to true,
                        "specifyBody" to "json",
                        "jsonBody" to """{ "daysStale": 7, "maxIssues": 10 }""",
                        "options" to mapOf("timeout" to 300000)
                    ),
                    continueOnFail = true
                )
            ),
            connections = mapOf(
                "Schedule" to N8nConnections(main = listOf(listOf(N8nConnection("Analyze Stale Issues", "main", 0))))
            ),
            active = false,
            settings = mapOf("executionOrder" to "v1")
        )
    }

    private fun generateSlackFeedbackHandler(config: Map<String, Any>): N8nWorkflow {
        val claudeFlowUrl = config["claudeFlowUrl"] as? String ?: "http://host.docker.internal:8080"

        return N8nWorkflow(
            name = "Slack Feedback Handler (Generated)",
            nodes = listOf(
                N8nNode(
                    id = generateId(),
                    name = "Webhook",
                    type = "n8n-nodes-base.webhook",
                    typeVersion = 2,
                    position = listOf(250, 300),
                    parameters = mapOf(
                        "httpMethod" to "POST",
                        "path" to "slack-reaction",
                        "responseMode" to "onReceived"
                    )
                ),
                N8nNode(
                    id = generateId(),
                    name = "Save Feedback",
                    type = "n8n-nodes-base.httpRequest",
                    typeVersion = 4.2,
                    position = listOf(450, 300),
                    parameters = mapOf(
                        "method" to "POST",
                        "url" to "$claudeFlowUrl/api/v1/feedback",
                        "sendBody" to true,
                        "specifyBody" to "json",
                        "jsonBody" to """={{ JSON.stringify(${'$'}json.body) }}"""
                    ),
                    continueOnFail = true
                )
            ),
            connections = mapOf(
                "Webhook" to N8nConnections(main = listOf(listOf(N8nConnection("Save Feedback", "main", 0))))
            ),
            active = false,
            settings = mapOf("executionOrder" to "v1")
        )
    }

    private fun generateWebhookToSlack(config: Map<String, Any>): N8nWorkflow {
        val claudeFlowUrl = config["claudeFlowUrl"] as? String ?: "http://host.docker.internal:8080"
        val slackChannel = config["slackChannel"] as? String ?: "#general"

        return N8nWorkflow(
            name = "Webhook to Slack (Generated)",
            nodes = listOf(
                N8nNode(
                    id = generateId(),
                    name = "Webhook",
                    type = "n8n-nodes-base.webhook",
                    typeVersion = 2,
                    position = listOf(250, 300),
                    parameters = mapOf(
                        "httpMethod" to "POST",
                        "path" to "notify",
                        "responseMode" to "onReceived"
                    )
                ),
                N8nNode(
                    id = generateId(),
                    name = "Send Notification",
                    type = "n8n-nodes-base.httpRequest",
                    typeVersion = 4.2,
                    position = listOf(450, 300),
                    parameters = mapOf(
                        "method" to "POST",
                        "url" to "$claudeFlowUrl/api/v1/slack/message",
                        "sendBody" to true,
                        "specifyBody" to "json",
                        "jsonBody" to """={{ JSON.stringify({ channel: "$slackChannel", text: ${'$'}json.body.message || JSON.stringify(${'$'}json.body) }) }}"""
                    ),
                    continueOnFail = true
                )
            ),
            connections = mapOf(
                "Webhook" to N8nConnections(main = listOf(listOf(N8nConnection("Send Notification", "main", 0))))
            ),
            active = false,
            settings = mapOf("executionOrder" to "v1")
        )
    }

    private fun generateScheduleApiCall(config: Map<String, Any>): N8nWorkflow {
        val apiUrl = config["apiUrl"] as? String ?: "https://api.example.com/endpoint"
        val intervalMinutes = (config["intervalMinutes"] as? Number)?.toInt() ?: 60

        return N8nWorkflow(
            name = "Schedule API Call (Generated)",
            nodes = listOf(
                N8nNode(
                    id = generateId(),
                    name = "Schedule",
                    type = "n8n-nodes-base.scheduleTrigger",
                    typeVersion = 1.2,
                    position = listOf(250, 300),
                    parameters = mapOf(
                        "rule" to mapOf(
                            "interval" to listOf(
                                mapOf("field" to "minutes", "minutesInterval" to intervalMinutes)
                            )
                        )
                    )
                ),
                N8nNode(
                    id = generateId(),
                    name = "API Call",
                    type = "n8n-nodes-base.httpRequest",
                    typeVersion = 4.2,
                    position = listOf(450, 300),
                    parameters = mapOf(
                        "method" to "GET",
                        "url" to apiUrl,
                        "options" to mapOf("timeout" to 30000)
                    ),
                    continueOnFail = true
                )
            ),
            connections = mapOf(
                "Schedule" to N8nConnections(main = listOf(listOf(N8nConnection("API Call", "main", 0))))
            ),
            active = false,
            settings = mapOf("executionOrder" to "v1")
        )
    }

    // ==================== Helpers ====================

    private fun generateId(): String = "node-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"

    private fun generateWorkflowName(description: String): String {
        val words = description.take(40).split(" ").take(5)
        return words.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    // ==================== Data Classes ====================

    data class GeneratorOptions(
        val claudeFlowUrl: String = "http://host.docker.internal:8080",
        val includeErrorHandling: Boolean = true,
        val addLogging: Boolean = false
    )

    data class WorkflowAnalysis(
        val triggerType: TriggerType,
        val actions: List<ActionType>,
        val hasCondition: Boolean,
        val needsErrorHandling: Boolean
    )

    enum class TriggerType {
        WEBHOOK, SLACK_WEBHOOK, GITLAB_WEBHOOK, JIRA_WEBHOOK,
        SCHEDULE, INTERVAL, MANUAL
    }

    enum class ActionType {
        AI_PROCESS, SLACK_SEND, JIRA_API, GITLAB_API,
        HTTP_REQUEST, LOG, CONDITION
    }
}

// ==================== n8n Workflow JSON 구조 ====================

data class N8nWorkflow(
    val name: String,
    val nodes: List<N8nNode>,
    val connections: Map<String, N8nConnections>,
    val active: Boolean = false,
    val settings: Map<String, Any> = mapOf("executionOrder" to "v1")
)

data class N8nNode(
    val id: String,
    val name: String,
    val type: String,
    val typeVersion: Number,
    val position: List<Int>,
    val parameters: Map<String, Any> = emptyMap(),
    val continueOnFail: Boolean = false
)

data class N8nConnections(
    val main: List<List<N8nConnection>>
)

data class N8nConnection(
    val node: String,
    val type: String,
    val index: Int
)
