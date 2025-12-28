package ai.claudeflow.api.rest

import ai.claudeflow.api.dto.*
import ai.claudeflow.core.enrichment.ContextEnrichmentPipeline
import ai.claudeflow.core.enrichment.EnrichmentContext
import ai.claudeflow.core.model.AgentMatch
import ai.claudeflow.core.model.Project
import ai.claudeflow.core.model.RoutingMethod
import ai.claudeflow.core.plugin.GitLabPlugin
import ai.claudeflow.core.plugin.PluginRegistry
import ai.claudeflow.core.rag.ConversationVectorService
import ai.claudeflow.core.ratelimit.RateLimiter
import ai.claudeflow.core.registry.ProjectRegistry
import ai.claudeflow.core.routing.AgentRouter
import ai.claudeflow.core.storage.Storage
import ai.claudeflow.core.storage.ExecutionRecord
import ai.claudeflow.executor.*
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.CoroutineScope
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * MR 리뷰 요청 패턴
 */
private val MR_REVIEW_PATTERNS = listOf(
    Regex("""!(\d+)"""),                    // !123
    Regex("""MR\s*(\d+)""", RegexOption.IGNORE_CASE),  // MR 123, MR123
    Regex("""merge\s*request""", RegexOption.IGNORE_CASE),  // merge request
    Regex("""리뷰.*해"""),                   // 리뷰해줘
    Regex("""봐.*줘.*MR""", RegexOption.IGNORE_CASE),  // MR 봐줘
    Regex("""MR.*봐""", RegexOption.IGNORE_CASE)       // MR 좀 봐
)

/**
 * 채팅 스트리밍 API
 *
 * SSE 스트리밍을 통한 실시간 채팅 지원
 * ContextEnrichmentPipeline을 통해 컨텍스트를 주입합니다.
 */
@RestController
@RequestMapping("/api/v1/chat")
class ChatStreamController(
    private val claudeExecutor: ClaudeExecutor,
    private val projectRegistry: ProjectRegistry,
    private val enrichmentPipeline: ContextEnrichmentPipeline,  // Pipeline 사용
    private val pluginRegistry: PluginRegistry,  // MR 분석용
    private val storage: Storage? = null,
    private val rateLimiter: RateLimiter? = null,
    private val conversationVectorService: ConversationVectorService? = null  // RAG 인덱싱용
) {
    private val agentRouter = AgentRouter()
    private val objectMapper = ObjectMapper()

    /**
     * 채팅 스트리밍 API (SSE)
     *
     * POST /api/v1/chat/stream
     */
    @PostMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamChat(@RequestBody request: ChatRequest): Flux<ServerSentEvent<String>> {
        logger.info { "Chat stream request: messages=${request.messages.size}, projectId=${request.projectId}, agentId=${request.agentId}" }

        // 즉시 연결 확인 하트비트
        val initialHeartbeat = Flux.just(
            ServerSentEvent.builder<String>()
                .event("heartbeat")
                .comment("connected")
                .build()
        )

        // 주기적 하트비트 (30초)
        val periodicHeartbeat = Flux.interval(Duration.ofSeconds(30))
            .map {
                ServerSentEvent.builder<String>()
                    .event("heartbeat")
                    .comment("keepalive")
                    .build()
            }

        // 메인 스트리밍 응답
        val chatStream = Flux.create<ServerSentEvent<String>> { sink ->
            mono {
                try {
                    // 📊 Rate limit 체크
                    val projectId = request.projectId ?: "default"
                    sink.next(buildProgressEvent(
                        ProgressSteps.RATE_LIMIT_CHECK,
                        "요청 제한 확인 중...",
                        mapOf("projectId" to projectId)
                    ))

                    val rateLimitResult = rateLimiter?.checkLimit(projectId)
                    if (rateLimitResult != null && !rateLimitResult.allowed) {
                        sink.next(buildErrorEvent("Rate limit exceeded for project: $projectId"))
                        sink.complete()
                        return@mono
                    }

                    // 마지막 사용자 메시지 추출
                    val lastUserMessage = request.messages.lastOrNull { it.role == "user" }?.content
                        ?: run {
                            sink.next(buildErrorEvent("No user message found"))
                            sink.complete()
                            return@mono
                        }

                    // 🔀 에이전트 라우팅 (세션 컨텍스트 우선)
                    sink.next(buildProgressEvent(
                        ProgressSteps.AGENT_ROUTING,
                        "최적의 에이전트 선택 중..."
                    ))

                    val routingStartTime = System.currentTimeMillis()
                    val sessionContext = request.sessionContext
                    val agentMatch: AgentMatch = when {
                        // 1. 명시적 agentId 지정
                        request.agentId != null -> {
                            val agent = agentRouter.getAgent(request.agentId)
                            if (agent != null) {
                                AgentMatch(agent = agent, confidence = 1.0, method = RoutingMethod.DEFAULT)
                            } else {
                                sink.next(buildErrorEvent("Agent not found: ${request.agentId}"))
                                sink.complete()
                                return@mono
                            }
                        }
                        // 2. 세션 컨텍스트가 있고 후속 질문인 경우 → 이전 에이전트 유지
                        sessionContext?.lastAgentId != null && isFollowUpQuestion(lastUserMessage, sessionContext) -> {
                            val agent = agentRouter.getAgent(sessionContext.lastAgentId)
                            if (agent != null) {
                                logger.info { "Session context: keeping agent ${agent.id} for follow-up question" }
                                AgentMatch(
                                    agent = agent,
                                    confidence = 0.95,
                                    method = RoutingMethod.CACHE,
                                    reasoning = "Session context follow-up"
                                )
                            } else {
                                agentRouter.route(lastUserMessage)
                            }
                        }
                        // 3. 일반 라우팅
                        else -> agentRouter.route(lastUserMessage)
                    }

                    val routingLatencyMs = System.currentTimeMillis() - routingStartTime

                    // 메타데이터 이벤트 전송 (에이전트 선택 결과)
                    sink.next(buildProgressEvent(
                        ProgressSteps.AGENT_ROUTING,
                        "${agentMatch.agent.name} 에이전트 선택됨",
                        mapOf(
                            "agentId" to agentMatch.agent.id,
                            "confidence" to String.format("%.0f%%", agentMatch.confidence * 100),
                            "method" to agentMatch.method.name,
                            "routingLatencyMs" to routingLatencyMs
                        )
                    ))

                    sink.next(buildMetadataEvent(
                        agentId = agentMatch.agent.id,
                        agentName = agentMatch.agent.name,
                        confidence = agentMatch.confidence,
                        method = agentMatch.method.name
                    ))

                    // 📋 MR 리뷰 프로젝트 선택 필요 여부 확인
                    val (needsClarification, gitlabProjects) = needsProjectClarification(lastUserMessage, agentMatch)
                    if (needsClarification) {
                        logger.info { "MR review requires project clarification. Projects: ${gitlabProjects.map { it.id }}" }

                        // Clarification 이벤트 전송
                        val options = gitlabProjects.map { project ->
                            mapOf(
                                "id" to project.id,
                                "label" to project.name,
                                "description" to (project.description ?: project.gitlabPath ?: ""),
                                "icon" to "📂"
                            )
                        }
                        sink.next(buildClarificationEvent(
                            type = "project_selection",
                            question = "어떤 프로젝트의 MR인가요?",
                            options = options,
                            context = mapOf("originalPrompt" to lastUserMessage)
                        ))
                        sink.complete()
                        return@mono
                    }

                    // 🔧 컨텍스트 구성
                    sink.next(buildProgressEvent(
                        ProgressSteps.CONTEXT_ENRICHMENT,
                        "컨텍스트 수집 중..."
                    ))

                    // 대화 히스토리 구성
                    val conversationContext = buildConversationContext(request.messages)

                    // ✅ Pipeline을 통한 컨텍스트 Enrichment
                    val enrichedContext = enrichmentPipeline.enrich(
                        prompt = lastUserMessage,
                        userId = request.userId,
                        projectId = projectId,
                        agentId = request.agentId
                    )

                    // 📋 프로젝트 힌트가 있으면 GitLab 정보 주입 + MR 분석 (Best Practice)
                    val projectHint = extractProjectHint(lastUserMessage)
                    val mrNumber = extractMrNumber(lastUserMessage)
                    val gitlabContext = if (projectHint != null && agentMatch.agent.id == "code-reviewer") {
                        // 프로젝트 검색: ID → case-insensitive → name 포함
                        val hintedProject = projectRegistry.get(projectHint)
                            ?: projectRegistry.listAll().find { it.id.equals(projectHint, ignoreCase = true) }
                            ?: projectRegistry.listAll().find { it.name.contains(projectHint, ignoreCase = true) }

                        if (hintedProject == null) {
                            logger.warn { "Project not found for hint: $projectHint" }
                        }

                        val glPath = hintedProject?.gitlabPath
                        if (hintedProject != null && glPath != null) {
                            logger.info { "Injecting GitLab context for project: ${hintedProject.id}, path: $glPath, mrNumber: $mrNumber" }

                            // MR 분석 수행 (Best Practice: GitLab API 플래그 직접 활용)
                            val mrAnalysisContext = if (mrNumber != null) {
                                sink.next(buildProgressEvent(
                                    ProgressSteps.CONTEXT_ENRICHMENT,
                                    "MR !$mrNumber 분석 시작...",
                                    mapOf("project" to glPath, "mr" to mrNumber)
                                ))
                                val result = performMrAnalysis(glPath, mrNumber, sink)
                                if (result == null) {
                                    logger.warn { "MR analysis returned null for !$mrNumber in $glPath" }
                                }
                                result
                            } else {
                                logger.info { "No MR number found in message, skipping MR analysis" }
                                null
                            }

                            buildString {
                                append("""
                                |
                                |## GitLab Project Context
                                |- Project ID: ${hintedProject.id}
                                |- Project Name: ${hintedProject.name}
                                |- GitLab Path: $glPath
                                |
                                |Use this exact GitLab path for glab commands: `glab mr view <MR_NUMBER> -R $glPath`
                                |""".trimMargin())

                                if (mrAnalysisContext != null) {
                                    append("\n\n")
                                    append(mrAnalysisContext)
                                    logger.info { "MR analysis context injected (${mrAnalysisContext.length} chars)" }
                                } else {
                                    append("\n\n**Note**: MR 분석 결과를 가져오지 못했습니다. glab CLI로 직접 조회해주세요.")
                                }
                            }
                        } else {
                            if (hintedProject != null) {
                                logger.warn { "Project ${hintedProject.id} has no gitlabPath configured" }
                            }
                            null
                        }
                    } else null

                    // 최종 프롬프트 구성 (GitLab 컨텍스트 포함)
                    val finalPrompt = if (enrichedContext.hasInjectedContext) {
                        logger.info {
                            "Context enriched: ${enrichedContext.injectedContexts.size} contexts, " +
                                    "${enrichedContext.totalContextSize} chars"
                        }
                        sink.next(buildProgressEvent(
                            ProgressSteps.CONTEXT_ENRICHMENT,
                            "컨텍스트 ${enrichedContext.injectedContexts.size}개 수집 완료",
                            mapOf(
                                "contextCount" to enrichedContext.injectedContexts.size,
                                "totalSize" to enrichedContext.totalContextSize
                            )
                        ))
                        "${enrichedContext.enrichedPrompt}${gitlabContext ?: ""}\n\n$conversationContext"
                    } else {
                        sink.next(buildProgressEvent(
                            ProgressSteps.CONTEXT_ENRICHMENT,
                            "기본 컨텍스트 사용"
                        ))
                        "${gitlabContext ?: ""}$conversationContext"
                    }

                    // 작업 디렉토리 결정: Pipeline > 프로젝트 > 에이전트
                    val project = projectRegistry.get(projectId)
                    val workingDir = enrichedContext.workingDirectory
                        ?: project?.workingDirectory
                        ?: agentMatch.agent.workingDirectory

                    // 🚀 실행 시작
                    sink.next(buildProgressEvent(
                        ProgressSteps.EXECUTION_START,
                        "Claude 실행 중...",
                        mapOf(
                            "model" to (request.model ?: agentMatch.agent.model),
                            "workingDir" to workingDir
                        )
                    ))

                    // 실행 요청 구성
                    val executionRequest = ExecutionRequest(
                        prompt = finalPrompt,
                        systemPrompt = agentMatch.agent.systemPrompt,
                        workingDirectory = workingDir,
                        model = request.model ?: agentMatch.agent.model,
                        maxTurns = request.maxTurns ?: 50,
                        allowedTools = agentMatch.agent.allowedTools,
                        userId = request.userId,
                        threadTs = System.currentTimeMillis().toString(),
                        agentId = agentMatch.agent.id
                    )

                    // 📝 응답 생성 시작
                    sink.next(buildProgressEvent(
                        ProgressSteps.PROCESSING,
                        "응답 생성 중..."
                    ))

                    // 스트리밍 실행 (실행 기록 저장 포함)
                    val usedModel = request.model ?: agentMatch.agent.model
                    claudeExecutor.executeStreaming(executionRequest)
                        .onEach { event ->
                            val sseEvent = when (event) {
                                is StreamingEvent.Text -> buildTextEvent(event.content)
                                is StreamingEvent.ToolStart -> buildToolStartEvent(event)
                                is StreamingEvent.ToolEnd -> buildToolEndEvent(event)
                                is StreamingEvent.Done -> {
                                    // 📊 ExecutionRecord 저장 (통계용 + RAG 인덱싱 + 라우팅 메트릭)
                                    saveExecutionRecord(
                                        event = event,
                                        prompt = lastUserMessage,
                                        agentMatch = agentMatch,
                                        projectId = projectId,
                                        userId = request.userId,
                                        model = usedModel,
                                        routingLatencyMs = routingLatencyMs
                                    )
                                    buildDoneEvent(event, agentMatch.agent.id)
                                }
                                is StreamingEvent.Error -> {
                                    // 에러도 기록
                                    saveExecutionRecordOnError(
                                        requestId = event.requestId,
                                        prompt = lastUserMessage,
                                        error = event.message,
                                        agentMatch = agentMatch,
                                        projectId = projectId,
                                        userId = request.userId,
                                        model = usedModel
                                    )
                                    buildErrorEvent(event.message)
                                }
                            }
                            sink.next(sseEvent)
                        }
                        .collect()

                    sink.complete()

                } catch (e: Exception) {
                    logger.error(e) { "Chat stream error" }
                    sink.next(buildErrorEvent("Stream error: ${e.message}"))
                    sink.complete()
                }
            }.subscribe()
        }

        return Flux.merge(initialHeartbeat, periodicHeartbeat, chatStream)
            .doOnCancel { logger.info { "Chat stream cancelled" } }
            .doOnError { e -> logger.error(e) { "Chat stream error" } }
    }

    /**
     * 비스트리밍 채팅 API (폴백용)
     *
     * POST /api/v1/chat/execute
     */
    @PostMapping("/execute")
    fun executeChat(@RequestBody request: ChatRequest): Mono<ResponseEntity<ChatResponse>> {
        logger.info { "Chat execute request: messages=${request.messages.size}" }

        return mono {
            try {
                // 마지막 사용자 메시지
                val lastUserMessage = request.messages.lastOrNull { it.role == "user" }?.content
                    ?: return@mono ResponseEntity.badRequest().body(
                        ChatResponse(
                            requestId = "",
                            success = false,
                            content = null,
                            agentId = "",
                            agentName = "",
                            confidence = 0.0,
                            durationMs = 0,
                            error = "No user message found"
                        )
                    )

                // 에이전트 라우팅
                val agentMatch: AgentMatch = if (request.agentId != null) {
                    val agent = agentRouter.getAgent(request.agentId)
                    if (agent != null) {
                        AgentMatch(agent = agent, confidence = 1.0, method = RoutingMethod.DEFAULT)
                    } else {
                        return@mono ResponseEntity.badRequest().body(
                            ChatResponse(
                                requestId = "",
                                success = false,
                                content = null,
                                agentId = "",
                                agentName = "",
                                confidence = 0.0,
                                durationMs = 0,
                                error = "Agent not found: ${request.agentId}"
                            )
                        )
                    }
                } else {
                    agentRouter.route(lastUserMessage)
                }

                // 대화 히스토리 구성
                val conversationContext = buildConversationContext(request.messages)

                // ✅ Pipeline을 통한 컨텍스트 Enrichment
                val projectId = request.projectId ?: "default"
                val enrichedContext = enrichmentPipeline.enrich(
                    prompt = lastUserMessage,
                    userId = request.userId,
                    projectId = projectId,
                    agentId = request.agentId
                )

                // 최종 프롬프트 구성
                val finalPrompt = if (enrichedContext.hasInjectedContext) {
                    logger.info {
                        "Context enriched: ${enrichedContext.injectedContexts.size} contexts, " +
                                "${enrichedContext.totalContextSize} chars"
                    }
                    "${enrichedContext.enrichedPrompt}\n\n$conversationContext"
                } else {
                    conversationContext
                }

                // 작업 디렉토리 결정
                val project = projectRegistry.get(projectId)
                val workingDir = enrichedContext.workingDirectory
                    ?: project?.workingDirectory
                    ?: agentMatch.agent.workingDirectory

                // 실행 요청
                val executionRequest = ExecutionRequest(
                    prompt = finalPrompt,
                    systemPrompt = agentMatch.agent.systemPrompt,
                    workingDirectory = workingDir,
                    model = request.model ?: agentMatch.agent.model,
                    maxTurns = request.maxTurns ?: 50,
                    allowedTools = agentMatch.agent.allowedTools,
                    userId = request.userId,
                    agentId = agentMatch.agent.id
                )

                val result = claudeExecutor.execute(executionRequest)

                // ✅ ExecutionRecord 저장 (Chat 스트리밍과 동일한 수준의 데이터 수집)
                saveExecutionRecordSync(
                    requestId = result.requestId,
                    prompt = lastUserMessage,
                    result = result.result,
                    status = result.status.name,
                    agentMatch = agentMatch,
                    projectId = projectId,
                    userId = request.userId,
                    model = executionRequest.model ?: agentMatch.agent.model,
                    durationMs = result.durationMs,
                    usage = result.usage,
                    cost = result.cost,
                    error = result.error
                )

                ResponseEntity.ok(ChatResponse(
                    requestId = result.requestId,
                    success = result.status == ExecutionStatus.SUCCESS,
                    content = result.result,
                    agentId = agentMatch.agent.id,
                    agentName = agentMatch.agent.name,
                    confidence = agentMatch.confidence,
                    durationMs = result.durationMs,
                    usage = result.usage?.let {
                        UsageInfo(
                            inputTokens = it.inputTokens,
                            outputTokens = it.outputTokens,
                            cacheReadTokens = it.cacheReadTokens,
                            cacheWriteTokens = it.cacheWriteTokens
                        )
                    },
                    cost = result.cost,
                    error = result.error
                ))
            } catch (e: Exception) {
                logger.error(e) { "Chat execute error" }
                ResponseEntity.internalServerError().body(
                    ChatResponse(
                        requestId = "",
                        success = false,
                        content = null,
                        agentId = "",
                        agentName = "",
                        confidence = 0.0,
                        durationMs = 0,
                        error = "Execution error: ${e.message}"
                    )
                )
            }
        }
    }

    companion object {
        /**
         * 대화 히스토리 최대 개수 (성능 최적화)
         * 너무 긴 히스토리는 토큰 폭발을 야기하므로 최근 10개로 제한
         */
        private const val MAX_CONVERSATION_HISTORY = 10

        /**
         * 진행 상황 단계 정의
         */
        object ProgressSteps {
            const val RATE_LIMIT_CHECK = "rate_limit_check"
            const val AGENT_ROUTING = "agent_routing"
            const val CONTEXT_ENRICHMENT = "context_enrichment"
            const val EXECUTION_START = "execution_start"
            const val PROCESSING = "processing"
        }
    }

    /**
     * 대화 히스토리를 프롬프트로 변환
     * 성능 최적화: 최근 MAX_CONVERSATION_HISTORY개로 제한하여 토큰 폭발 방지
     */
    private fun buildConversationContext(messages: List<ChatMessage>): String {
        if (messages.size <= 1) {
            return messages.lastOrNull()?.content ?: ""
        }

        // 성능 최적화: 최근 10개 메시지로 제한 (토큰 비용 절감)
        val recentMessages = messages.takeLast(MAX_CONVERSATION_HISTORY + 1) // 마지막 메시지 포함
        val truncatedCount = messages.size - recentMessages.size

        val history = recentMessages.dropLast(1).joinToString("\n\n") { msg ->
            val role = when (msg.role) {
                "user" -> "User"
                "assistant" -> "Assistant"
                else -> msg.role.replaceFirstChar { it.uppercase() }
            }
            "[$role]: ${msg.content}"
        }

        val lastMessage = recentMessages.last().content

        val truncationNotice = if (truncatedCount > 0) {
            "[Note: $truncatedCount earlier messages omitted]\n\n"
        } else ""

        return """
            |Previous conversation:
            |$truncationNotice$history
            |
            |Current request:
            |$lastMessage
        """.trimMargin()
    }

    // SSE 이벤트 빌더들

    private fun buildTextEvent(content: String): ServerSentEvent<String> {
        val data = objectMapper.writeValueAsString(mapOf("content" to content))
        return ServerSentEvent.builder<String>()
            .event("text")
            .data(data)
            .build()
    }

    private fun buildToolStartEvent(event: StreamingEvent.ToolStart): ServerSentEvent<String> {
        val data = objectMapper.writeValueAsString(mapOf(
            "toolId" to event.toolId,
            "toolName" to event.toolName,
            "input" to event.input.mapValues { it.value?.toString()?.take(200) }
        ))
        return ServerSentEvent.builder<String>()
            .event("tool_start")
            .data(data)
            .build()
    }

    private fun buildToolEndEvent(event: StreamingEvent.ToolEnd): ServerSentEvent<String> {
        val data = objectMapper.writeValueAsString(mapOf(
            "toolId" to event.toolId,
            "toolName" to event.toolName,
            "success" to event.success,
            "result" to event.result?.take(500)
        ))
        return ServerSentEvent.builder<String>()
            .event("tool_end")
            .data(data)
            .build()
    }

    private fun buildMetadataEvent(
        agentId: String,
        agentName: String,
        confidence: Double,
        method: String
    ): ServerSentEvent<String> {
        val data = objectMapper.writeValueAsString(mapOf(
            "agentId" to agentId,
            "agentName" to agentName,
            "confidence" to confidence,
            "routingMethod" to method
        ))
        return ServerSentEvent.builder<String>()
            .event("metadata")
            .data(data)
            .build()
    }

    private fun buildDoneEvent(event: StreamingEvent.Done, agentId: String): ServerSentEvent<String> {
        val data = objectMapper.writeValueAsString(mapOf(
            "requestId" to event.requestId,
            "executionId" to event.requestId,  // 피드백 제출용 ID (requestId와 동일)
            "agentId" to agentId,
            "durationMs" to event.durationMs,
            "usage" to event.usage?.let {
                mapOf(
                    "inputTokens" to it.inputTokens,
                    "outputTokens" to it.outputTokens
                )
            },
            "cost" to event.cost
        ))
        return ServerSentEvent.builder<String>()
            .event("done")
            .data(data)
            .build()
    }

    private fun buildErrorEvent(message: String): ServerSentEvent<String> {
        val data = objectMapper.writeValueAsString(mapOf("message" to message))
        return ServerSentEvent.builder<String>()
            .event("error")
            .data(data)
            .build()
    }

    /**
     * 실행 기록 저장 (성공 시) - 비동기
     * Chat 스트리밍 완료 후 통계용으로 ExecutionRecord 저장
     * 스트리밍 응답에 영향을 주지 않도록 백그라운드에서 처리
     *
     * ClaudeFlowController와 동일한 수준의 데이터 수집:
     * 1. ExecutionRecord 저장
     * 2. RAG 인덱싱 (ConversationVectorService)
     * 3. 라우팅 메트릭 저장
     * 4. 사용자 컨텍스트 업데이트
     */
    private fun saveExecutionRecord(
        event: StreamingEvent.Done,
        prompt: String,
        agentMatch: AgentMatch,
        projectId: String,
        userId: String?,
        model: String,
        routingLatencyMs: Long = 0
    ) {
        storage?.let { store ->
            // 비동기로 저장 (스트리밍 블로킹 방지)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 1. ExecutionRecord 저장
                    val record = ExecutionRecord(
                        id = event.requestId,
                        prompt = prompt.take(1000),
                        result = event.result?.take(5000),
                        status = "SUCCESS",
                        agentId = agentMatch.agent.id,
                        projectId = projectId.takeIf { it != "default" },
                        userId = userId,
                        channel = null,  // Chat은 channel 없음
                        threadTs = null,
                        replyTs = null,
                        durationMs = event.durationMs,
                        inputTokens = event.usage?.inputTokens ?: 0,
                        outputTokens = event.usage?.outputTokens ?: 0,
                        cost = event.cost ?: event.usage?.let { usage ->
                            (usage.inputTokens * 0.000003) + (usage.outputTokens * 0.000015)
                        },
                        error = null,
                        model = model,
                        source = "chat",  // Chat 페이지에서의 요청
                        routingMethod = agentMatch.method.name.lowercase(),
                        routingConfidence = agentMatch.confidence
                    )
                    store.saveExecution(record)
                    logger.debug { "Chat execution saved: ${event.requestId}" }

                    // 2. RAG 자동 인덱싱 (성공한 실행만)
                    if (conversationVectorService != null) {
                        try {
                            val indexed = conversationVectorService.indexExecution(record)
                            if (indexed) {
                                logger.debug { "RAG indexed chat execution: ${event.requestId}" }
                            }
                        } catch (e: Exception) {
                            logger.warn { "RAG indexing failed for chat (non-critical): ${e.message}" }
                        }
                    }

                    // 3. 라우팅 메트릭 저장
                    try {
                        store.saveRoutingMetric(
                            executionId = event.requestId,
                            routingMethod = agentMatch.method.name.lowercase(),
                            agentId = agentMatch.agent.id,
                            confidence = agentMatch.confidence,
                            latencyMs = routingLatencyMs
                        )
                        logger.debug { "Chat routing metric saved: method=${agentMatch.method.name}, latency=${routingLatencyMs}ms" }
                    } catch (e: Exception) {
                        logger.warn { "Failed to save chat routing metric: ${e.message}" }
                    }

                    // 4. 사용자 컨텍스트 업데이트 (User Management용)
                    userId?.let { uid ->
                        try {
                            store.updateUserInteraction(
                                userId = uid,
                                promptLength = prompt.length,
                                responseLength = event.result?.length ?: 0
                            )
                            logger.debug { "Updated user context for chat: $uid" }
                        } catch (e: Exception) {
                            logger.warn { "Failed to update user context for chat: ${e.message}" }
                        }
                    }

                } catch (e: Exception) {
                    logger.warn { "Failed to save chat execution: ${e.message}" }
                }
            }
        }
    }

    /**
     * 실행 기록 저장 (동기 - /execute API용)
     * executeChat API에서 사용되며, 스트리밍과 동일한 수준의 데이터 수집
     */
    private suspend fun saveExecutionRecordSync(
        requestId: String,
        prompt: String,
        result: String?,
        status: String,
        agentMatch: AgentMatch,
        projectId: String,
        userId: String?,
        model: String,
        durationMs: Long,
        usage: ai.claudeflow.executor.TokenUsage?,
        cost: Double?,
        error: String?
    ) {
        storage?.let { store ->
            try {
                val record = ExecutionRecord(
                    id = requestId,
                    prompt = prompt.take(1000),
                    result = result?.take(5000),
                    status = status,
                    agentId = agentMatch.agent.id,
                    projectId = projectId.takeIf { it != "default" },
                    userId = userId,
                    channel = null,
                    threadTs = null,
                    replyTs = null,
                    durationMs = durationMs,
                    inputTokens = usage?.inputTokens ?: 0,
                    outputTokens = usage?.outputTokens ?: 0,
                    cost = cost ?: usage?.let { u ->
                        (u.inputTokens * 0.000003) + (u.outputTokens * 0.000015)
                    },
                    error = error,
                    model = model,
                    source = "chat",
                    routingMethod = agentMatch.method.name.lowercase(),
                    routingConfidence = agentMatch.confidence
                )
                store.saveExecution(record)
                logger.debug { "Chat execute saved: $requestId" }

                // RAG 자동 인덱싱 (성공한 실행만)
                if (status == "SUCCESS" && conversationVectorService != null) {
                    try {
                        val indexed = conversationVectorService.indexExecution(record)
                        if (indexed) {
                            logger.debug { "RAG indexed chat execute: $requestId" }
                        }
                    } catch (e: Exception) {
                        logger.warn { "RAG indexing failed for chat execute (non-critical): ${e.message}" }
                    }
                }

                // 사용자 컨텍스트 업데이트
                userId?.let { uid ->
                    try {
                        store.updateUserInteraction(
                            userId = uid,
                            promptLength = prompt.length,
                            responseLength = result?.length ?: 0
                        )
                    } catch (e: Exception) {
                        logger.warn { "Failed to update user context for chat execute: ${e.message}" }
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to save chat execute: ${e.message}" }
            }
        }
    }

    /**
     * 실행 기록 저장 (에러 시) - 비동기
     */
    private fun saveExecutionRecordOnError(
        requestId: String,
        prompt: String,
        error: String,
        agentMatch: AgentMatch,
        projectId: String,
        userId: String?,
        model: String
    ) {
        storage?.let { store ->
            // 비동기로 저장 (스트리밍 블로킹 방지)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val record = ExecutionRecord(
                        id = requestId,
                        prompt = prompt.take(1000),
                        result = null,
                        status = "ERROR",
                        agentId = agentMatch.agent.id,
                        projectId = projectId.takeIf { it != "default" },
                        userId = userId,
                        channel = null,
                        threadTs = null,
                        replyTs = null,
                        durationMs = 0,
                        inputTokens = 0,
                        outputTokens = 0,
                        cost = null,
                        error = error,
                        model = model,
                        source = "chat",
                        routingMethod = agentMatch.method.name.lowercase(),
                        routingConfidence = agentMatch.confidence
                    )
                    store.saveExecution(record)
                    logger.debug { "Chat error execution saved: $requestId" }
                } catch (e: Exception) {
                    logger.warn { "Failed to save chat error execution: ${e.message}" }
                }
            }
        }
    }

    /**
     * 진행 상황 이벤트 빌더
     *
     * @param step 현재 단계 (ProgressSteps 참조)
     * @param message 사용자에게 보여줄 메시지
     * @param detail 상세 정보 (선택)
     */
    private fun buildProgressEvent(
        step: String,
        message: String,
        detail: Map<String, Any?>? = null
    ): ServerSentEvent<String> {
        val eventData = mutableMapOf<String, Any?>(
            "step" to step,
            "message" to message,
            "timestamp" to System.currentTimeMillis()
        )
        if (detail != null) {
            eventData["detail"] = detail
        }
        val data = objectMapper.writeValueAsString(eventData)
        return ServerSentEvent.builder<String>()
            .event("progress")
            .data(data)
            .build()
    }

    /**
     * Clarification 이벤트 빌더 (프로젝트 선택 등)
     */
    private fun buildClarificationEvent(
        type: String,
        question: String,
        options: List<Map<String, String>>,
        context: Map<String, Any?>? = null
    ): ServerSentEvent<String> {
        val eventData = mutableMapOf<String, Any?>(
            "type" to type,
            "question" to question,
            "options" to options
        )
        if (context != null) {
            eventData["context"] = context
        }
        val data = objectMapper.writeValueAsString(eventData)
        return ServerSentEvent.builder<String>()
            .event("clarification")
            .data(data)
            .build()
    }

    /**
     * MR 리뷰 요청인지 확인
     */
    private fun isMrReviewRequest(message: String): Boolean {
        return MR_REVIEW_PATTERNS.any { it.containsMatchIn(message) }
    }

    /**
     * 메시지에서 프로젝트 힌트 추출 (예: "[프로젝트: ccds-server]")
     */
    private fun extractProjectHint(message: String): String? {
        val hintPattern = Regex("""\[프로젝트:\s*([^\]]+)\]""")
        return hintPattern.find(message)?.groupValues?.get(1)?.trim()
    }

    /**
     * GitLab 경로가 있는 프로젝트 목록 조회
     */
    private fun getGitLabProjects(): List<Project> {
        return projectRegistry.listAll().filter { !it.gitlabPath.isNullOrBlank() }
    }

    /**
     * MR 리뷰 프로젝트 선택이 필요한지 확인
     *
     * @return Pair<Boolean, List<Project>> - (선택 필요 여부, GitLab 프로젝트 목록)
     */
    private fun needsProjectClarification(
        message: String,
        agentMatch: AgentMatch
    ): Pair<Boolean, List<Project>> {
        // 1. code-reviewer 에이전트가 아니면 clarification 불필요
        if (agentMatch.agent.id != "code-reviewer") {
            return Pair(false, emptyList())
        }

        // 2. MR 리뷰 요청이 아니면 clarification 불필요
        if (!isMrReviewRequest(message)) {
            return Pair(false, emptyList())
        }

        // 3. 이미 프로젝트 힌트가 있으면 clarification 불필요
        if (extractProjectHint(message) != null) {
            return Pair(false, emptyList())
        }

        // 4. GitLab 프로젝트 목록 조회
        val gitlabProjects = getGitLabProjects()
        if (gitlabProjects.isEmpty()) {
            return Pair(false, emptyList())
        }

        // 5. GitLab 프로젝트가 1개뿐이면 자동 선택 (clarification 불필요)
        if (gitlabProjects.size == 1) {
            return Pair(false, emptyList())
        }

        return Pair(true, gitlabProjects)
    }

    /**
     * 후속 질문 여부 판단
     *
     * 세션 컨텍스트가 있을 때 현재 메시지가 이전 대화의 후속 질문인지 판단
     * MR 리뷰 관련 후속 질문 패턴:
     * - "파일명 확인해줘", "다시 봐줘", "변경사항 확인"
     * - 짧은 질문 (새로운 주제가 아님)
     * - MR 번호나 프로젝트명 없이 추가 요청
     */
    private fun isFollowUpQuestion(message: String, sessionContext: SessionContext): Boolean {
        val normalizedMessage = message.lowercase().trim()

        // MR 리뷰 세션인 경우
        if (sessionContext.lastTopic == "mr-review" || sessionContext.mrNumber != null) {
            // 후속 질문 패턴
            val followUpPatterns = listOf(
                "확인", "봐줘", "다시", "더", "자세히", "왜", "어떻게",
                "파일", "변경", "수정", "추가", "삭제",
                "맞니", "맞아", "아니야", "그거", "이거",
                "진짜", "정말", "제대로"
            )

            // 새로운 MR 요청인지 확인 (새 MR 번호가 있으면 후속 질문 아님)
            val newMrPattern = Regex("""!(\d+)|MR\s*(\d+)""", RegexOption.IGNORE_CASE)
            val newMrMatch = newMrPattern.find(message)
            if (newMrMatch != null) {
                val newMrNumber = (newMrMatch.groupValues[1].takeIf { it.isNotEmpty() }
                    ?: newMrMatch.groupValues[2]).toIntOrNull()
                // 다른 MR 번호면 새 요청
                if (newMrNumber != null && newMrNumber != sessionContext.mrNumber) {
                    return false
                }
            }

            // 후속 질문 패턴 매칭
            if (followUpPatterns.any { normalizedMessage.contains(it) }) {
                return true
            }

            // 짧은 메시지는 후속 질문으로 간주 (50자 이하)
            if (message.length <= 50) {
                return true
            }
        }

        return false
    }

    /**
     * MR 번호 추출
     */
    private fun extractMrNumber(message: String): Int? {
        val mrPattern = Regex("""!(\d+)|MR\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = mrPattern.find(message) ?: return null
        return (match.groupValues[1].takeIf { it.isNotEmpty() }
            ?: match.groupValues[2]).toIntOrNull()
    }

    /**
     * MR 분석 수행 (Best Practice: MrAnalyzer + GitLab API 플래그 활용)
     *
     * GitLabPlugin의 mr-review 명령을 호출하여 규칙 기반 분석 결과를 가져옵니다.
     * 이 결과를 Claude 컨텍스트에 주입하여 2-Pass 리뷰 아키텍처를 구현합니다.
     *
     * @param gitlabPath GitLab 프로젝트 경로 (예: sirius/ccds-server)
     * @param mrId MR 번호
     * @param sink SSE 이벤트 전송용 sink
     * @return MR 분석 결과 컨텍스트 문자열 (실패 시 null)
     */
    private suspend fun performMrAnalysis(
        gitlabPath: String,
        mrId: Int,
        sink: reactor.core.publisher.FluxSink<ServerSentEvent<String>>
    ): String? {
        return try {
            sink.next(buildProgressEvent(
                ProgressSteps.CONTEXT_ENRICHMENT,
                "MR !$mrId 분석 중... (Pass 1: 규칙 기반)",
                mapOf("project" to gitlabPath, "mrId" to mrId)
            ))

            val gitlabPlugin = pluginRegistry.get("gitlab") as? GitLabPlugin
            if (gitlabPlugin == null) {
                logger.warn { "GitLabPlugin not available for MR analysis" }
                return null
            }

            val result = gitlabPlugin.execute("mr-review", mapOf(
                "project" to gitlabPath,
                "mr_id" to mrId
            ))

            if (!result.success || result.data == null) {
                logger.warn { "MR analysis failed: ${result.error}" }
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>

            // 분석 결과 추출
            val summary = data["summary"] as? String ?: ""
            val quickIssues = data["quickIssues"] as? List<Map<String, Any>> ?: emptyList()
            val fileAnalysis = data["fileAnalysis"] as? Map<String, Any>
            val reviewPrompt = data["review_prompt"] as? String
            val priorityFiles = data["priorityFiles"] as? List<String> ?: emptyList()

            // 이슈 개수 알림
            sink.next(buildProgressEvent(
                ProgressSteps.CONTEXT_ENRICHMENT,
                "MR 분석 완료: ${quickIssues.size}개 이슈 감지",
                mapOf(
                    "issues" to quickIssues.size,
                    "files" to (fileAnalysis?.get("totalFiles") ?: 0)
                )
            ))

            // 컨텍스트 문자열 구성
            buildString {
                appendLine("## MR 분석 결과 (Pass 1: 규칙 기반 분석)")
                appendLine()
                appendLine("### 요약")
                appendLine(summary)
                appendLine()

                // 파일 분석 결과
                if (fileAnalysis != null) {
                    appendLine("### 파일 변경 분석 (GitLab API 플래그 기반)")
                    @Suppress("UNCHECKED_CAST")
                    val renamed = fileAnalysis["renamed"] as? List<Map<String, Any>> ?: emptyList()
                    @Suppress("UNCHECKED_CAST")
                    val addedMaps = fileAnalysis["added"] as? List<Map<String, Any>> ?: emptyList()
                    @Suppress("UNCHECKED_CAST")
                    val deletedMaps = fileAnalysis["deleted"] as? List<Map<String, Any>> ?: emptyList()
                    @Suppress("UNCHECKED_CAST")
                    val modifiedMaps = fileAnalysis["modified"] as? List<Map<String, Any>> ?: emptyList()

                    // path 필드 추출
                    val added = addedMaps.mapNotNull { it["path"] as? String }
                    val deleted = deletedMaps.mapNotNull { it["path"] as? String }
                    val modified = modifiedMaps.mapNotNull { it["path"] as? String }

                    appendLine("| 유형 | 파일 | 비고 |")
                    appendLine("|------|------|------|")
                    renamed.forEach { r ->
                        appendLine("| ✏️ Rename | ${r["oldPath"]} → ${r["newPath"]} | 파일명 변경 |")
                    }
                    added.forEach { f ->
                        appendLine("| ➕ Add | $f | 신규 파일 |")
                    }
                    deleted.forEach { f ->
                        appendLine("| ➖ Delete | $f | 삭제 |")
                    }
                    modified.take(10).forEach { f ->
                        appendLine("| 📝 Modify | $f | 내용 수정 |")
                    }
                    if (modified.size > 10) {
                        appendLine("| ... | ${modified.size - 10}개 파일 더 | |")
                    }
                    appendLine()
                }

                // 빠른 이슈 (Quick Issues)
                if (quickIssues.isNotEmpty()) {
                    appendLine("### 🚨 자동 감지된 이슈 (반드시 리뷰에 포함!)")
                    quickIssues.forEach { issue ->
                        val severity = issue["severity"] as? String ?: "INFO"
                        val category = issue["category"] as? String ?: ""
                        // message 또는 description 필드 지원
                        val message = issue["message"] as? String
                            ?: issue["description"] as? String
                            ?: ""
                        val suggestion = issue["suggestion"] as? String ?: ""
                        val icon = when (severity) {
                            "ERROR" -> "🚨"
                            "WARNING" -> "⚠️"
                            else -> "ℹ️"
                        }
                        appendLine("- $icon **[$severity]** $message")
                        if (suggestion.isNotEmpty()) {
                            appendLine("  - 권장: $suggestion")
                        }
                    }
                    appendLine()
                }

                // 리뷰 우선순위 파일
                if (priorityFiles.isNotEmpty()) {
                    appendLine("### 리뷰 우선순위 파일")
                    priorityFiles.take(5).forEachIndexed { idx, file ->
                        appendLine("${idx + 1}. `$file`")
                    }
                    appendLine()
                }

                // AI 리뷰 가이드
                appendLine("### AI 리뷰 지침")
                appendLine("""
                |위 분석 결과를 참고하여 심층 리뷰를 진행해주세요:
                |1. 자동 감지된 이슈들을 먼저 확인하고 검증
                |2. 우선순위 파일들의 변경사항 상세 분석
                |3. 파일명 변경(Rename)과 내용 수정(Modify) 정확히 구분
                |4. 보안, Breaking Change, 코드 품질 관점에서 추가 검토
                """.trimMargin())

                // 리뷰 프롬프트가 있으면 추가
                if (reviewPrompt != null && reviewPrompt.length > 100) {
                    appendLine()
                    appendLine("### 생성된 리뷰 프롬프트")
                    appendLine("```")
                    appendLine(reviewPrompt.take(2000))
                    if (reviewPrompt.length > 2000) appendLine("...")
                    appendLine("```")
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to perform MR analysis for !$mrId" }
            null
        }
    }
}
