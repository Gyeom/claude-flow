package ai.claudeflow.api.config

import ai.claudeflow.core.enrichment.ContextEnricher
import ai.claudeflow.core.enrichment.ContextEnrichmentPipeline
import ai.claudeflow.core.enrichment.ProjectContextEnricher
import ai.claudeflow.core.rag.KnowledgeVectorService
import ai.claudeflow.core.storage.Storage
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private val logger = KotlinLogging.logger {}

/**
 * Context Enrichment 파이프라인 설정
 *
 * 모든 ContextEnricher Bean을 수집하여 파이프라인을 구성합니다.
 * 새 Enricher를 추가하려면 ContextEnricher 인터페이스를 구현하고 @Bean으로 등록하세요.
 */
@Configuration
class EnrichmentConfig {

    /**
     * 프로젝트 컨텍스트 Enricher
     * RAG 기반 프로젝트 검색 및 패턴 매칭
     */
    @Bean
    fun projectContextEnricher(
        knowledgeVectorService: KnowledgeVectorService?,
        storage: Storage,
        objectMapper: ObjectMapper,
        @Value("\${claude-flow.config-path:#{null}}") configPath: String?,
        @Value("\${claude-flow.workspace-root:#{null}}") workspaceRoot: String?
    ): ProjectContextEnricher {
        logger.info { "Creating ProjectContextEnricher (RAG enabled: ${knowledgeVectorService != null})" }
        return ProjectContextEnricher(
            knowledgeVectorService = knowledgeVectorService,
            storage = storage,
            objectMapper = objectMapper,
            configPath = configPath,
            workspaceRoot = workspaceRoot
        )
    }

    /**
     * Enrichment 파이프라인
     * 모든 ContextEnricher를 priority 순으로 실행
     */
    @Bean
    fun contextEnrichmentPipeline(
        enrichers: List<ContextEnricher>
    ): ContextEnrichmentPipeline {
        logger.info { "Creating ContextEnrichmentPipeline with ${enrichers.size} enrichers" }
        return ContextEnrichmentPipeline(enrichers)
    }
}
