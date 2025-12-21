package ai.claudeflow.core.analytics

import ai.claudeflow.core.storage.DateRange
import ai.claudeflow.core.storage.Storage
import ai.claudeflow.core.storage.repository.TimeGranularity
import mu.KotlinLogging
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * Analytics 분석 모듈 (완전 구현)
 *
 * Claude Flow 스타일의 상세 분석 기능 제공:
 * - 사용자별 사용량 통계
 * - 에이전트별 성능 분석
 * - 시간대별 트렌드
 * - 프로젝트별 통계
 * - 라우팅 효율성 분석
 */
class Analytics(private val storage: Storage) {

    private val analyticsRepository get() = storage.analyticsRepository
    private val executionRepository get() = storage.executionRepository
    private val feedbackRepository get() = storage.feedbackRepository
    private val agentRepository get() = storage.agentRepository

    /**
     * 전체 대시보드 통계
     * @param days 일 단위 기간
     * @param hours 시간 단위 기간 (null이 아니면 days 대신 사용)
     */
    fun getDashboard(days: Int = 30, hours: Int? = null): DashboardStats {
        val dateRange = if (hours != null) DateRange.lastHours(hours) else DateRange.lastDays(days)
        val effectiveDays = hours?.let { (it / 24).coerceAtLeast(1) } ?: days

        val stats = storage.getStats(dateRange)
        val userStats = getUserStats(effectiveDays, hours)
        val agentStats = getAgentStats(effectiveDays, hours)
        val hourlyTrend = getHourlyTrend(effectiveDays, hours)

        return DashboardStats(
            totalExecutions = stats.totalExecutions,
            successRate = stats.successRate,
            totalTokens = stats.totalTokens,
            avgDurationMs = stats.avgDurationMs,
            thumbsUp = stats.thumbsUp,
            thumbsDown = stats.thumbsDown,
            topUsers = userStats.take(10),
            topAgents = agentStats.take(5),
            hourlyTrend = hourlyTrend,
            satisfactionScore = calculateSatisfactionScore(stats.thumbsUp, stats.thumbsDown)
        )
    }

    /**
     * 사용자별 사용량 통계 (실제 쿼리 구현)
     */
    fun getUserStats(days: Int = 30, hours: Int? = null): List<UserStat> {
        val dateRange = if (hours != null) DateRange.lastHours(hours) else DateRange.lastDays(days)
        val userStats = analyticsRepository.getUserStats(dateRange, 50)

        return userStats.map { stat ->
            UserStat(
                userId = stat.userId,
                displayName = stat.displayName,
                totalInteractions = stat.totalRequests.toInt(),
                lastSeen = stat.lastSeen ?: ""
            )
        }.sortedByDescending { it.totalInteractions }
    }

    /**
     * 에이전트별 성능 통계 (실제 쿼리 구현)
     */
    fun getAgentStats(days: Int = 30, hours: Int? = null): List<AgentStat> {
        val dateRange = if (hours != null) DateRange.lastHours(hours) else DateRange.lastDays(days)
        val agentStats = analyticsRepository.getAgentStats(dateRange)
        val tokenStats = analyticsRepository.getTokenStats(dateRange)

        // 전체 평균 토큰 수 계산
        val totalRequests = agentStats.sumOf { it.requests }
        val avgTokensPerRequest = if (totalRequests > 0)
            (tokenStats.totalTokens / totalRequests).toInt()
        else 0

        return agentStats.map { stat ->
            val agent = agentRepository.findByIdAndProject(stat.agentId, null)
            AgentStat(
                agentId = stat.agentId,
                agentName = stat.agentName,
                totalExecutions = stat.requests.toInt(),
                successRate = stat.successRate,
                avgDurationMs = stat.avgDurationMs.toDouble(),
                avgTokens = avgTokensPerRequest,
                priority = agent?.priority ?: 0
            )
        }.sortedByDescending { it.totalExecutions }
    }

    /**
     * 시간대별 사용 트렌드 (실제 쿼리 구현)
     */
    fun getHourlyTrend(days: Int = 7, hours: Int? = null): List<HourlyTrend> {
        val dateRange = if (hours != null) DateRange.lastHours(hours) else DateRange.lastDays(days)
        val timeSeries = analyticsRepository.getTimeSeries(dateRange, TimeGranularity.HOUR)

        // 시간대별 집계
        val hourlyMap = mutableMapOf<Int, Long>()
        timeSeries.forEach { point ->
            // timestamp format: "YYYY-MM-DDTHH:00:00"
            val hour = try {
                point.timestamp.substringAfter("T").substringBefore(":").toInt()
            } catch (e: Exception) {
                0
            }
            hourlyMap[hour] = hourlyMap.getOrDefault(hour, 0L) + point.requests
        }

        return (0..23).map { hour ->
            HourlyTrend(
                hour = hour,
                count = hourlyMap.getOrDefault(hour, 0L).toInt()
            )
        }
    }

    /**
     * 프로젝트별 통계 (실제 쿼리 구현)
     */
    fun getProjectStats(): List<ProjectStat> {
        val dateRange = DateRange.lastDays(30)
        val agents = agentRepository.findAll()
        val projectIds = agents.mapNotNull { it.projectId }.distinct()

        // 에이전트별 통계를 먼저 가져옴
        val agentStats = analyticsRepository.getAgentStats(dateRange)
        val agentStatsMap = agentStats.associateBy { it.agentId }

        return projectIds.map { projectId ->
            val projectAgents = agents.filter { it.projectId == projectId }

            // 프로젝트별 통계 집계
            var totalExecutions = 0L
            var totalDuration = 0.0
            var count = 0

            projectAgents.forEach { agent ->
                agentStatsMap[agent.id]?.let { stat ->
                    totalExecutions += stat.requests
                    totalDuration += stat.avgDurationMs * stat.requests
                    count++
                }
            }

            ProjectStat(
                projectId = projectId,
                agentCount = projectAgents.size,
                totalExecutions = totalExecutions.toInt(),
                avgDurationMs = if (totalExecutions > 0) totalDuration / totalExecutions else 0.0
            )
        }
    }

    /**
     * 피드백 분석 (실제 쿼리 구현)
     */
    fun getFeedbackAnalysis(days: Int = 30): FeedbackAnalysis {
        val dateRange = DateRange.lastDays(days)
        val feedbackStats = feedbackRepository.getFeedbackStats(dateRange)
        val total = feedbackStats.positive + feedbackStats.negative

        return FeedbackAnalysis(
            totalFeedback = total.toInt(),
            positiveCount = feedbackStats.positive.toInt(),
            negativeCount = feedbackStats.negative.toInt(),
            positiveRate = if (total > 0) feedbackStats.positive.toDouble() / total else 0.0,
            negativeRate = if (total > 0) feedbackStats.negative.toDouble() / total else 0.0,
            satisfactionScore = calculateSatisfactionScore(
                feedbackStats.positive.toInt(),
                feedbackStats.negative.toInt()
            )
        )
    }

    /**
     * 토큰 사용량 분석 (실제 쿼리 구현)
     */
    fun getTokenUsage(days: Int = 30): TokenUsage {
        val dateRange = DateRange.lastDays(days)
        val tokenStats = analyticsRepository.getTokenStats(dateRange)
        val aggregatedStats = executionRepository.getAggregatedStats(dateRange)

        // Claude API 비용 계산 (Sonnet 4 기준)
        // Input: $3/1M tokens, Output: $15/1M tokens
        val inputCost = tokenStats.totalInputTokens * 0.000003
        val outputCost = tokenStats.totalOutputTokens * 0.000015
        val totalCost = inputCost + outputCost

        return TokenUsage(
            totalTokens = tokenStats.totalTokens,
            inputTokens = tokenStats.totalInputTokens,
            outputTokens = tokenStats.totalOutputTokens,
            estimatedCost = totalCost,
            avgTokensPerRequest = if (aggregatedStats.totalRequests > 0)
                tokenStats.totalTokens / aggregatedStats.totalRequests
            else 0
        )
    }

    /**
     * 에이전트 라우팅 효율성 분석 (실제 쿼리 구현)
     */
    fun getRoutingEfficiency(): RoutingEfficiency {
        val dateRange = DateRange.lastDays(7)
        val routingStats = analyticsRepository.getRoutingStats(dateRange)

        val total = routingStats.totalRouted.coerceAtLeast(1L)

        return RoutingEfficiency(
            keywordMatchRate = routingStats.keywordMatches.toDouble() / total,
            patternMatchRate = routingStats.patternMatches.toDouble() / total,
            semanticMatchRate = routingStats.semanticMatches.toDouble() / total,
            llmFallbackRate = routingStats.llmClassifications.toDouble() / total,
            defaultFallbackRate = routingStats.defaultFallbacks.toDouble() / total,
            cacheHitRate = routingStats.cacheHitRate,
            avgRoutingTimeMs = 0.0  // 별도 집계 필요
        )
    }

    /**
     * 캐시 성능 통계
     */
    fun getCacheStats(): CacheStats {
        val dateRange = DateRange.lastDays(7)
        val routingStats = analyticsRepository.getRoutingStats(dateRange)

        return CacheStats(
            totalCacheHits = routingStats.cacheHits,
            cacheHitRate = routingStats.cacheHitRate,
            estimatedSavingsMs = routingStats.cacheHits * 100  // 평균 100ms 절약 가정
        )
    }

    private fun calculateSatisfactionScore(thumbsUp: Int, thumbsDown: Int): Double {
        val total = thumbsUp + thumbsDown
        if (total == 0) return 0.0
        // NPS 스타일 계산: (긍정 - 부정) / 전체 * 100
        return ((thumbsUp - thumbsDown).toDouble() / total) * 100
    }
}

// ==================== DTOs ====================

data class DashboardStats(
    val totalExecutions: Int,
    val successRate: Double,
    val totalTokens: Long,
    val avgDurationMs: Double,
    val thumbsUp: Int,
    val thumbsDown: Int,
    val topUsers: List<UserStat>,
    val topAgents: List<AgentStat>,
    val hourlyTrend: List<HourlyTrend>,
    val satisfactionScore: Double
)

data class UserStat(
    val userId: String,
    val displayName: String?,
    val totalInteractions: Int,
    val lastSeen: String
)

data class AgentStat(
    val agentId: String,
    val agentName: String,
    val totalExecutions: Int,
    val successRate: Double,
    val avgDurationMs: Double,
    val avgTokens: Int,
    val priority: Int
)

data class HourlyTrend(
    val hour: Int,
    val count: Int
)

data class ProjectStat(
    val projectId: String,
    val agentCount: Int,
    val totalExecutions: Int,
    val avgDurationMs: Double
)

data class FeedbackAnalysis(
    val totalFeedback: Int,
    val positiveCount: Int,
    val negativeCount: Int,
    val positiveRate: Double,
    val negativeRate: Double,
    val satisfactionScore: Double
)

data class TokenUsage(
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCost: Double,
    val avgTokensPerRequest: Long
)

data class RoutingEfficiency(
    val keywordMatchRate: Double,
    val patternMatchRate: Double = 0.0,
    val semanticMatchRate: Double,
    val llmFallbackRate: Double,
    val defaultFallbackRate: Double,
    val cacheHitRate: Double = 0.0,
    val avgRoutingTimeMs: Double
)

data class CacheStats(
    val totalCacheHits: Long,
    val cacheHitRate: Double,
    val estimatedSavingsMs: Long
)
