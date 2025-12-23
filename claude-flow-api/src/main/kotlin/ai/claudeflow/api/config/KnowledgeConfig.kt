package ai.claudeflow.api.config

import ai.claudeflow.core.knowledge.ImageAnalysisService
import ai.claudeflow.core.knowledge.KnowledgeService
import ai.claudeflow.core.rag.EmbeddingService
import ai.claudeflow.core.rag.KnowledgeVectorService
import ai.claudeflow.core.storage.Storage
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private val logger = KotlinLogging.logger {}

/**
 * Knowledge Base 설정
 */
@Configuration
class KnowledgeConfig {

    /**
     * 이미지 분석 서비스
     * Claude Vision API를 사용하여 이미지에서 정보 추출
     */
    @Bean
    fun imageAnalysisService(
        @Value("\${claude-flow.claude.model:claude-sonnet-4-20250514}") model: String
    ): ImageAnalysisService? {
        val apiKey = System.getenv("ANTHROPIC_API_KEY")
        if (apiKey.isNullOrBlank()) {
            logger.info { "ImageAnalysisService disabled (ANTHROPIC_API_KEY not set)" }
            return null
        }

        logger.info { "Creating ImageAnalysisService with model: $model" }
        return ImageAnalysisService(
            apiKey = apiKey,
            model = model
        )
    }

    /**
     * Knowledge 서비스
     * 문서 업로드, URL 수집, 인덱싱 등을 관리
     */
    @Bean
    fun knowledgeService(
        storage: Storage,
        knowledgeVectorService: KnowledgeVectorService?,
        imageAnalysisService: ImageAnalysisService?,
        embeddingService: EmbeddingService?
    ): KnowledgeService {
        logger.info {
            "Creating KnowledgeService (vector: ${knowledgeVectorService != null}, " +
            "image: ${imageAnalysisService != null}, embedding: ${embeddingService != null})"
        }

        return KnowledgeService(
            knowledgeRepository = storage.knowledgeRepository,
            knowledgeVectorService = knowledgeVectorService,
            imageAnalysisService = imageAnalysisService,
            embeddingService = embeddingService
        )
    }
}
