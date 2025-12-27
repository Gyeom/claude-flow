package ai.claudeflow.core.rag

import ai.claudeflow.core.storage.repository.FeedbackRepository
import ai.claudeflow.core.storage.repository.ExecutionRepository
import ai.claudeflow.core.storage.FeedbackRecord
import ai.claudeflow.core.storage.GitLabFeedbackType
import mu.KotlinLogging
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * 피드백 기반 학습 서비스
 *
 * 사용자 피드백(thumbsup/thumbsdown)을 분석하여 라우팅 결정을 개선
 */
class FeedbackLearningService(
    private val feedbackRepository: FeedbackRepository,
    private val executionRepository: ExecutionRepository,
    private val conversationVectorService: ConversationVectorService? = null,
    private val embeddingService: EmbeddingService? = null
) {
    // 메모리 캐시: 사용자별 에이전트 선호도
    private val preferenceCache = ConcurrentHashMap<String, UserAgentPreferences>()
    private val cacheExpiryMinutes = 30L

    /**
     * 피드백 기록 및 학습
     */
    fun recordFeedback(
        executionId: String,
        userId: String,
        isPositive: Boolean
    ): Boolean {
        return try {
            // 실행 기록 조회
            val execution = executionRepository.findById(executionId) ?: return false
            val agentId = execution.agentId

            // 벡터 DB 피드백 점수 업데이트
            val score = if (isPositive) 1.0 else -1.0
            conversationVectorService?.updateFeedbackScore(executionId, score)

            // 사용자 선호도 캐시 업데이트
            val userKey = userId
            val preferences = preferenceCache.getOrPut(userKey) {
                UserAgentPreferences(userId = userId)
            }

            preferences.recordFeedback(agentId, isPositive)

            // 캐시 만료 시간 갱신
            preferences.lastUpdated = Instant.now()

            logger.debug { "Recorded feedback for user $userId, agent $agentId: positive=$isPositive" }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to record feedback for execution $executionId" }
            false
        }
    }

    /**
     * 사용자별 에이전트 선호도 점수 조회
     */
    fun getAgentPreferences(userId: String): Map<String, Float> {
        // 캐시 확인
        val cached = preferenceCache[userId]
        if (cached != null && !cached.isExpired(cacheExpiryMinutes)) {
            return cached.calculatePreferenceScores()
        }

        // DB에서 로드
        return loadPreferencesFromDb(userId)
    }

    /**
     * 라우팅 점수 조정
     *
     * 피드백 기반으로 에이전트 선택 점수를 조정
     */
    fun adjustRoutingScore(
        userId: String,
        agentId: String,
        baseScore: Float,
        queryEmbedding: FloatArray? = null
    ): Float {
        val preferences = getAgentPreferences(userId)
        val preferenceScore = preferences[agentId] ?: 0.5f

        // 조정 알고리즘:
        // - 성공률 > 0.7: boost = 1.0 + (success_rate - 0.5) × 0.2
        // - 성공률 < 0.3: penalty = 1.0 - (0.5 - success_rate) × 0.3
        val adjustmentFactor = when {
            preferenceScore > 0.7f -> 1.0f + (preferenceScore - 0.5f) * 0.2f
            preferenceScore < 0.3f -> 1.0f - (0.5f - preferenceScore) * 0.3f
            else -> 1.0f
        }

        val adjustedScore = (baseScore * adjustmentFactor).coerceIn(0f, 1f)

        logger.debug {
            "Adjusted routing score for agent $agentId: $baseScore -> $adjustedScore (preference: $preferenceScore)"
        }

        return adjustedScore
    }

    /**
     * 유사 쿼리 기반 에이전트 추천
     *
     * 유사한 과거 질문에서 긍정 피드백을 받은 에이전트를 추천
     */
    fun recommendAgentFromSimilar(
        query: String,
        userId: String?,
        topK: Int = 5
    ): AgentRecommendation? {
        if (conversationVectorService == null) return null

        // 유사 대화 검색
        val similar = conversationVectorService.findSimilarConversations(
            query = query,
            userId = userId,
            topK = topK * 2,  // 더 많이 검색하여 필터링
            minScore = 0.7f   // 높은 유사도만
        )

        if (similar.isEmpty()) return null

        // 피드백 기반 집계
        val agentScores = mutableMapOf<String, AgentScoreAccumulator>()

        for (conv in similar) {
            val feedback = feedbackRepository.findByExecutionId(conv.executionId)
            val positive = feedback.count { FeedbackRecord.isPositiveReaction(it.reaction) }
            val negative = feedback.count { FeedbackRecord.isNegativeReaction(it.reaction) }

            agentScores.getOrPut(conv.agentId) { AgentScoreAccumulator() }.also {
                it.addSample(conv.score, positive, negative)
            }
        }

        // 최고 점수 에이전트 선택
        val best = agentScores.entries
            .filter { it.value.sampleCount >= 2 }  // 최소 2개 샘플
            .maxByOrNull { it.value.combinedScore }
            ?: return null

        return AgentRecommendation(
            agentId = best.key,
            confidence = best.value.combinedScore,
            sampleCount = best.value.sampleCount,
            successRate = best.value.successRate,
            reason = "유사 질문 ${best.value.sampleCount}개 분석 결과"
        )
    }

    /**
     * 학습 통계 조회
     */
    fun getLearningStats(userId: String? = null): LearningStats {
        val cached = if (userId != null) {
            listOfNotNull(preferenceCache[userId])
        } else {
            preferenceCache.values.toList()
        }

        val totalUsers = cached.size
        val totalFeedback = cached.sumOf { it.totalFeedback }
        val positiveRate = if (totalFeedback > 0) {
            cached.sumOf { it.positiveCount }.toDouble() / totalFeedback
        } else 0.0

        return LearningStats(
            totalUsers = totalUsers,
            totalFeedback = totalFeedback,
            positiveRate = positiveRate,
            cachedPreferences = cached.size,
            lastUpdated = cached.maxOfOrNull { it.lastUpdated }?.toString()
        )
    }

    /**
     * 캐시 초기화
     */
    fun clearCache(userId: String? = null) {
        if (userId != null) {
            preferenceCache.remove(userId)
        } else {
            preferenceCache.clear()
        }
        logger.info { "Cleared feedback cache${userId?.let { " for user $it" } ?: ""}" }
    }

    // ==================== GitLab 피드백 학습 ====================

    /**
     * GitLab 피드백 학습 (이모지)
     *
     * MR 리뷰 코멘트에 대한 이모지 피드백(👍/👎)을 학습
     * 향후 유사한 MR 리뷰 시 긍정 피드백 받은 패턴 우선
     *
     * @param mrContext MR 제목 + 요약 (쿼리로 사용)
     * @param reviewContent AI 리뷰 내용 (응답으로 사용)
     * @param feedbackType POSITIVE/NEGATIVE
     * @param userId GitLab 사용자 ID
     */
    fun learnFromGitLabFeedback(
        mrContext: String,
        reviewContent: String,
        feedbackType: GitLabFeedbackType,
        userId: String
    ) {
        try {
            val isPositive = feedbackType == GitLabFeedbackType.POSITIVE

            // 사용자 선호도 캐시 업데이트
            val preferences = preferenceCache.getOrPut(userId) {
                UserAgentPreferences(userId = userId)
            }
            preferences.recordFeedback("code-reviewer", isPositive)
            preferences.lastUpdated = Instant.now()

            logger.info { "Learned from GitLab emoji feedback: userId=$userId, type=$feedbackType" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to learn from GitLab feedback" }
        }
    }

    private fun loadPreferencesFromDb(userId: String): Map<String, Float> {
        // 최근 30일 실행 기록 조회
        val recentExecutions = executionRepository.findByUserId(userId)
            .filter {
                ChronoUnit.DAYS.between(it.createdAt, Instant.now()) <= 30
            }

        if (recentExecutions.isEmpty()) return emptyMap()

        val agentStats = mutableMapOf<String, Pair<Int, Int>>()  // positive, total

        for (execution in recentExecutions) {
            val feedback = feedbackRepository.findByExecutionId(execution.id)
            val positive = feedback.any { FeedbackRecord.isPositiveReaction(it.reaction) }
            val negative = feedback.any { FeedbackRecord.isNegativeReaction(it.reaction) }

            if (positive || negative) {
                val current = agentStats.getOrDefault(execution.agentId, Pair(0, 0))
                agentStats[execution.agentId] = Pair(
                    current.first + if (positive) 1 else 0,
                    current.second + 1
                )
            }
        }

        // 캐시에 저장
        val preferences = UserAgentPreferences(userId)
        for ((agentId, stats) in agentStats) {
            repeat(stats.first) { preferences.recordFeedback(agentId, true) }
            repeat(stats.second - stats.first) { preferences.recordFeedback(agentId, false) }
        }
        preferenceCache[userId] = preferences

        return preferences.calculatePreferenceScores()
    }
}

/**
 * 사용자별 에이전트 선호도 (메모리 캐시)
 */
class UserAgentPreferences(
    val userId: String,
    var lastUpdated: Instant = Instant.now()
) {
    private val agentFeedback = ConcurrentHashMap<String, Pair<Int, Int>>()  // positive, total

    val totalFeedback: Int
        get() = agentFeedback.values.sumOf { it.second }

    val positiveCount: Int
        get() = agentFeedback.values.sumOf { it.first }

    fun recordFeedback(agentId: String, isPositive: Boolean) {
        val current = agentFeedback.getOrDefault(agentId, Pair(0, 0))
        agentFeedback[agentId] = Pair(
            current.first + if (isPositive) 1 else 0,
            current.second + 1
        )
        lastUpdated = Instant.now()
    }

    fun calculatePreferenceScores(): Map<String, Float> {
        return agentFeedback.mapValues { (_, stats) ->
            if (stats.second > 0) {
                stats.first.toFloat() / stats.second
            } else {
                0.5f  // 기본값
            }
        }
    }

    fun isExpired(expiryMinutes: Long): Boolean {
        return ChronoUnit.MINUTES.between(lastUpdated, Instant.now()) > expiryMinutes
    }
}

/**
 * 에이전트 점수 누적기
 */
private class AgentScoreAccumulator {
    var sampleCount = 0
    var totalSimilarity = 0f
    var positiveCount = 0
    var negativeCount = 0

    fun addSample(similarity: Float, positive: Int, negative: Int) {
        sampleCount++
        totalSimilarity += similarity
        positiveCount += positive
        negativeCount += negative
    }

    val successRate: Float
        get() = if (positiveCount + negativeCount > 0) {
            positiveCount.toFloat() / (positiveCount + negativeCount)
        } else 0.5f

    val combinedScore: Float
        get() {
            val avgSimilarity = if (sampleCount > 0) totalSimilarity / sampleCount else 0f
            return avgSimilarity * 0.3f + successRate * 0.7f
        }
}

/**
 * 에이전트 추천 결과
 */
data class AgentRecommendation(
    val agentId: String,
    val confidence: Float,
    val sampleCount: Int,
    val successRate: Float,
    val reason: String
)

/**
 * 학습 통계
 */
data class LearningStats(
    val totalUsers: Int,
    val totalFeedback: Int,
    val positiveRate: Double,
    val cachedPreferences: Int,
    val lastUpdated: String?
)
