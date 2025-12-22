package ai.claudeflow.api.rest

import ai.claudeflow.core.enrichment.ContextEnrichmentPipeline
import ai.claudeflow.core.enrichment.EnrichmentResult
import ai.claudeflow.core.enrichment.EnricherType
import ai.claudeflow.core.rag.AugmentationOptions
import ai.claudeflow.core.rag.ContextAugmentationService
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * 컨텍스트 Enrichment API Controller
 *
 * n8n 워크플로우에서 프롬프트 실행 전에 컨텍스트를 주입할 수 있습니다.
 * Chain of Responsibility 패턴의 Enricher들이 순차적으로 적용됩니다.
 *
 * 사용 흐름:
 * 1. n8n에서 Slack 메시지 수신
 * 2. /api/v1/enrich 호출하여 컨텍스트 주입
 * 3. enrichedPrompt로 /api/v1/execute 또는 /api/v1/execute-with-routing 호출
 */
@RestController
@RequestMapping("/api/v1")
class EnrichmentController(
    private val enrichmentPipeline: ContextEnrichmentPipeline,
    private val contextAugmentationService: ContextAugmentationService? = null
) {
    /**
     * 프롬프트 컨텍스트 Enrichment API
     *
     * 사용자 메시지에 프로젝트 컨텍스트, 사용자 규칙 등을 주입합니다.
     *
     * POST /api/v1/enrich
     * {
     *   "prompt": "사용자 메시지",
     *   "userId": "U123",
     *   "projectId": "my-project",
     *   "includeRag": true
     * }
     *
     * Response:
     * {
     *   "enrichedPrompt": "컨텍스트가 주입된 프롬프트",
     *   "appliedEnrichers": ["ProjectContextEnricher", "UserRuleEnricher"],
     *   "workingDirectory": "/workspace/my-project",
     *   "metadata": {...}
     * }
     */
    @PostMapping("/enrich")
    fun enrich(@RequestBody request: EnrichRequest): Mono<ResponseEntity<EnrichResponse>> = mono {
        logger.info { "Enrich request: prompt=${request.prompt.take(50)}..." }

        try {
            // 1. Pipeline을 통한 Enrichment
            val enrichedContext = enrichmentPipeline.enrich(
                prompt = request.prompt,
                userId = request.userId,
                projectId = request.projectId,
                agentId = request.agentId,
                channelId = request.channel,
                threadTs = request.threadTs,
                additionalMetadata = request.metadata ?: emptyMap()
            )

            // 2. RAG 컨텍스트 증강 (옵션)
            var ragSystemPrompt: String? = null
            var ragMetadata: Map<String, Any> = emptyMap()

            if (request.includeRag == true && request.userId != null && contextAugmentationService != null) {
                try {
                    val augmented = contextAugmentationService.buildAugmentedContext(
                        userId = request.userId,
                        message = request.prompt,
                        options = AugmentationOptions(
                            includeSimilarConversations = true,
                            includeUserRules = true,
                            includeUserSummary = true,
                            maxSimilarConversations = request.maxSimilarConversations ?: 3,
                            minSimilarityScore = request.minSimilarityScore ?: 0.65f
                        )
                    )

                    if (augmented.systemPrompt.isNotBlank()) {
                        ragSystemPrompt = augmented.systemPrompt
                        ragMetadata = mapOf(
                            "similarConversationsCount" to augmented.relevantConversations.size,
                            "userRulesCount" to augmented.userRules.size,
                            "userSummaryIncluded" to (augmented.userSummary != null),
                            "processingTimeMs" to augmented.metadata.totalTimeMs
                        )
                        logger.debug {
                            "RAG context augmented: ${augmented.relevantConversations.size} similar, " +
                                    "${augmented.userRules.size} rules"
                        }
                    }
                } catch (e: Exception) {
                    logger.warn { "RAG augmentation failed (continuing without): ${e.message}" }
                }
            }

            // 3. 결과 반환
            val result = EnrichmentResult.from(enrichedContext)
            val response = EnrichResponse(
                success = true,
                originalPrompt = result.originalPrompt,
                enrichedPrompt = result.enrichedPrompt,
                workingDirectory = result.workingDirectory,
                contextInjected = result.contextInjected,
                injectedContextCount = result.injectedContextCount,
                totalContextSize = result.totalContextSize,
                processingTimeMs = result.processingTimeMs,
                appliedEnrichers = result.enricherLogs.filter { it.message.contains("successfully") }
                    .map { it.enricherId },
                enricherLogs = result.enricherLogs.map { log ->
                    EnricherLogDto(
                        enricherId = log.enricherId,
                        message = log.message,
                        durationMs = log.durationMs
                    )
                },
                ragSystemPrompt = ragSystemPrompt,
                ragMetadata = ragMetadata.takeIf { it.isNotEmpty() }
            )

            logger.info {
                "Enrichment complete: ${response.injectedContextCount} contexts, " +
                        "${response.totalContextSize} chars, ${response.processingTimeMs}ms"
            }

            ResponseEntity.ok(response)
        } catch (e: Exception) {
            logger.error(e) { "Enrichment failed" }
            ResponseEntity.ok(EnrichResponse(
                success = false,
                originalPrompt = request.prompt,
                enrichedPrompt = request.prompt,
                error = e.message
            ))
        }
    }

    /**
     * 등록된 Enricher 목록 조회
     */
    @GetMapping("/enrichers")
    fun listEnrichers(): Mono<ResponseEntity<List<EnricherInfoDto>>> = mono {
        val enrichers = enrichmentPipeline.getEnrichers().map { enricher ->
            EnricherInfoDto(
                id = enricher.id,
                name = enricher.name,
                priority = enricher.priority
            )
        }
        ResponseEntity.ok(enrichers)
    }

    /**
     * RAG 상태 확인
     */
    @GetMapping("/enrich/rag-status")
    fun getRagStatus(): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val status = mapOf(
            "ragEnabled" to (contextAugmentationService != null),
            "enrichersCount" to enrichmentPipeline.getEnrichers().size,
            "enrichers" to enrichmentPipeline.getEnrichers().map { it.id }
        )
        ResponseEntity.ok(status)
    }
}

// Request/Response DTOs

data class EnrichRequest(
    val prompt: String,
    val userId: String? = null,
    val projectId: String? = null,
    val agentId: String? = null,
    val channel: String? = null,
    val threadTs: String? = null,
    val metadata: Map<String, Any>? = null,
    /** RAG 컨텍스트 증강 포함 여부 */
    val includeRag: Boolean? = false,
    /** RAG: 유사 대화 최대 개수 */
    val maxSimilarConversations: Int? = 3,
    /** RAG: 최소 유사도 점수 (0.0 ~ 1.0) */
    val minSimilarityScore: Float? = 0.65f
)

data class EnrichResponse(
    val success: Boolean,
    val originalPrompt: String,
    val enrichedPrompt: String,
    val workingDirectory: String? = null,
    val contextInjected: Boolean = false,
    val injectedContextCount: Int = 0,
    val totalContextSize: Int = 0,
    val processingTimeMs: Long = 0,
    val appliedEnrichers: List<String> = emptyList(),
    val enricherLogs: List<EnricherLogDto> = emptyList(),
    /** RAG로 생성된 시스템 프롬프트 (별도 사용) */
    val ragSystemPrompt: String? = null,
    val ragMetadata: Map<String, Any>? = null,
    val error: String? = null
)

data class EnricherLogDto(
    val enricherId: String,
    val message: String,
    val durationMs: Long
)

data class EnricherInfoDto(
    val id: String,
    val name: String,
    val priority: Int
)
