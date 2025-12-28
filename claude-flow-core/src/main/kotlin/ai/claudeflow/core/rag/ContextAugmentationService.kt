package ai.claudeflow.core.rag

import ai.claudeflow.core.storage.UserContext
import ai.claudeflow.core.storage.UserRule
import ai.claudeflow.core.storage.repository.UserContextRepository
import ai.claudeflow.core.storage.repository.UserRuleRepository
import mu.KotlinLogging
import kotlin.system.measureTimeMillis

private val logger = KotlinLogging.logger {}

/**
 * RAG 기반 컨텍스트 증강 서비스
 *
 * 사용자 질문에 관련 과거 대화, 규칙, 요약을 자동으로 포함하여
 * Claude에게 더 풍부한 컨텍스트 제공
 */
class ContextAugmentationService(
    private val conversationVectorService: ConversationVectorService,
    private val userContextRepository: UserContextRepository? = null,
    private val userRuleRepository: UserRuleRepository? = null,
    private val fewShotInjectionService: FewShotInjectionService? = null
) {
    /**
     * 증강된 컨텍스트 빌드
     *
     * @param userId 사용자 ID
     * @param message 현재 메시지
     * @param options 증강 옵션
     * @return 증강된 컨텍스트
     */
    fun buildAugmentedContext(
        userId: String,
        message: String,
        options: AugmentationOptions = AugmentationOptions()
    ): AugmentedContext {
        val startTime = System.currentTimeMillis()
        var similarConversations = emptyList<SimilarConversation>()
        var retrievalTimeMs = 0L

        // 1. 유사 대화 검색
        if (options.includeSimilarConversations && conversationVectorService.isAvailable()) {
            retrievalTimeMs = measureTimeMillis {
                similarConversations = conversationVectorService.findSimilarConversations(
                    query = message,
                    userId = if (options.userScopedSearch) userId else null,
                    topK = options.maxSimilarConversations,
                    minScore = options.minSimilarityScore
                )
            }
            logger.debug { "Retrieved ${similarConversations.size} similar conversations in ${retrievalTimeMs}ms" }
        }

        // 2. 사용자 규칙 조회
        val userRules = if (options.includeUserRules) {
            userRuleRepository?.findByUserId(userId) ?: emptyList()
        } else emptyList()

        // 3. 사용자 요약 조회
        val userContext = userContextRepository?.findById(userId)
        val userSummary = if (options.includeUserSummary) {
            userContext?.summary
        } else null

        // 4. Few-shot 예제 조회 (관리자가 마킹한 우수 사례)
        val fewShotPrompt = if (options.includeFewShot && options.agentId != null && fewShotInjectionService != null) {
            try {
                fewShotInjectionService.buildFewShotPrompt(options.agentId, options.maxFewShotExamples)
            } catch (e: Exception) {
                logger.warn { "Failed to build few-shot prompt: ${e.message}" }
                null
            }
        } else null

        // 5. Anti-pattern 경고 조회 (자주 발생하는 문제 유형)
        val antiPatternPrompt = if (options.includeAntiPatterns && options.agentId != null && fewShotInjectionService != null) {
            try {
                fewShotInjectionService.buildAntiPatternPrompt(options.agentId)
            } catch (e: Exception) {
                logger.warn { "Failed to build anti-pattern prompt: ${e.message}" }
                null
            }
        } else null

        // 6. 시스템 프롬프트 구성
        val systemPrompt = buildSystemPrompt(
            similarConversations = similarConversations,
            userRules = userRules,
            userSummary = userSummary,
            userContext = userContext,
            fewShotPrompt = fewShotPrompt,
            antiPatternPrompt = antiPatternPrompt
        )

        val totalTimeMs = System.currentTimeMillis() - startTime
        logger.debug { "Context augmentation completed in ${totalTimeMs}ms" }

        return AugmentedContext(
            systemPrompt = systemPrompt,
            relevantConversations = similarConversations.map { conv ->
                RelevantConversation(
                    question = conv.prompt,
                    answer = conv.result,
                    similarity = conv.score,
                    agentId = conv.agentId,
                    createdAt = conv.createdAt
                )
            },
            userRules = userRules,
            userSummary = userSummary,
            fewShotPrompt = fewShotPrompt,
            antiPatternPrompt = antiPatternPrompt,
            metadata = AugmentationMetadata(
                retrievalTimeMs = retrievalTimeMs,
                totalTimeMs = totalTimeMs,
                totalCandidates = similarConversations.size,
                selectedCount = similarConversations.size,
                ragEnabled = conversationVectorService.isAvailable()
            )
        )
    }

    /**
     * 쿼리 확장 (HyDE - Hypothetical Document Embeddings 스타일)
     *
     * 사용자 질문을 가상의 답변으로 확장하여 검색 정확도 향상
     */
    fun expandQuery(query: String): String {
        // 간단한 확장: 질문에 예상 키워드 추가
        val expandedParts = mutableListOf(query)

        // 한국어 동의어/관련어 추가
        val koreanExpansions = mapOf(
            "코드 리뷰" to listOf("MR 검토", "PR 리뷰", "코드 검수"),
            "버그" to listOf("오류", "에러", "문제"),
            "배포" to listOf("릴리즈", "deploy", "출시"),
            "테스트" to listOf("QA", "검증", "test"),
            "성능" to listOf("퍼포먼스", "속도", "최적화")
        )

        for ((key, synonyms) in koreanExpansions) {
            if (query.contains(key)) {
                expandedParts.addAll(synonyms.take(2))
            }
        }

        return expandedParts.joinToString(" ")
    }

    /**
     * Re-ranking: 검색 결과 재순위화
     *
     * 기본 검색 결과를 추가 신호(피드백, 시간, 에이전트 일치)로 재정렬
     */
    fun rerankResults(
        results: List<SimilarConversation>,
        currentAgentId: String? = null,
        boostRecent: Boolean = true
    ): List<SimilarConversation> {
        return results.map { conv ->
            var adjustedScore = conv.score

            // 동일 에이전트 보너스
            if (currentAgentId != null && conv.agentId == currentAgentId) {
                adjustedScore *= 1.1f
            }

            // 최근 대화 보너스
            if (boostRecent) {
                try {
                    val createdAt = java.time.Instant.parse(conv.createdAt)
                    val hoursSince = java.time.Duration.between(createdAt, java.time.Instant.now()).toHours()
                    val recencyBoost = when {
                        hoursSince < 1 -> 1.2f
                        hoursSince < 24 -> 1.1f
                        hoursSince < 168 -> 1.05f  // 1주일
                        else -> 1.0f
                    }
                    adjustedScore *= recencyBoost
                } catch (e: Exception) {
                    // 날짜 파싱 실패 시 무시
                }
            }

            conv.copy(score = adjustedScore.coerceAtMost(1.0f))
        }.sortedByDescending { it.score }
    }

    private fun buildSystemPrompt(
        similarConversations: List<SimilarConversation>,
        userRules: List<UserRule>,
        userSummary: String?,
        userContext: UserContext?,
        fewShotPrompt: String? = null,
        antiPatternPrompt: String? = null
    ): String {
        return buildString {
            appendLine("## 사용자 컨텍스트")

            // 사용자 요약
            userSummary?.let {
                appendLine()
                appendLine("### 사용자 배경")
                appendLine(it)
            }

            // 사용자 정보
            userContext?.let { ctx ->
                appendLine()
                appendLine("### 사용자 정보")
                ctx.displayName?.let { appendLine("- 이름: $it") }
                appendLine("- 선호 언어: ${ctx.preferredLanguage}")
                ctx.domain?.let { appendLine("- 도메인: $it") }
                appendLine("- 총 상호작용: ${ctx.totalInteractions}회")
            }

            // 사용자 규칙
            if (userRules.isNotEmpty()) {
                appendLine()
                appendLine("### 적용할 규칙")
                userRules.forEach { rule ->
                    appendLine("- ${rule.rule}")
                }
            }

            // 관련 대화
            if (similarConversations.isNotEmpty()) {
                appendLine()
                appendLine("### 관련 이전 대화 (참고용)")
                similarConversations.forEachIndexed { index, conv ->
                    appendLine()
                    appendLine("**대화 ${index + 1}** (유사도: ${(conv.score * 100).toInt()}%, 에이전트: ${conv.agentId})")
                    appendLine("Q: ${conv.prompt.take(300)}")
                    appendLine("A: ${conv.result.take(500)}")
                }
            }

            // Few-shot 예제 (관리자가 마킹한 우수 응답 사례)
            fewShotPrompt?.let {
                appendLine()
                appendLine(it)
            }

            // Anti-pattern 경고 (자주 발생하는 문제 유형)
            antiPatternPrompt?.let {
                appendLine()
                appendLine(it)
            }
        }
    }
}

/**
 * 증강 옵션
 */
data class AugmentationOptions(
    val includeSimilarConversations: Boolean = true,
    val includeUserRules: Boolean = true,
    val includeUserSummary: Boolean = true,
    val includeFewShot: Boolean = true,
    val includeAntiPatterns: Boolean = true,
    val agentId: String? = null,
    val maxSimilarConversations: Int = 3,
    val maxFewShotExamples: Int = 3,
    val minSimilarityScore: Float = 0.65f,
    val userScopedSearch: Boolean = false  // true면 해당 사용자 대화만 검색
)

/**
 * 증강된 컨텍스트
 */
data class AugmentedContext(
    val systemPrompt: String,
    val relevantConversations: List<RelevantConversation>,
    val userRules: List<UserRule>,
    val userSummary: String?,
    val fewShotPrompt: String?,
    val antiPatternPrompt: String?,
    val metadata: AugmentationMetadata
)

/**
 * 관련 대화
 */
data class RelevantConversation(
    val question: String,
    val answer: String,
    val similarity: Float,
    val agentId: String,
    val createdAt: String
)

/**
 * 증강 메타데이터
 */
data class AugmentationMetadata(
    val retrievalTimeMs: Long,
    val totalTimeMs: Long,
    val totalCandidates: Int,
    val selectedCount: Int,
    val ragEnabled: Boolean
)
