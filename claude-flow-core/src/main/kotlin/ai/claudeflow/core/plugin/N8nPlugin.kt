package ai.claudeflow.core.plugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * n8n 플러그인
 *
 * n8n API를 통한 워크플로우 관리 및 자연어 기반 워크플로우 생성
 * - 워크플로우 CRUD
 * - 워크플로우 실행
 * - 자연어 → 워크플로우 JSON 생성 (Claude 연동)
 */
class N8nPlugin : BasePlugin() {
    override val id = "n8n"
    override val name = "n8n"
    override val description = "n8n 워크플로우 자동화 관리 및 자연어 기반 워크플로우 생성"

    override val commands = listOf(
        PluginCommand(
            name = "list",
            description = "워크플로우 목록 조회",
            usage = "/n8n list",
            examples = listOf("/n8n list")
        ),
        PluginCommand(
            name = "get",
            description = "워크플로우 상세 조회",
            usage = "/n8n get <workflow-id>",
            examples = listOf("/n8n get 1")
        ),
        PluginCommand(
            name = "create",
            description = "새 워크플로우 생성 (자연어 설명으로)",
            usage = "/n8n create <워크플로우 설명>",
            examples = listOf(
                "/n8n create Slack 메시지 받으면 GPT로 응답 생성",
                "/n8n create 매일 아침 9시에 Jira 이슈 요약 Slack 전송"
            )
        ),
        PluginCommand(
            name = "run",
            description = "워크플로우 수동 실행",
            usage = "/n8n run <workflow-id>",
            examples = listOf("/n8n run 1")
        ),
        PluginCommand(
            name = "activate",
            description = "워크플로우 활성화",
            usage = "/n8n activate <workflow-id>",
            examples = listOf("/n8n activate 1")
        ),
        PluginCommand(
            name = "deactivate",
            description = "워크플로우 비활성화",
            usage = "/n8n deactivate <workflow-id>",
            examples = listOf("/n8n deactivate 1")
        ),
        PluginCommand(
            name = "delete",
            description = "워크플로우 삭제",
            usage = "/n8n delete <workflow-id>",
            examples = listOf("/n8n delete 1")
        ),
        PluginCommand(
            name = "executions",
            description = "최근 실행 내역 조회",
            usage = "/n8n executions [limit]",
            examples = listOf("/n8n executions", "/n8n executions 50")
        ),
        PluginCommand(
            name = "templates",
            description = "사용 가능한 워크플로우 템플릿 목록",
            usage = "/n8n templates",
            examples = listOf("/n8n templates")
        ),
        PluginCommand(
            name = "import",
            description = "JSON 파일에서 워크플로우 가져오기",
            usage = "/n8n import <json-file-path>",
            examples = listOf("/n8n import /path/to/workflow.json")
        )
    )

    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private lateinit var baseUrl: String
    private var sessionCookie: String? = null
    private var sessionExpiry: Instant = Instant.MIN
    private var n8nEmail: String = ""
    private var n8nPassword: String = ""

    override suspend fun initialize(config: Map<String, String>) {
        super.initialize(config)
        baseUrl = getConfig("N8N_URL", "http://localhost:5678").trimEnd('/')
        n8nEmail = getConfig("N8N_EMAIL", "admin@local.dev")
        n8nPassword = getConfig("N8N_PASSWORD", "")

        logger.info { "n8n plugin initialized: $baseUrl" }
    }

    override fun shouldHandle(message: String): Boolean {
        val lower = message.lowercase()
        return lower.startsWith("/n8n") ||
                lower.contains("워크플로우") ||
                lower.contains("workflow") ||
                lower.contains("자동화") ||
                (lower.contains("n8n") && (lower.contains("만들") || lower.contains("생성") || lower.contains("실행")))
    }

    override suspend fun execute(command: String, args: Map<String, Any>): PluginResult {
        return when (command) {
            "list" -> listWorkflows()
            "get" -> getWorkflow(
                args["workflow_id"] as? String ?: return PluginResult(false, error = "Workflow ID required")
            )
            "create" -> createWorkflowFromDescription(
                args["description"] as? String ?: return PluginResult(false, error = "Workflow description required")
            )
            "run" -> runWorkflow(
                args["workflow_id"] as? String ?: return PluginResult(false, error = "Workflow ID required")
            )
            "activate" -> setWorkflowActive(
                args["workflow_id"] as? String ?: return PluginResult(false, error = "Workflow ID required"),
                true
            )
            "deactivate" -> setWorkflowActive(
                args["workflow_id"] as? String ?: return PluginResult(false, error = "Workflow ID required"),
                false
            )
            "delete" -> deleteWorkflow(
                args["workflow_id"] as? String ?: return PluginResult(false, error = "Workflow ID required")
            )
            "executions" -> getExecutions(
                (args["limit"] as? Number)?.toInt() ?: 20
            )
            "templates" -> listTemplates()
            "import" -> importWorkflow(
                args["file_path"] as? String ?: return PluginResult(false, error = "File path required")
            )
            else -> PluginResult(false, error = "Unknown command: $command")
        }
    }

    /**
     * 워크플로우 목록 조회
     */
    private fun listWorkflows(): PluginResult {
        return try {
            ensureAuthenticated()
            val response = apiGet("/rest/workflows")
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
            val workflows = result["data"] as? List<Map<String, Any>> ?: emptyList()

            val formatted = workflows.map { wf ->
                mapOf(
                    "id" to wf["id"],
                    "name" to wf["name"],
                    "active" to wf["active"],
                    "createdAt" to wf["createdAt"],
                    "updatedAt" to wf["updatedAt"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${workflows.size} workflows"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list workflows" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 워크플로우 상세 조회
     */
    private fun getWorkflow(workflowId: String): PluginResult {
        return try {
            ensureAuthenticated()
            val response = apiGet("/rest/workflows/$workflowId")
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
            val workflow = result["data"] as? Map<String, Any> ?: result

            val info = mapOf(
                "id" to workflow["id"],
                "name" to workflow["name"],
                "active" to workflow["active"],
                "nodes" to (workflow["nodes"] as? List<*>)?.size,
                "createdAt" to workflow["createdAt"],
                "updatedAt" to workflow["updatedAt"],
                "settings" to workflow["settings"]
            )

            PluginResult(success = true, data = info)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get workflow: $workflowId" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 자연어 설명으로 워크플로우 생성
     * 이 함수는 기본 템플릿을 반환하고, 실제 AI 생성은 별도 서비스에서 처리
     */
    fun createWorkflowFromDescription(description: String): PluginResult {
        return try {
            // 기본 워크플로우 구조 반환 (실제 AI 생성은 N8nWorkflowGenerator 서비스에서)
            val template = generateBasicTemplate(description)

            PluginResult(
                success = true,
                data = mapOf(
                    "description" to description,
                    "template" to template,
                    "message" to "워크플로우 템플릿이 생성되었습니다. AI 기반 상세 생성은 /n8n generate 명령을 사용하세요."
                ),
                message = "Basic template generated for: $description"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create workflow from description" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 기본 워크플로우 템플릿 생성
     */
    private fun generateBasicTemplate(description: String): Map<String, Any> {
        val lower = description.lowercase()

        // 트리거 타입 감지
        val trigger = when {
            lower.contains("slack") && lower.contains("메시지") -> "slack-webhook"
            lower.contains("webhook") -> "webhook"
            lower.contains("매일") || lower.contains("시간마다") || lower.contains("cron") -> "schedule"
            lower.contains("gitlab") || lower.contains("mr") -> "gitlab-webhook"
            lower.contains("jira") -> "jira-webhook"
            else -> "manual"
        }

        // 액션 타입 감지
        val actions = mutableListOf<String>()
        if (lower.contains("slack") && (lower.contains("전송") || lower.contains("보내"))) actions.add("slack-send")
        if (lower.contains("gpt") || lower.contains("ai") || lower.contains("claude")) actions.add("ai-process")
        if (lower.contains("jira") && lower.contains("이슈")) actions.add("jira-api")
        if (lower.contains("gitlab")) actions.add("gitlab-api")
        if (lower.contains("http") || lower.contains("api")) actions.add("http-request")

        return mapOf(
            "name" to "Generated: ${description.take(50)}",
            "trigger" to trigger,
            "actions" to actions,
            "suggestedNodes" to buildSuggestedNodes(trigger, actions),
            "description" to description
        )
    }

    /**
     * 제안 노드 구조 생성
     */
    private fun buildSuggestedNodes(trigger: String, actions: List<String>): List<Map<String, Any>> {
        val nodes = mutableListOf<Map<String, Any>>()
        var position = 250

        // 트리거 노드
        nodes.add(
            mapOf(
                "type" to when (trigger) {
                    "slack-webhook" -> "n8n-nodes-base.webhook"
                    "webhook" -> "n8n-nodes-base.webhook"
                    "schedule" -> "n8n-nodes-base.scheduleTrigger"
                    "gitlab-webhook" -> "n8n-nodes-base.gitlabTrigger"
                    "jira-webhook" -> "n8n-nodes-base.jiraTrigger"
                    else -> "n8n-nodes-base.manualTrigger"
                },
                "name" to "Trigger",
                "position" to listOf(position, 300)
            )
        )
        position += 200

        // 액션 노드들
        for (action in actions) {
            nodes.add(
                mapOf(
                    "type" to when (action) {
                        "slack-send" -> "n8n-nodes-base.slack"
                        "ai-process" -> "n8n-nodes-base.openAi"
                        "jira-api" -> "n8n-nodes-base.jira"
                        "gitlab-api" -> "n8n-nodes-base.gitlab"
                        "http-request" -> "n8n-nodes-base.httpRequest"
                        else -> "n8n-nodes-base.noOp"
                    },
                    "name" to action.replace("-", " ").replaceFirstChar { it.uppercase() },
                    "position" to listOf(position, 300)
                )
            )
            position += 200
        }

        return nodes
    }

    /**
     * 워크플로우 실행
     */
    private fun runWorkflow(workflowId: String): PluginResult {
        return try {
            ensureAuthenticated()
            apiPost("/rest/workflows/$workflowId/run", "{}")

            PluginResult(
                success = true,
                message = "Workflow $workflowId executed"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to run workflow: $workflowId" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 워크플로우 활성화/비활성화
     */
    private fun setWorkflowActive(workflowId: String, active: Boolean): PluginResult {
        return try {
            ensureAuthenticated()
            val body = mapper.writeValueAsString(mapOf("active" to active))
            apiPatch("/rest/workflows/$workflowId", body)

            PluginResult(
                success = true,
                message = "Workflow $workflowId ${if (active) "activated" else "deactivated"}"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to set workflow active: $workflowId" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 워크플로우 삭제
     */
    private fun deleteWorkflow(workflowId: String): PluginResult {
        return try {
            ensureAuthenticated()
            apiDelete("/rest/workflows/$workflowId")

            PluginResult(
                success = true,
                message = "Workflow $workflowId deleted"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete workflow: $workflowId" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 실행 내역 조회
     */
    private fun getExecutions(limit: Int): PluginResult {
        return try {
            ensureAuthenticated()
            val response = apiGet("/rest/executions?limit=$limit")
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
            val data = result["data"] as? Map<String, Any>
            val executions = data?.get("results") as? List<Map<String, Any>> ?: emptyList()

            val formatted = executions.map { exec ->
                mapOf(
                    "id" to exec["id"],
                    "workflowId" to exec["workflowId"],
                    "workflowName" to exec["workflowName"],
                    "status" to exec["status"],
                    "startedAt" to exec["startedAt"],
                    "stoppedAt" to exec["stoppedAt"],
                    "mode" to exec["mode"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${executions.size} executions"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get executions" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 템플릿 목록
     */
    private fun listTemplates(): PluginResult {
        val templates = listOf(
            mapOf(
                "id" to "slack-mention-handler",
                "name" to "Slack Mention Handler",
                "description" to "Slack 멘션 수신 → Claude 처리 → 응답 전송",
                "trigger" to "webhook",
                "integrations" to listOf("Slack", "Claude Flow API")
            ),
            mapOf(
                "id" to "gitlab-mr-review",
                "name" to "GitLab MR Auto Review",
                "description" to "GitLab MR 생성 시 자동 코드 리뷰",
                "trigger" to "gitlab-webhook",
                "integrations" to listOf("GitLab", "Claude Flow API")
            ),
            mapOf(
                "id" to "daily-report",
                "name" to "Daily Report",
                "description" to "매일 아침 Jira/GitLab 요약 Slack 전송",
                "trigger" to "schedule",
                "integrations" to listOf("Jira", "GitLab", "Slack")
            ),
            mapOf(
                "id" to "jira-auto-fix",
                "name" to "Jira Auto Fix Scheduler",
                "description" to "미해결 이슈 자동 분석 및 해결 제안",
                "trigger" to "schedule",
                "integrations" to listOf("Jira", "Claude Flow API")
            ),
            mapOf(
                "id" to "slack-feedback-handler",
                "name" to "Slack Feedback Handler",
                "description" to "Slack 리액션 피드백 수집",
                "trigger" to "webhook",
                "integrations" to listOf("Slack", "Claude Flow API")
            )
        )

        return PluginResult(
            success = true,
            data = templates,
            message = "Found ${templates.size} templates"
        )
    }

    /**
     * JSON 파일에서 워크플로우 가져오기
     */
    private fun importWorkflow(filePath: String): PluginResult {
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                return PluginResult(false, error = "File not found: $filePath")
            }

            val content = file.readText()
            val workflow = mapper.readValue(content, Map::class.java) as Map<String, Any>

            // versionId, id 제거 (API에서 자동 생성)
            val workflowData = workflow.filterKeys { it !in listOf("versionId", "id") }

            ensureAuthenticated()
            val response = apiPost("/rest/workflows", mapper.writeValueAsString(workflowData))
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
            val newWorkflow = result["data"] as? Map<String, Any> ?: result

            PluginResult(
                success = true,
                data = mapOf(
                    "id" to newWorkflow["id"],
                    "name" to newWorkflow["name"]
                ),
                message = "Workflow imported: ${newWorkflow["name"]}"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to import workflow: $filePath" }
            PluginResult(false, error = e.message)
        }
    }

    // ==================== n8n 인증 ====================

    private fun ensureAuthenticated() {
        if (sessionCookie != null && Instant.now().plusSeconds(300).isBefore(sessionExpiry)) {
            return
        }

        if (n8nPassword.isEmpty()) {
            logger.warn { "n8n password not configured, skipping authentication" }
            return
        }

        try {
            val loginBody = mapper.writeValueAsString(
                mapOf(
                    "emailOrLdapLoginId" to n8nEmail,
                    "password" to n8nPassword
                )
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/rest/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() in 200..299) {
                val cookies = response.headers().allValues("Set-Cookie")
                val authCookie = cookies.find { it.startsWith("n8n-auth=") }
                if (authCookie != null) {
                    sessionCookie = authCookie.split(";")[0]
                    sessionExpiry = Instant.now().plusSeconds(6 * 24 * 60 * 60)
                    logger.info { "Successfully logged into n8n" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to login to n8n" }
        }
    }

    // ==================== HTTP Helpers ====================

    private fun apiGet(path: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .apply { sessionCookie?.let { header("Cookie", it) } }
            .header("Content-Type", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("n8n API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    private fun apiPost(path: String, body: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .apply { sessionCookie?.let { header("Cookie", it) } }
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("n8n API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    private fun apiPatch(path: String, body: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .apply { sessionCookie?.let { header("Cookie", it) } }
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("n8n API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    private fun apiDelete(path: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .apply { sessionCookie?.let { header("Cookie", it) } }
            .header("Content-Type", "application/json")
            .DELETE()
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("n8n API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }
}
