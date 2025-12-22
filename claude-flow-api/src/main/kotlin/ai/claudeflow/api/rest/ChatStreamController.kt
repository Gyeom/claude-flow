package ai.claudeflow.api.rest

import ai.claudeflow.api.dto.*
import ai.claudeflow.core.enrichment.ContextEnrichmentPipeline
import ai.claudeflow.core.enrichment.EnrichmentContext
import ai.claudeflow.core.model.AgentMatch
import ai.claudeflow.core.model.RoutingMethod
import ai.claudeflow.core.ratelimit.RateLimiter
import ai.claudeflow.core.registry.ProjectRegistry
import ai.claudeflow.core.routing.AgentRouter
import ai.claudeflow.core.storage.Storage
import ai.claudeflow.executor.*
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.reactor.mono
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
    private val storage: Storage? = null,
    private val rateLimiter: RateLimiter? = null
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
                    // Rate limit 체크
                    val projectId = request.projectId ?: "default"
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

                    // 에이전트 라우팅
                    val agentMatch: AgentMatch = if (request.agentId != null) {
                        val agent = agentRouter.getAgent(request.agentId)
                        if (agent != null) {
                            AgentMatch(
                                agent = agent,
                                confidence = 1.0,
                                method = RoutingMethod.DEFAULT
                            )
                        } else {
                            sink.next(buildErrorEvent("Agent not found: ${request.agentId}"))
                            sink.complete()
                            return@mono
                        }
                    } else {
                        agentRouter.route(lastUserMessage)
                    }

                    // 메타데이터 이벤트 전송
                    sink.next(buildMetadataEvent(
                        agentId = agentMatch.agent.id,
                        agentName = agentMatch.agent.name,
                        confidence = agentMatch.confidence,
                        method = agentMatch.method.name
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

                    // 작업 디렉토리 결정: Pipeline > 프로젝트 > 에이전트
                    val project = projectRegistry.get(projectId)
                    val workingDir = enrichedContext.workingDirectory
                        ?: project?.workingDirectory
                        ?: agentMatch.agent.workingDirectory

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

                    // 스트리밍 실행
                    claudeExecutor.executeStreaming(executionRequest)
                        .onEach { event ->
                            val sseEvent = when (event) {
                                is StreamingEvent.Text -> buildTextEvent(event.content)
                                is StreamingEvent.ToolStart -> buildToolStartEvent(event)
                                is StreamingEvent.ToolEnd -> buildToolEndEvent(event)
                                is StreamingEvent.Done -> buildDoneEvent(event, agentMatch.agent.id)
                                is StreamingEvent.Error -> buildErrorEvent(event.message)
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

    /**
     * 대화 히스토리를 프롬프트로 변환
     */
    private fun buildConversationContext(messages: List<ChatMessage>): String {
        if (messages.size <= 1) {
            return messages.lastOrNull()?.content ?: ""
        }

        val history = messages.dropLast(1).joinToString("\n\n") { msg ->
            val role = when (msg.role) {
                "user" -> "User"
                "assistant" -> "Assistant"
                else -> msg.role.replaceFirstChar { it.uppercase() }
            }
            "[$role]: ${msg.content}"
        }

        val lastMessage = messages.last().content

        return """
            |Previous conversation:
            |$history
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
}
