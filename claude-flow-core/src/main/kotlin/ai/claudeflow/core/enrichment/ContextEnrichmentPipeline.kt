package ai.claudeflow.core.enrichment

import mu.KotlinLogging
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 컨텍스트 Enrichment 파이프라인
 *
 * Chain of Responsibility 패턴으로 여러 Enricher를 순차 실행합니다.
 * 각 Enricher는 독립적이며, Spring에서 자동 주입됩니다.
 *
 * 실행 순서는 priority에 따라 결정됩니다 (낮을수록 먼저).
 *
 * 사용 예:
 * ```kotlin
 * val context = EnrichmentContext.create(
 *     prompt = "어떤 프로젝트들을 관리하고 있니?",
 *     userId = "U123",
 *     projectId = "my-project"
 * )
 * val enriched = pipeline.enrich(context)
 * println(enriched.enrichedPrompt)
 * ```
 *
 * @see ContextEnricher
 * @see EnrichmentContext
 */
class ContextEnrichmentPipeline(
    enrichers: List<ContextEnricher> = emptyList()
) {
    // priority 순으로 정렬
    private val sortedEnrichers: List<ContextEnricher> = enrichers.sortedBy { it.priority }

    init {
        if (sortedEnrichers.isNotEmpty()) {
            logger.info {
                "ContextEnrichmentPipeline initialized with ${sortedEnrichers.size} enrichers: " +
                        sortedEnrichers.joinToString { "${it.id}(priority=${it.priority})" }
            }
        }
    }

    /**
     * 컨텍스트를 순차적으로 풍부하게 만듦
     *
     * @param context 초기 컨텍스트
     * @return 모든 Enricher를 거친 최종 컨텍스트
     */
    suspend fun enrich(context: EnrichmentContext): EnrichmentContext {
        if (sortedEnrichers.isEmpty()) {
            logger.debug { "No enrichers configured, returning original context" }
            return context
        }

        var currentContext = context
        val pipelineStart = Instant.now()

        logger.debug { "Starting enrichment pipeline for prompt: ${context.originalPrompt.take(100)}..." }

        for (enricher in sortedEnrichers) {
            val enricherStart = Instant.now()

            try {
                if (enricher.shouldEnrich(currentContext)) {
                    logger.debug { "Applying enricher: ${enricher.id}" }

                    currentContext = enricher.enrich(currentContext)

                    val durationMs = Duration.between(enricherStart, Instant.now()).toMillis()
                    currentContext = currentContext.withLog(
                        enricherId = enricher.id,
                        message = "Applied successfully",
                        durationMs = durationMs
                    )

                    logger.debug {
                        "Enricher ${enricher.id} completed in ${durationMs}ms, " +
                                "contexts injected: ${currentContext.injectedContexts.size}"
                    }
                } else {
                    logger.debug { "Skipping enricher: ${enricher.id} (shouldEnrich=false)" }
                    currentContext = currentContext.withLog(
                        enricherId = enricher.id,
                        message = "Skipped (condition not met)"
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "Enricher ${enricher.id} failed, continuing with next enricher" }
                currentContext = currentContext.withLog(
                    enricherId = enricher.id,
                    message = "Failed: ${e.message}"
                )
                // 하나의 Enricher 실패가 전체를 막지 않음
            }
        }

        val totalDurationMs = Duration.between(pipelineStart, Instant.now()).toMillis()
        logger.info {
            "Enrichment pipeline completed in ${totalDurationMs}ms, " +
                    "total contexts: ${currentContext.injectedContexts.size}, " +
                    "total size: ${currentContext.totalContextSize} chars"
        }

        return currentContext
    }

    /**
     * 간편 메서드: 프롬프트와 메타데이터로 바로 enrichment 수행
     */
    suspend fun enrich(
        prompt: String,
        userId: String? = null,
        projectId: String? = null,
        agentId: String? = null,
        channelId: String? = null,
        threadTs: String? = null,
        additionalMetadata: Map<String, Any> = emptyMap()
    ): EnrichmentContext {
        val context = EnrichmentContext.create(
            prompt = prompt,
            userId = userId,
            projectId = projectId,
            agentId = agentId,
            channelId = channelId,
            threadTs = threadTs,
            additionalMetadata = additionalMetadata
        )
        return enrich(context)
    }

    /**
     * 등록된 Enricher 목록 조회
     */
    fun getEnrichers(): List<ContextEnricher> = sortedEnrichers.toList()

    /**
     * 특정 ID의 Enricher 조회
     */
    fun getEnricher(id: String): ContextEnricher? = sortedEnrichers.find { it.id == id }

    companion object {
        /**
         * 빈 파이프라인 (테스트용)
         */
        val EMPTY = ContextEnrichmentPipeline(emptyList())
    }
}

/**
 * Enrichment 결과 요약
 */
data class EnrichmentResult(
    val originalPrompt: String,
    val enrichedPrompt: String,
    val workingDirectory: String?,
    val contextInjected: Boolean,
    val injectedContextCount: Int,
    val totalContextSize: Int,
    val processingTimeMs: Long,
    val enricherLogs: List<EnrichmentLogEntry>
) {
    companion object {
        fun from(context: EnrichmentContext): EnrichmentResult {
            val processingTime = Duration.between(context.startTime, Instant.now()).toMillis()
            return EnrichmentResult(
                originalPrompt = context.originalPrompt,
                enrichedPrompt = context.enrichedPrompt,
                workingDirectory = context.workingDirectory,
                contextInjected = context.hasInjectedContext,
                injectedContextCount = context.injectedContexts.size,
                totalContextSize = context.totalContextSize,
                processingTimeMs = processingTime,
                enricherLogs = context.enrichmentLog
            )
        }
    }
}
