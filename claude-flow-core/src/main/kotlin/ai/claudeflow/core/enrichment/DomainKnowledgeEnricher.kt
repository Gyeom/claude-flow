package ai.claudeflow.core.enrichment

import ai.claudeflow.core.plugin.PluginManager
import ai.claudeflow.core.plugin.ConfluencePlugin
import ai.claudeflow.core.plugin.ReviewContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 도메인 지식 Enricher
 *
 * Confluence 플러그인을 활용하여 코드 리뷰에 필요한
 * 도메인 지식을 컨텍스트에 주입합니다.
 *
 * 토큰 최적화:
 * - MCP 대비 80% 토큰 절감
 * - 키워드 관련 섹션만 추출
 * - 캐싱으로 반복 호출 최소화
 *
 * @property pluginManager 플러그인 매니저
 * @property defaultSpaceKey 기본 Confluence 스페이스 키
 * @property tokenBudget 도메인 지식 토큰 예산 (기본 1500)
 */
class DomainKnowledgeEnricher(
    private val pluginManager: PluginManager,
    private val defaultSpaceKey: String? = null,
    private val tokenBudget: Int = 1500
) : ContextEnricher {

    override val id: String = "domain-knowledge"
    override val name: String = "Domain Knowledge Enricher"
    override val priority: Int = 50 // 다른 enricher보다 먼저 실행

    // 코드 리뷰 관련 키워드 패턴
    private val codeReviewPatterns = listOf(
        Regex("(?i)(mr|merge request|pr|pull request)\\s*(리뷰|review|검토)"),
        Regex("(?i)코드\\s*(리뷰|검토|확인)"),
        Regex("(?i)review\\s*(this|code|changes)"),
        Regex("(?i)gitlab.*?(\\d+)")
    )

    override fun shouldEnrich(context: EnrichmentContext): Boolean {
        // Confluence 플러그인이 활성화되어 있는지 확인
        val confluencePlugin = pluginManager.get("confluence") as? ConfluencePlugin
        val hasConfluence = confluencePlugin?.enabled == true

        if (!hasConfluence) {
            logger.debug { "Confluence plugin not available, skipping enricher" }
            return false
        }

        // 코드 리뷰 관련 요청인지 확인
        val prompt = context.originalPrompt
        val isCodeReview = codeReviewPatterns.any { it.containsMatchIn(prompt) }

        if (isCodeReview) {
            logger.debug { "Code review request detected, will enrich with domain knowledge" }
            return true
        }

        // projectId가 있고 특정 도메인 용어가 포함된 경우
        val hasProjectContext = context.metadata.projectId != null
        val hasDomainTerms = extractKeywords(prompt).isNotEmpty()

        return hasProjectContext && hasDomainTerms
    }

    override suspend fun enrich(context: EnrichmentContext): EnrichmentContext {
        var enrichedContext = context
        val prompt = context.originalPrompt
        val keywords = extractKeywords(prompt)

        logger.debug { "Enriching with domain knowledge, keywords: $keywords" }

        // Confluence에서 도메인 지식 조회
        val confluenceContext = enrichWithConfluence(
            spaceKey = context.metadata.projectId ?: defaultSpaceKey,
            keywords = keywords
        )

        if (confluenceContext != null) {
            enrichedContext = enrichedContext.withAddedContext(
                enricherId = id,
                enricherType = EnricherType.PROJECT,
                content = confluenceContext,
                metadata = mapOf(
                    "source" to "confluence",
                    "keywords" to keywords
                )
            )
        }

        return enrichedContext
    }

    /**
     * Confluence에서 도메인 지식 조회
     */
    private fun enrichWithConfluence(
        spaceKey: String?,
        keywords: List<String>
    ): String? {
        if (spaceKey == null || keywords.isEmpty()) return null

        val confluence = pluginManager.get("confluence") as? ConfluencePlugin ?: return null
        if (!confluence.enabled) return null

        return try {
            val result = confluence.getReviewContext(
                spaceKey = spaceKey,
                keywords = keywords,
                tokenBudget = tokenBudget
            )

            if (result.success) {
                val reviewContext = result.data as? ReviewContext
                if (reviewContext != null) {
                    confluence.formatReviewContext(reviewContext)
                } else null
            } else {
                logger.warn { "Failed to get Confluence context: ${result.error}" }
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error enriching with Confluence" }
            null
        }
    }

    /**
     * 프롬프트에서 키워드 추출
     */
    private fun extractKeywords(prompt: String): List<String> {
        // 기본 키워드 추출 (명사, 영어 단어)
        val words = prompt
            .split(Regex("[\\s,.!?;:()\\[\\]{}\"'<>]+"))
            .filter { word ->
                word.length >= 2 &&
                !word.matches(Regex("^(the|a|an|is|are|was|were|be|been|have|has|had|do|does|did|will|would|could|should|may|might|must|shall|can|to|of|in|for|on|with|at|by|from|or|and|but|if|then|else|when|where|which|who|what|how|why|this|that|these|those|it|its|my|your|our|their|his|her|he|she|they|we|you|I|me|us|them|him|our|the|이|그|저|것|수|등|및|또|더|를|을|에|의|가|은|는)$", RegexOption.IGNORE_CASE))
            }
            .map { it.lowercase() }
            .distinct()

        // 한국어 명사 추출 (간단한 휴리스틱)
        val koreanNouns = Regex("[가-힣]{2,}")
            .findAll(prompt)
            .map { it.value }
            .filter { it.length >= 2 }
            .toList()

        return (words + koreanNouns).distinct().take(10)
    }
}
