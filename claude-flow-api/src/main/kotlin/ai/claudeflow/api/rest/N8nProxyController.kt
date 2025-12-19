package ai.claudeflow.api.rest

import ai.claudeflow.core.n8n.N8nWorkflowGenerator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import org.springframework.web.reactive.function.client.awaitExchange
import reactor.core.publisher.Mono
import java.time.Instant

private val logger = KotlinLogging.logger {}
private val mapper = jacksonObjectMapper()

/**
 * n8n API Proxy Controller
 *
 * 대시보드에서 n8n API를 직접 호출하면 CORS 문제가 발생할 수 있으므로
 * 백엔드를 통해 프록시합니다.
 * n8n 인증을 위해 세션 쿠키를 관리합니다.
 */
@RestController
@RequestMapping("/api/v1/n8n")
class N8nProxyController(
    @Value("\${claude-flow.webhook.base-url:http://localhost:5678}")
    private val n8nBaseUrl: String,
    @Value("\${claude-flow.n8n.email:\${N8N_DEFAULT_EMAIL:admin@local.dev}}")
    private val n8nEmail: String,
    @Value("\${claude-flow.n8n.password:\${N8N_DEFAULT_PASSWORD:}}")
    private val n8nPassword: String
) {
    private var sessionCookie: String? = null
    private var sessionExpiry: Instant = Instant.MIN
    private val loginMutex = Mutex()

    private val webClient = WebClient.builder()
        .baseUrl(n8nBaseUrl)
        .build()

    /**
     * Login to n8n and get session cookie
     */
    private suspend fun ensureAuthenticated(): String? {
        // Return cached cookie if still valid (with 5 min buffer)
        if (sessionCookie != null && Instant.now().plusSeconds(300).isBefore(sessionExpiry)) {
            return sessionCookie
        }

        return loginMutex.withLock {
            // Double-check after acquiring lock
            if (sessionCookie != null && Instant.now().plusSeconds(300).isBefore(sessionExpiry)) {
                return@withLock sessionCookie
            }

            try {
                logger.info { "Logging into n8n at $n8nBaseUrl" }
                webClient.post()
                    .uri("/rest/login")
                    .bodyValue(mapOf(
                        "emailOrLdapLoginId" to n8nEmail,
                        "password" to n8nPassword
                    ))
                    .awaitExchange { clientResponse ->
                        val cookies = clientResponse.cookies()
                        val authCookie = cookies["n8n-auth"]?.firstOrNull()
                        if (authCookie != null) {
                            sessionCookie = "n8n-auth=${authCookie.value}"
                            // Cookie expires in 7 days, we'll refresh after 6 days
                            sessionExpiry = Instant.now().plusSeconds(6 * 24 * 60 * 60)
                            logger.info { "Successfully logged into n8n" }
                        }
                        Unit
                    }
                sessionCookie
            } catch (e: Exception) {
                logger.error(e) { "Failed to login to n8n" }
                null
            }
        }
    }

    private fun WebClient.RequestHeadersSpec<*>.withAuth(cookie: String?): WebClient.RequestHeadersSpec<*> {
        return cookie?.let { header("Cookie", it) } ?: this
    }

    /**
     * Get all workflows
     */
    @GetMapping("/workflows")
    fun getWorkflows(): Mono<ResponseEntity<List<N8nWorkflowDto>>> = mono {
        try {
            val cookie = ensureAuthenticated()
            val response = webClient.get()
                .uri("/rest/workflows")
                .withAuth(cookie)
                .retrieve()
                .awaitBodyOrNull<N8nWorkflowsResponse>()

            val workflows = response?.data?.map { it.toDto() } ?: emptyList()
            logger.debug { "Fetched ${workflows.size} workflows from n8n" }
            ResponseEntity.ok(workflows)
        } catch (e: Exception) {
            logger.warn { "Failed to fetch workflows from n8n: ${e.message}" }
            ResponseEntity.ok(emptyList())
        }
    }

    /**
     * Get workflow by ID
     */
    @GetMapping("/workflows/{id}")
    fun getWorkflow(@PathVariable id: String): Mono<ResponseEntity<N8nWorkflowDto?>> = mono {
        try {
            val cookie = ensureAuthenticated()
            val response = webClient.get()
                .uri("/rest/workflows/$id")
                .withAuth(cookie)
                .retrieve()
                .awaitBodyOrNull<N8nWorkflowResponse>()

            val workflow = response?.data?.toDto()
            if (workflow != null) {
                ResponseEntity.ok(workflow)
            } else {
                ResponseEntity.notFound().build()
            }
        } catch (e: Exception) {
            logger.warn { "Failed to fetch workflow $id: ${e.message}" }
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Get recent executions
     */
    @GetMapping("/executions")
    fun getExecutions(
        @RequestParam(defaultValue = "100") limit: Int
    ): Mono<ResponseEntity<List<N8nExecutionDto>>> = mono {
        try {
            val cookie = ensureAuthenticated()
            val response = webClient.get()
                .uri("/rest/executions?limit=$limit")
                .withAuth(cookie)
                .retrieve()
                .awaitBodyOrNull<N8nExecutionsResponse>()

            val executions = response?.data?.results?.map { it.toDto() } ?: emptyList()
            logger.debug { "Fetched ${executions.size} executions from n8n" }
            ResponseEntity.ok(executions)
        } catch (e: Exception) {
            logger.warn { "Failed to fetch executions from n8n: ${e.message}" }
            ResponseEntity.ok(emptyList())
        }
    }

    /**
     * Toggle workflow active state
     */
    @PatchMapping("/workflows/{id}/active")
    fun setWorkflowActive(
        @PathVariable id: String,
        @RequestBody request: SetActiveRequest
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        try {
            val cookie = ensureAuthenticated()
            webClient.patch()
                .uri("/rest/workflows/$id")
                .header("Cookie", cookie ?: "")
                .bodyValue(mapOf("active" to request.active))
                .retrieve()
                .awaitBodyOrNull<Any>()

            logger.info { "Set workflow $id active=${request.active}" }
            ResponseEntity.ok(mapOf("success" to true, "id" to id, "active" to request.active))
        } catch (e: Exception) {
            logger.warn { "Failed to update workflow $id: ${e.message}" }
            ResponseEntity.ok(mapOf("success" to false, "error" to (e.message ?: "Unknown error")))
        }
    }

    /**
     * n8n 인증 정보 가져오기 (자동 로그인용)
     */
    @GetMapping("/auth")
    fun getAuthCookie(): Mono<ResponseEntity<N8nAuthResponse>> = mono {
        try {
            val cookie = ensureAuthenticated()
            if (cookie != null) {
                ResponseEntity.ok(N8nAuthResponse(
                    authCookie = cookie,
                    n8nUrl = n8nBaseUrl,
                    success = true
                ))
            } else {
                ResponseEntity.ok(N8nAuthResponse(
                    authCookie = null,
                    n8nUrl = n8nBaseUrl,
                    success = false,
                    error = "Failed to authenticate with n8n"
                ))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get n8n auth cookie" }
            ResponseEntity.ok(N8nAuthResponse(
                authCookie = null,
                n8nUrl = n8nBaseUrl,
                success = false,
                error = e.message
            ))
        }
    }

    /**
     * n8n으로 리다이렉트
     * 사용자가 n8n에 이미 로그인되어 있으면 자동으로 접속됩니다.
     */
    @GetMapping("/login-redirect")
    fun loginRedirect(
        @RequestParam(defaultValue = "") path: String
    ): Mono<ResponseEntity<Void>> {
        val targetUrl = if (path.isNotEmpty()) "$n8nBaseUrl$path" else n8nBaseUrl

        return Mono.just(
            ResponseEntity.status(302)
                .header("Location", targetUrl)
                .build()
        )
    }

    /**
     * Execute workflow manually
     */
    @PostMapping("/workflows/{id}/run")
    fun executeWorkflow(@PathVariable id: String): Mono<ResponseEntity<Map<String, Any>>> = mono {
        try {
            val cookie = ensureAuthenticated()
            webClient.post()
                .uri("/rest/workflows/$id/run")
                .header("Cookie", cookie ?: "")
                .retrieve()
                .awaitBodyOrNull<Any>()

            logger.info { "Executed workflow $id" }
            ResponseEntity.ok(mapOf("success" to true, "id" to id))
        } catch (e: Exception) {
            logger.warn { "Failed to execute workflow $id: ${e.message}" }
            ResponseEntity.ok(mapOf("success" to false, "error" to (e.message ?: "Unknown error")))
        }
    }

    // ==================== 워크플로우 생성 기능 ====================

    private val workflowGenerator = N8nWorkflowGenerator()

    /**
     * 자연어 설명으로 워크플로우 생성
     */
    @PostMapping("/workflows/generate")
    fun generateWorkflow(
        @RequestBody request: GenerateWorkflowRequest
    ): Mono<ResponseEntity<GenerateWorkflowResponse>> = mono {
        try {
            logger.info { "Generating workflow from description: ${request.description}" }

            val workflow = workflowGenerator.generate(request.description)
            val workflowJson = mapper.writeValueAsString(workflow)

            // n8n에 직접 생성할지 여부
            if (request.createInN8n == true) {
                val cookie = ensureAuthenticated()
                val createResponse = webClient.post()
                    .uri("/rest/workflows")
                    .header("Cookie", cookie ?: "")
                    .bodyValue(workflow)
                    .retrieve()
                    .awaitBodyOrNull<Map<String, Any>>()

                val createdWorkflow = createResponse?.get("data") as? Map<*, *>
                val workflowId = createdWorkflow?.get("id")?.toString()

                if (workflowId != null) {
                    logger.info { "Created workflow in n8n: $workflowId" }
                    ResponseEntity.ok(GenerateWorkflowResponse(
                        success = true,
                        workflow = workflow,
                        workflowJson = workflowJson,
                        createdId = workflowId,
                        message = "워크플로우가 n8n에 생성되었습니다: ${workflow.name}"
                    ))
                } else {
                    ResponseEntity.ok(GenerateWorkflowResponse(
                        success = true,
                        workflow = workflow,
                        workflowJson = workflowJson,
                        message = "워크플로우 JSON이 생성되었습니다 (n8n 저장 실패)"
                    ))
                }
            } else {
                ResponseEntity.ok(GenerateWorkflowResponse(
                    success = true,
                    workflow = workflow,
                    workflowJson = workflowJson,
                    message = "워크플로우 JSON이 생성되었습니다. createInN8n=true로 설정하면 바로 n8n에 저장됩니다."
                ))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate workflow" }
            ResponseEntity.ok(GenerateWorkflowResponse(
                success = false,
                error = e.message
            ))
        }
    }

    /**
     * 템플릿 기반 워크플로우 생성
     */
    @PostMapping("/workflows/template/{templateId}")
    fun generateFromTemplate(
        @PathVariable templateId: String,
        @RequestBody(required = false) config: Map<String, Any>?
    ): Mono<ResponseEntity<GenerateWorkflowResponse>> = mono {
        try {
            logger.info { "Generating workflow from template: $templateId" }

            val workflow = workflowGenerator.generateFromTemplate(templateId, config ?: emptyMap())
            val workflowJson = mapper.writeValueAsString(workflow)

            // n8n에 생성
            val cookie = ensureAuthenticated()
            val createResponse = webClient.post()
                .uri("/rest/workflows")
                .header("Cookie", cookie ?: "")
                .bodyValue(workflow)
                .retrieve()
                .awaitBodyOrNull<Map<String, Any>>()

            val createdWorkflow = createResponse?.get("data") as? Map<*, *>
            val workflowId = createdWorkflow?.get("id")?.toString()

            ResponseEntity.ok(GenerateWorkflowResponse(
                success = true,
                workflow = workflow,
                workflowJson = workflowJson,
                createdId = workflowId,
                message = if (workflowId != null)
                    "템플릿 '$templateId' 기반 워크플로우가 생성되었습니다 (ID: $workflowId)"
                else
                    "워크플로우 JSON이 생성되었습니다"
            ))
        } catch (e: IllegalArgumentException) {
            logger.warn { "Unknown template: $templateId" }
            ResponseEntity.badRequest().body(GenerateWorkflowResponse(
                success = false,
                error = "알 수 없는 템플릿: $templateId"
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate workflow from template" }
            ResponseEntity.ok(GenerateWorkflowResponse(
                success = false,
                error = e.message
            ))
        }
    }

    /**
     * 사용 가능한 템플릿 목록
     */
    @GetMapping("/templates")
    fun listTemplates(): Mono<ResponseEntity<List<WorkflowTemplateInfo>>> = mono {
        val templates = listOf(
            WorkflowTemplateInfo(
                id = "slack-mention-handler",
                name = "Slack Mention Handler",
                description = "Slack 멘션 수신 → Claude 처리 → 응답 전송",
                trigger = "webhook",
                integrations = listOf("Slack", "Claude Flow API")
            ),
            WorkflowTemplateInfo(
                id = "gitlab-mr-review",
                name = "GitLab MR Auto Review",
                description = "GitLab MR 생성 시 자동 코드 리뷰",
                trigger = "webhook",
                integrations = listOf("GitLab", "Claude Flow API")
            ),
            WorkflowTemplateInfo(
                id = "daily-report",
                name = "Daily Report",
                description = "매일 아침 Jira/GitLab 요약 Slack 전송",
                trigger = "schedule",
                integrations = listOf("Jira", "GitLab", "Slack")
            ),
            WorkflowTemplateInfo(
                id = "jira-auto-fix",
                name = "Jira Auto Fix Scheduler",
                description = "미해결 이슈 자동 분석 및 해결 제안",
                trigger = "schedule",
                integrations = listOf("Jira", "Claude Flow API")
            ),
            WorkflowTemplateInfo(
                id = "slack-feedback-handler",
                name = "Slack Feedback Handler",
                description = "Slack 리액션 피드백 수집",
                trigger = "webhook",
                integrations = listOf("Slack", "Claude Flow API")
            ),
            WorkflowTemplateInfo(
                id = "webhook-to-slack",
                name = "Webhook to Slack",
                description = "Webhook → Slack 알림",
                trigger = "webhook",
                integrations = listOf("Slack")
            ),
            WorkflowTemplateInfo(
                id = "schedule-api-call",
                name = "Schedule API Call",
                description = "주기적 API 호출",
                trigger = "schedule",
                integrations = listOf("HTTP")
            )
        )
        ResponseEntity.ok(templates)
    }

    /**
     * 워크플로우 삭제
     */
    @DeleteMapping("/workflows/{id}")
    fun deleteWorkflow(@PathVariable id: String): Mono<ResponseEntity<Map<String, Any>>> = mono {
        try {
            val cookie = ensureAuthenticated()
            webClient.delete()
                .uri("/rest/workflows/$id")
                .header("Cookie", cookie ?: "")
                .retrieve()
                .awaitBodyOrNull<Any>()

            logger.info { "Deleted workflow $id" }
            ResponseEntity.ok(mapOf("success" to true, "id" to id))
        } catch (e: Exception) {
            logger.warn { "Failed to delete workflow $id: ${e.message}" }
            ResponseEntity.ok(mapOf("success" to false, "error" to (e.message ?: "Unknown error")))
        }
    }

    /**
     * Claude에게 보낼 프롬프트 생성 (AI 기반 고급 생성용)
     */
    @PostMapping("/workflows/prompt")
    fun generatePrompt(
        @RequestBody request: GenerateWorkflowRequest
    ): Mono<ResponseEntity<Map<String, String>>> = mono {
        val prompt = workflowGenerator.generatePromptForClaude(request.description)
        ResponseEntity.ok(mapOf("prompt" to prompt))
    }
}

// n8n API Response Types
data class N8nWorkflowsResponse(val data: List<N8nWorkflow> = emptyList())
data class N8nWorkflowResponse(val data: N8nWorkflow? = null)
data class N8nExecutionsResponse(val data: N8nExecutionsData? = null)
data class N8nExecutionsData(val results: List<N8nExecution> = emptyList(), val count: Int = 0)

data class N8nWorkflow(
    val id: String,
    val name: String,
    val active: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val nodes: List<Map<String, Any>>? = null,
    val connections: Any? = null,
    val settings: Map<String, Any>? = null,
    val tags: List<Map<String, Any>>? = null
) {
    fun toDto() = N8nWorkflowDto(
        id = id,
        name = name,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
        nodes = nodes,
        connections = connections,
        settings = settings,
        tags = tags?.map { N8nTagDto(it["id"]?.toString() ?: "", it["name"]?.toString() ?: "") }
    )
}

data class N8nExecution(
    val id: String,
    val finished: Boolean? = null,
    val mode: String? = null,
    val startedAt: String,
    val stoppedAt: String? = null,
    val workflowId: String,
    val status: String,
    val workflowName: String? = null
) {
    fun toDto() = N8nExecutionDto(
        id = id,
        finished = finished ?: (status == "success"),
        mode = mode ?: "unknown",
        startedAt = startedAt,
        stoppedAt = stoppedAt,
        workflowId = workflowId,
        status = status,
        workflowName = workflowName
    )
}

// DTOs for frontend
data class N8nWorkflowDto(
    val id: String,
    val name: String,
    val active: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val nodes: List<Map<String, Any>>?,
    val connections: Any?,
    val settings: Map<String, Any>?,
    val tags: List<N8nTagDto>?
)

data class N8nTagDto(val id: String, val name: String)

data class N8nExecutionDto(
    val id: String,
    val finished: Boolean,
    val mode: String,
    val startedAt: String,
    val stoppedAt: String?,
    val workflowId: String,
    val status: String,
    val workflowName: String?
)

data class SetActiveRequest(val active: Boolean)

data class N8nAuthResponse(
    val authCookie: String?,
    val n8nUrl: String,
    val success: Boolean,
    val error: String? = null
)

// 워크플로우 생성 관련 DTOs
data class GenerateWorkflowRequest(
    val description: String,
    val createInN8n: Boolean? = false
)

data class GenerateWorkflowResponse(
    val success: Boolean,
    val workflow: ai.claudeflow.core.n8n.N8nWorkflow? = null,
    val workflowJson: String? = null,
    val createdId: String? = null,
    val message: String? = null,
    val error: String? = null
)

data class WorkflowTemplateInfo(
    val id: String,
    val name: String,
    val description: String,
    val trigger: String,
    val integrations: List<String>
)
