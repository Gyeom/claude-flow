package ai.claudeflow.api.rest

import ai.claudeflow.api.command.CommandHandler
import ai.claudeflow.api.service.ProjectContextService
import ai.claudeflow.api.slack.SlackMessageSender
import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.model.Project
import ai.claudeflow.core.ratelimit.RateLimiter
import ai.claudeflow.core.registry.ProjectRegistry
import ai.claudeflow.core.routing.AgentRouter
import ai.claudeflow.core.routing.AgentUpdate
import ai.claudeflow.core.storage.ExecutionRecord
import ai.claudeflow.core.storage.FeedbackRecord
import ai.claudeflow.core.storage.Storage
import ai.claudeflow.core.rag.ContextAugmentationService
import ai.claudeflow.core.rag.AugmentationOptions
import ai.claudeflow.core.rag.ConversationVectorService
import ai.claudeflow.executor.ClaudeExecutor
import ai.claudeflow.executor.ExecutionRequest
import ai.claudeflow.executor.ExecutionResult
import ai.claudeflow.executor.ExecutionStatus
import kotlinx.coroutines.reactor.mono
import reactor.core.scheduler.Schedulers
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import java.util.UUID

private val logger = KotlinLogging.logger {}

/** Default max turns for Claude CLI execution */
private const val DEFAULT_MAX_TURNS = 50

/**
 * claude-flow REST API
 *
 * n8n 워크플로우에서 호출하는 API 엔드포인트
 */
@RestController
@RequestMapping("/api/v1")
class ClaudeFlowController(
    private val claudeExecutor: ClaudeExecutor,
    private val slackMessageSender: SlackMessageSender,
    private val projectRegistry: ProjectRegistry,
    private val projectContextService: ProjectContextService,
    private val storage: Storage? = null,
    private val rateLimiter: RateLimiter? = null,
    private val contextAugmentationService: ContextAugmentationService? = null,
    private val conversationVectorService: ConversationVectorService? = null
) {
    private val agentRouter = AgentRouter()
    private val commandHandler = CommandHandler(projectRegistry)
    /**
     * Claude 실행 API
     *
     * n8n에서 호출하여 Claude Code 실행
     */
    @PostMapping("/execute")
    fun execute(@RequestBody request: ExecuteRequest): Mono<ResponseEntity<ExecuteResponse>> {
        logger.info { "Execute request: prompt=${request.prompt.take(50)}..." }

        return Mono.fromCallable {
            // 대화 히스토리가 있으면 프롬프트에 컨텍스트 추가
            val contextualPrompt = buildContextualPrompt(request.prompt, request.conversationHistory)

            val executionRequest = ExecutionRequest(
                prompt = contextualPrompt,
                systemPrompt = request.systemPrompt,
                workingDirectory = request.workingDirectory,
                model = request.model,
                maxTurns = request.maxTurns ?: DEFAULT_MAX_TURNS,
                allowedTools = request.allowedTools,
                deniedTools = request.deniedTools
            )

            val result = claudeExecutor.execute(executionRequest)

            val response = ExecuteResponse(
                requestId = result.requestId,
                success = result.status == ExecutionStatus.SUCCESS,
                result = result.result,
                error = result.error,
                durationMs = result.durationMs,
                usage = result.usage?.let {
                    TokenUsageDto(
                        inputTokens = it.inputTokens,
                        outputTokens = it.outputTokens
                    )
                }
            )

            if (result.status == ExecutionStatus.SUCCESS) {
                ResponseEntity.ok(response)
            } else {
                ResponseEntity.status(500).body(response)
            }
        }.subscribeOn(Schedulers.boundedElastic())
    }

    /**
     * Slack 메시지 전송 API
     */
    @PostMapping("/slack/message")
    fun sendSlackMessage(@RequestBody request: SlackMessageRequest): Mono<ResponseEntity<SlackMessageResponse>> = mono {
        logger.info { "Sending Slack message to ${request.channel}" }

        val result = slackMessageSender.sendMessage(
            channel = request.channel,
            text = request.text,
            threadTs = request.threadTs,
            blocks = request.blocks
        )

        result.fold(
            onSuccess = { ts ->
                ResponseEntity.ok(SlackMessageResponse(success = true, timestamp = ts))
            },
            onFailure = { e ->
                ResponseEntity.status(500).body(
                    SlackMessageResponse(success = false, error = e.message)
                )
            }
        )
    }

    /**
     * Slack 리액션 추가 API
     */
    @PostMapping("/slack/reaction")
    fun addSlackReaction(@RequestBody request: SlackReactionRequest): Mono<ResponseEntity<Map<String, Boolean>>> = mono {
        val success = if (request.remove) {
            slackMessageSender.removeReaction(request.channel, request.timestamp, request.emoji)
        } else {
            slackMessageSender.addReaction(request.channel, request.timestamp, request.emoji)
        }

        ResponseEntity.ok(mapOf("success" to success))
    }

    /**
     * 스레드 히스토리 조회 API
     */
    @PostMapping("/slack/thread-history")
    fun getThreadHistory(@RequestBody request: ThreadHistoryRequest): Mono<ResponseEntity<ThreadHistoryResponse>> = mono {
        logger.info { "Getting thread history for ${request.channel}:${request.threadTs}" }

        val result = slackMessageSender.getThreadHistory(
            channel = request.channel,
            threadTs = request.threadTs,
            limit = request.limit
        )

        result.fold(
            onSuccess = { messages ->
                val dtos = messages.map { msg ->
                    ThreadMessageDto(
                        user = msg.user,
                        userName = msg.userName,
                        text = msg.text,
                        timestamp = msg.timestamp,
                        isBot = msg.isBot
                    )
                }
                ResponseEntity.ok(ThreadHistoryResponse(success = true, messages = dtos))
            },
            onFailure = { e ->
                ResponseEntity.status(500).body(
                    ThreadHistoryResponse(success = false, error = e.message)
                )
            }
        )
    }

    /**
     * Health check
     */
    @GetMapping("/health")
    fun health(): Mono<ResponseEntity<Map<String, String>>> = mono {
        ResponseEntity.ok(mapOf("status" to "ok"))
    }

    // ==================== 명령어 처리 API ====================

    /**
     * 명령어 처리 API
     * /project, /help 등의 명령어 처리
     */
    @PostMapping("/command")
    fun executeCommand(@RequestBody request: CommandRequest): Mono<ResponseEntity<CommandResponse>> = mono {
        logger.info { "Command request: ${request.text}" }

        val result = commandHandler.execute(request.text, request.channel)

        ResponseEntity.ok(CommandResponse(
            isCommand = result.isCommand,
            response = result.response
        ))
    }

    /**
     * 에이전트 라우팅 API
     * 메시지를 분석하여 적절한 에이전트 선택
     */
    @PostMapping("/route")
    fun route(@RequestBody request: RouteRequest): Mono<ResponseEntity<RouteResponse>> = mono {
        logger.info { "Routing request: ${request.message.take(50)}..." }

        val match = agentRouter.route(request.message)

        ResponseEntity.ok(RouteResponse(
            agentId = match.agent.id,
            agentName = match.agent.name,
            confidence = match.confidence,
            matchedKeyword = match.matchedKeyword,
            systemPrompt = match.agent.systemPrompt,
            model = match.agent.model,
            allowedTools = match.agent.allowedTools
        ))
    }

    /**
     * 에이전트 목록 조회 API
     */
    @GetMapping("/agents")
    fun listAgents(@RequestParam(defaultValue = "false") includeDisabled: Boolean): Mono<ResponseEntity<List<AgentDetailDto>>> = mono {
        val agents = if (includeDisabled) {
            agentRouter.listAllAgents()
        } else {
            agentRouter.listAgents()
        }

        ResponseEntity.ok(agents.map { it.toDetailDto() })
    }

    /**
     * 특정 에이전트 조회
     */
    @GetMapping("/agents/{agentId}")
    fun getAgentById(@PathVariable agentId: String): Mono<ResponseEntity<AgentDetailDto>> = mono {
        val agent = agentRouter.listAllAgents().find { it.id == agentId }
        if (agent != null) {
            ResponseEntity.ok(agent.toDetailDto())
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * 에이전트 생성
     */
    @PostMapping("/agents")
    fun createAgent(@RequestBody request: CreateAgentRequest): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val agent = Agent(
            id = request.id,
            name = request.name,
            description = request.description,
            keywords = request.keywords,
            systemPrompt = request.systemPrompt,
            model = request.model ?: "sonnet",
            allowedTools = request.allowedTools ?: emptyList(),
            workingDirectory = request.workingDirectory,
            enabled = request.enabled ?: true
        )

        val success = agentRouter.addAgent(agent)
        if (success) {
            logger.info { "Created agent: ${agent.id}" }
            ResponseEntity.ok(mapOf("success" to true, "agent" to agent.toDetailDto()))
        } else {
            ResponseEntity.badRequest().body(mapOf("success" to false, "error" to "Agent already exists: ${request.id}"))
        }
    }

    /**
     * 에이전트 업데이트
     */
    @PutMapping("/agents/{agentId}")
    fun updateAgentById(@PathVariable agentId: String, @RequestBody request: UpdateAgentRequest): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val update = AgentUpdate(
            name = request.name,
            description = request.description,
            keywords = request.keywords,
            systemPrompt = request.systemPrompt,
            model = request.model,
            allowedTools = request.allowedTools,
            workingDirectory = request.workingDirectory,
            enabled = request.enabled
        )

        val success = agentRouter.updateAgent(agentId, update)
        if (success) {
            val agent = agentRouter.listAllAgents().find { it.id == agentId }
            ResponseEntity.ok(mapOf("success" to true, "agent" to (agent?.toDetailDto() ?: emptyMap<String, Any>())))
        } else {
            ResponseEntity.notFound().build<Map<String, Any>>()
        }
    }

    /**
     * 에이전트 삭제
     */
    @DeleteMapping("/agents/{agentId}")
    fun deleteAgent(@PathVariable agentId: String): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val success = agentRouter.removeAgent(agentId)
        if (success) {
            ResponseEntity.ok(mapOf("success" to true))
        } else {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "error" to "Cannot delete agent (built-in or not found): $agentId"
            ))
        }
    }

    /**
     * 에이전트 활성화/비활성화
     */
    @PatchMapping("/agents/{agentId}/enabled")
    fun setAgentEnabled(@PathVariable agentId: String, @RequestBody request: SetAgentEnabledRequest): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val success = agentRouter.setAgentEnabled(agentId, request.enabled)
        if (success) {
            ResponseEntity.ok(mapOf("success" to true, "agentId" to agentId, "enabled" to request.enabled))
        } else {
            ResponseEntity.notFound().build<Map<String, Any>>()
        }
    }

    // Agent -> DTO 변환 확장 함수
    private fun Agent.toDetailDto() = AgentDetailDto(
        id = id,
        name = name,
        description = description,
        keywords = keywords,
        systemPrompt = systemPrompt,
        model = model,
        allowedTools = allowedTools,
        workingDirectory = workingDirectory,
        enabled = enabled
    )

    // ==================== 프로젝트 관리 API (레거시 - ProjectsController로 마이그레이션됨) ====================
    // NOTE: 아래 API들은 하위 호환성을 위해 유지됩니다.
    // 새로운 코드에서는 /api/v1/projects/* 엔드포인트를 사용하세요.

    /**
     * 채널에 프로젝트 설정
     * @deprecated Use POST /api/v1/projects/{projectId}/channels instead
     */
    @Deprecated("Use POST /api/v1/projects/{projectId}/channels instead")
    @PostMapping("/projects/channel")
    fun setChannelProject(@RequestBody request: SetChannelProjectRequest): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val success = projectRegistry.setChannelProject(request.channel, request.projectId)
        if (success) {
            ResponseEntity.ok(mapOf(
                "success" to true,
                "channel" to request.channel,
                "projectId" to request.projectId
            ))
        } else {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "error" to "Project not found: ${request.projectId}"
            ))
        }
    }

    /**
     * 채널의 프로젝트 조회
     * @deprecated Use GET /api/v1/projects/{projectId}/channels instead
     */
    @Deprecated("Use GET /api/v1/projects/{projectId}/channels instead")
    @GetMapping("/projects/channel/{channel}")
    fun getChannelProject(@PathVariable channel: String): Mono<ResponseEntity<Map<String, Any?>>> = mono {
        val project = projectRegistry.getChannelProject(channel)
        if (project != null) {
            ResponseEntity.ok(mapOf(
                "projectId" to project.id,
                "projectName" to project.name,
                "workingDirectory" to project.workingDirectory
            ))
        } else {
            ResponseEntity.ok(emptyMap())
        }
    }

    /**
     * 채널-프로젝트 매핑 목록
     * @deprecated Use GET /api/v1/projects to get all projects with their channels
     */
    @Deprecated("Use GET /api/v1/projects to get all projects with their channels")
    @GetMapping("/projects/channels")
    fun getChannelMappings(): Mono<ResponseEntity<Map<String, String>>> = mono {
        ResponseEntity.ok(projectRegistry.getChannelMappings())
    }

    /**
     * 기본 프로젝트 설정
     * @deprecated Use POST /api/v1/projects/{projectId}/default instead
     */
    @Deprecated("Use POST /api/v1/projects/{projectId}/default instead")
    @PostMapping("/projects/default")
    fun setDefaultProject(@RequestBody request: SetDefaultProjectRequest): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val success = projectRegistry.setDefaultProject(request.projectId)
        if (success) {
            ResponseEntity.ok(mapOf("success" to true, "defaultProject" to request.projectId))
        } else {
            ResponseEntity.badRequest().body(mapOf("success" to false, "error" to "Project not found"))
        }
    }

    /**
     * 라우팅 + 실행 통합 API
     * 메시지를 분석하여 적절한 에이전트로 라우팅 후 실행
     * 채널에 설정된 프로젝트가 있으면 해당 프로젝트 경로에서 실행
     */
    @PostMapping("/execute-with-routing")
    fun executeWithRouting(@RequestBody request: ExecuteRequest): Mono<ResponseEntity<ExecuteResponse>> {
        logger.info { "Execute with routing: ${request.prompt.take(50)}..." }

        return Mono.fromCallable {
            // 0. Rate Limiting 체크
            val projectId = request.channel?.let { projectRegistry.getChannelProject(it)?.id } ?: "default"
            rateLimiter?.let { limiter ->
                val limitResult = limiter.checkLimit(projectId)
                if (!limitResult.allowed) {
                    logger.warn { "Rate limit exceeded for project: $projectId" }
                    return@fromCallable ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                        ExecuteResponse(
                            requestId = UUID.randomUUID().toString(),
                            success = false,
                            error = "Rate limit exceeded. Retry after ${limitResult.retryAfterSeconds} seconds."
                        )
                    )
                }
            }

            // 1. 라우팅 (시간 측정)
            val routingStartTime = System.currentTimeMillis()
            val match = agentRouter.route(request.prompt)
            val routingLatencyMs = System.currentTimeMillis() - routingStartTime
            logger.info { "Routed to agent: ${match.agent.id} (confidence: ${match.confidence}, method: ${match.method}, latency: ${routingLatencyMs}ms)" }

            // 2. 채널에 설정된 프로젝트 조회 (채널 정보가 있으면)
            val project = request.channel?.let { projectRegistry.getChannelProject(it) }
            if (project != null) {
                logger.info { "Using project: ${project.id} (${project.workingDirectory})" }
            }

            // 3. 프로젝트 컨텍스트 주입
            val enrichedResult = projectContextService.enrichPromptWithProjectContext(request.prompt)
            if (enrichedResult.contextInjected) {
                logger.info { "Project context injected: ${enrichedResult.detectedProjects.map { it.projectId }}" }
            }

            // 4. RAG 컨텍스트 증강 (userId가 있으면)
            var ragSystemPrompt: String? = null
            if (request.userId != null && contextAugmentationService != null) {
                try {
                    val augmented = contextAugmentationService.buildAugmentedContext(
                        userId = request.userId,
                        message = request.prompt,
                        options = AugmentationOptions(
                            includeSimilarConversations = true,
                            includeUserRules = true,
                            includeUserSummary = true,
                            maxSimilarConversations = 3,
                            minSimilarityScore = 0.65f
                        )
                    )
                    if (augmented.systemPrompt.isNotBlank()) {
                        ragSystemPrompt = augmented.systemPrompt
                        logger.info { "RAG context augmented: ${augmented.relevantConversations.size} similar conversations, ${augmented.userRules.size} rules (${augmented.metadata.totalTimeMs}ms)" }
                    }
                } catch (e: Exception) {
                    logger.warn { "RAG augmentation failed (continuing without): ${e.message}" }
                }
            }

            // 5. 대화 히스토리 포함 프롬프트 생성
            val contextualPrompt = buildContextualPrompt(enrichedResult.enrichedPrompt, request.conversationHistory)

            // 6. 작업 디렉토리 결정 (우선순위: 요청 > 탐지된 프로젝트 > 채널 프로젝트 > 에이전트)
            val detectedProjectPath = enrichedResult.detectedProjects.firstOrNull()?.path
            val workingDir = request.workingDirectory
                ?: detectedProjectPath
                ?: project?.workingDirectory
                ?: match.agent.workingDirectory

            // 7. 시스템 프롬프트 구성 (우선순위: 요청 > RAG증강 + 에이전트)
            val finalSystemPrompt = request.systemPrompt ?: buildString {
                // RAG 컨텍스트가 있으면 먼저 추가
                ragSystemPrompt?.let { ragPrompt ->
                    append(ragPrompt)
                    append("\n\n")
                }
                // 에이전트 시스템 프롬프트 추가
                if (match.agent.systemPrompt.isNotBlank()) {
                    append(match.agent.systemPrompt)
                }
            }.takeIf { it.isNotBlank() }

            // 8. 라우팅된 에이전트의 설정으로 실행
            val executionRequest = ExecutionRequest(
                prompt = contextualPrompt,
                systemPrompt = finalSystemPrompt,
                workingDirectory = workingDir,
                model = request.model ?: match.agent.model,
                maxTurns = request.maxTurns ?: DEFAULT_MAX_TURNS,
                allowedTools = request.allowedTools ?: match.agent.allowedTools.takeIf { it.isNotEmpty() },
                deniedTools = request.deniedTools,
                agentId = match.agent.id
            )

            val result = claudeExecutor.execute(executionRequest)

            // 9. 실행 결과 저장
            val executionRecord = ExecutionRecord(
                id = result.requestId,
                prompt = request.prompt.take(1000),
                result = result.result?.take(5000),
                status = result.status.name,
                agentId = match.agent.id,
                projectId = project?.id,
                userId = request.userId,
                channel = request.channel,
                threadTs = request.threadTs,
                replyTs = null,
                durationMs = result.durationMs,
                inputTokens = result.usage?.inputTokens ?: 0,
                outputTokens = result.usage?.outputTokens ?: 0,
                error = result.error
            )

            storage?.let { store ->
                try {
                    store.saveExecution(executionRecord)
                    logger.debug { "Saved execution record: ${result.requestId}" }

                    // 10. RAG 자동 인덱싱 (성공한 실행만)
                    if (result.status == ExecutionStatus.SUCCESS && conversationVectorService != null) {
                        try {
                            val indexed = conversationVectorService.indexExecution(executionRecord)
                            if (indexed) {
                                logger.debug { "RAG indexed execution: ${result.requestId}" }
                            }
                        } catch (e: Exception) {
                            logger.warn { "RAG indexing failed (non-critical): ${e.message}" }
                        }
                    }

                    // 11. 라우팅 메트릭 저장
                    store.saveRoutingMetric(
                        executionId = result.requestId,
                        routingMethod = match.method.name.lowercase(),
                        agentId = match.agent.id,
                        confidence = match.confidence,
                        latencyMs = routingLatencyMs
                    )
                    logger.debug { "Saved routing metric: method=${match.method.name}, latency=${routingLatencyMs}ms" }

                    // 12. 사용자 컨텍스트 업데이트 (User Management용)
                    request.userId?.let { userId ->
                        store.updateUserInteraction(
                            userId = userId,
                            promptLength = request.prompt.length,
                            responseLength = result.result?.length ?: 0
                        )
                        logger.debug { "Updated user context for: $userId" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to save execution record" }
                }
            }

            val response = ExecuteResponse(
                requestId = result.requestId,
                success = result.status == ExecutionStatus.SUCCESS,
                result = result.result,
                error = result.error,
                durationMs = result.durationMs,
                usage = result.usage?.let {
                    TokenUsageDto(
                        inputTokens = it.inputTokens,
                        outputTokens = it.outputTokens
                    )
                },
                routedAgent = match.agent.id,
                routingConfidence = match.confidence,
                projectId = enrichedResult.detectedProjects.firstOrNull()?.projectId ?: project?.id
            )

            if (result.status == ExecutionStatus.SUCCESS) {
                ResponseEntity.ok(response)
            } else {
                ResponseEntity.status(500).body(response)
            }
        }.subscribeOn(Schedulers.boundedElastic())
    }

    /**
     * 대화 히스토리를 포함한 프롬프트 생성
     */
    private fun buildContextualPrompt(
        currentPrompt: String,
        history: List<ConversationMessage>?
    ): String {
        if (history.isNullOrEmpty()) {
            return currentPrompt
        }

        val historyText = history.joinToString("\n\n") { msg ->
            val roleLabel = when (msg.role) {
                "user" -> msg.userName?.let { "사용자($it)" } ?: "사용자"
                "assistant" -> "어시스턴트"
                else -> msg.role
            }
            "$roleLabel: ${msg.content}"
        }

        return """
            |[이전 대화 내용]
            |$historyText
            |
            |[현재 질문]
            |$currentPrompt
        """.trimMargin()
    }

    // ==================== 피드백 API ====================

    /**
     * 피드백 저장/삭제 API
     */
    @PostMapping("/feedback")
    fun saveFeedback(@RequestBody request: FeedbackRequest): Mono<ResponseEntity<FeedbackResponse>> = mono {
        if (storage == null) {
            return@mono ResponseEntity.ok(FeedbackResponse(success = false, error = "Storage not configured"))
        }

        try {
            if (request.action == "delete") {
                storage.deleteFeedback(request.executionId, request.userId, request.reaction)
            } else {
                storage.saveFeedback(FeedbackRecord(
                    id = UUID.randomUUID().toString(),
                    executionId = request.executionId,
                    userId = request.userId,
                    reaction = request.reaction
                ))
            }
            logger.info { "Feedback ${request.action}: ${request.executionId} - ${request.reaction}" }
            ResponseEntity.ok(FeedbackResponse(success = true))
        } catch (e: Exception) {
            logger.error(e) { "Failed to save feedback" }
            ResponseEntity.ok(FeedbackResponse(success = false, error = e.message))
        }
    }

    // ==================== 실행 이력 API ====================

    /**
     * 실행에 reply_ts 연결 (피드백 추적용)
     */
    @PatchMapping("/executions/{id}/reply-ts")
    fun updateExecutionReplyTs(
        @PathVariable id: String,
        @RequestBody request: UpdateReplyTsRequest
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        if (storage == null) {
            return@mono ResponseEntity.ok(mapOf("success" to false, "error" to "Storage not configured"))
        }

        try {
            storage.updateExecutionReplyTs(id, request.replyTs)
            logger.info { "Updated reply_ts for execution $id: ${request.replyTs}" }
            ResponseEntity.ok(mapOf("success" to true, "executionId" to id, "replyTs" to request.replyTs))
        } catch (e: Exception) {
            logger.error(e) { "Failed to update reply_ts for execution $id" }
            ResponseEntity.ok(mapOf("success" to false, "error" to (e.message ?: "Unknown error")))
        }
    }

    /**
     * 실행 이력 조회 (reply_ts로 조회)
     */
    @GetMapping("/executions/by-reply-ts")
    fun getExecutionByReplyTs(@RequestParam replyTs: String): Mono<ResponseEntity<ExecutionDto?>> = mono {
        val record = storage?.findExecutionByReplyTs(replyTs)
        if (record != null) {
            ResponseEntity.ok(ExecutionDto(
                executionId = record.id,
                prompt = record.prompt,
                result = record.result,
                status = record.status,
                agentId = record.agentId,
                durationMs = record.durationMs,
                createdAt = record.createdAt.toString()
            ))
        } else {
            ResponseEntity.ok(null)
        }
    }

    /**
     * 최근 실행 이력 조회
     */
    @GetMapping("/executions/recent")
    fun getRecentExecutions(@RequestParam(defaultValue = "50") limit: Int): Mono<ResponseEntity<List<ExecutionDto>>> = mono {
        val records = storage?.getRecentExecutions(limit) ?: emptyList()
        ResponseEntity.ok(records.map { record ->
            ExecutionDto(
                executionId = record.id,
                prompt = record.prompt,
                result = record.result,
                status = record.status,
                agentId = record.agentId,
                durationMs = record.durationMs,
                createdAt = record.createdAt.toString(),
                inputTokens = record.inputTokens,
                outputTokens = record.outputTokens
            )
        })
    }

    // ==================== 통계 API ====================

    /**
     * 전체 통계 조회
     */
    @GetMapping("/stats")
    fun getStats(): Mono<ResponseEntity<StatsResponse>> = mono {
        val stats = storage?.getStats()
        if (stats != null) {
            ResponseEntity.ok(StatsResponse(
                totalExecutions = stats.totalExecutions,
                successRate = stats.successRate,
                totalTokens = stats.totalTokens,
                avgDurationMs = stats.avgDurationMs,
                thumbsUp = stats.thumbsUp,
                thumbsDown = stats.thumbsDown
            ))
        } else {
            ResponseEntity.ok(StatsResponse(0, 0.0, 0, 0.0, 0, 0))
        }
    }

    // ==================== Rate Limiter 관리 API ====================

    /**
     * Rate Limiter 상태 조회
     */
    @GetMapping("/rate-limit/status")
    fun getRateLimitStatus(): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val status = rateLimiter?.getStatus() ?: emptyMap()
        ResponseEntity.ok(mapOf("limiters" to status))
    }

    /**
     * 프로젝트별 Rate Limit 설정
     */
    @PostMapping("/rate-limit/set")
    fun setRateLimit(@RequestBody request: SetRateLimitRequest): Mono<ResponseEntity<Map<String, Any>>> = mono {
        rateLimiter?.setLimit(request.projectId, request.rpm)
        ResponseEntity.ok(mapOf("success" to true, "projectId" to request.projectId, "rpm" to request.rpm))
    }
}

// Request/Response DTOs
data class ExecuteRequest(
    val prompt: String,
    val systemPrompt: String? = null,
    val workingDirectory: String? = null,
    val model: String? = null,
    val maxTurns: Int? = null,
    val allowedTools: List<String>? = null,
    val deniedTools: List<String>? = null,
    val conversationHistory: List<ConversationMessage>? = null,
    val channel: String? = null,
    val userId: String? = null,
    val threadTs: String? = null
)

data class ConversationMessage(
    val role: String,  // "user" or "assistant"
    val content: String,
    val userName: String? = null
)

data class ExecuteResponse(
    val requestId: String,
    val success: Boolean,
    val result: String? = null,
    val error: String? = null,
    val durationMs: Long = 0,
    val usage: TokenUsageDto? = null,
    val routedAgent: String? = null,
    val routingConfidence: Double? = null,
    val projectId: String? = null
)

// Routing DTOs
data class RouteRequest(
    val message: String
)

data class RouteResponse(
    val agentId: String,
    val agentName: String,
    val confidence: Double,
    val matchedKeyword: String?,
    val systemPrompt: String,
    val model: String,
    val allowedTools: List<String>
)

data class AgentDto(
    val id: String,
    val name: String,
    val description: String,
    val keywords: List<String>
)

data class AgentDetailDto(
    val id: String,
    val name: String,
    val description: String,
    val keywords: List<String>,
    val systemPrompt: String,
    val model: String,
    val allowedTools: List<String>,
    val workingDirectory: String?,
    val enabled: Boolean
)

data class CreateAgentRequest(
    val id: String,
    val name: String,
    val description: String,
    val keywords: List<String>,
    val systemPrompt: String,
    val model: String? = "sonnet",
    val allowedTools: List<String>? = emptyList(),
    val workingDirectory: String? = null,
    val enabled: Boolean? = true
)

data class UpdateAgentRequest(
    val name: String? = null,
    val description: String? = null,
    val keywords: List<String>? = null,
    val systemPrompt: String? = null,
    val model: String? = null,
    val allowedTools: List<String>? = null,
    val workingDirectory: String? = null,
    val enabled: Boolean? = null
)

data class SetAgentEnabledRequest(
    val enabled: Boolean
)

// Project DTOs (Legacy)
data class SetChannelProjectRequest(
    val channel: String,
    val projectId: String
)

data class SetDefaultProjectRequest(
    val projectId: String
)

// Command DTOs
data class CommandRequest(
    val text: String,
    val channel: String
)

data class CommandResponse(
    val isCommand: Boolean,
    val response: String?
)

data class TokenUsageDto(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0
)

data class SlackMessageRequest(
    val channel: String,
    val text: String,
    val threadTs: String? = null,
    val blocks: String? = null
)

data class SlackMessageResponse(
    val success: Boolean,
    val timestamp: String? = null,
    val error: String? = null
)

data class SlackReactionRequest(
    val channel: String,
    val timestamp: String,
    val emoji: String,
    val remove: Boolean = false
)

data class ThreadHistoryRequest(
    val channel: String,
    val threadTs: String,
    val limit: Int = 20
)

data class ThreadMessageDto(
    val user: String,
    val userName: String?,
    val text: String,
    val timestamp: String,
    val isBot: Boolean
)

data class ThreadHistoryResponse(
    val success: Boolean,
    val messages: List<ThreadMessageDto> = emptyList(),
    val error: String? = null
)

// Feedback DTOs
data class FeedbackRequest(
    val executionId: String,
    val userId: String,
    val reaction: String,
    val action: String = "upsert"  // upsert or delete
)

data class FeedbackResponse(
    val success: Boolean,
    val error: String? = null
)

data class UpdateReplyTsRequest(
    val replyTs: String
)

// Stats DTOs
data class StatsResponse(
    val totalExecutions: Int,
    val successRate: Double,
    val totalTokens: Long,
    val avgDurationMs: Double,
    val thumbsUp: Int,
    val thumbsDown: Int
)

// Execution DTOs
data class ExecutionDto(
    val executionId: String,
    val prompt: String,
    val result: String?,
    val status: String,
    val agentId: String,
    val durationMs: Long,
    val createdAt: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0
)

// Rate Limit DTOs
data class SetRateLimitRequest(
    val projectId: String,
    val rpm: Int
)
