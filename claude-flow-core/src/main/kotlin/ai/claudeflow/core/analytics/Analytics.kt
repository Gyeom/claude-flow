package ai.claudeflow.core.analytics

import ai.claudeflow.core.storage.Storage
import mu.KotlinLogging
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * Analytics 분석 모듈
 *
 * Claude Flow 스타일의 상세 분석 기능 제공:
 * - 사용자별 사용량 통계
 * - 에이전트별 성능 분석
 * - 시간대별 트렌드
 * - 프로젝트별 통계
 */
class Analytics(private val storage: Storage) {

    /**
     * 전체 대시보드 통계
     */
    fun getDashboard(days: Int = 30): DashboardStats {
        val stats = storage.getStats()
        val userStats = getUserStats(days)
        val agentStats = getAgentStats(days)
        val hourlyTrend = getHourlyTrend(days)

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
     * 사용자별 사용량 통계
     */
    fun getUserStats(days: Int = 30): List<UserStat> {
        val cutoff = Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toString()

        // Storage에 쿼리 메서드 추가 필요 - 현재는 간단 구현
        val contexts = storage.getAllUserContexts()
        return contexts.map { ctx ->
            UserStat(
                userId = ctx.userId,
                displayName = ctx.displayName,
                totalInteractions = ctx.totalInteractions,
                lastSeen = ctx.lastSeen.toString()
            )
        }.sortedByDescending { it.totalInteractions }
    }

    /**
     * 에이전트별 성능 통계
     */
    fun getAgentStats(days: Int = 30): List<AgentStat> {
        val agents = storage.getAllAgents()
        return agents.map { agent ->
            AgentStat(
                agentId = agent.id,
                agentName = agent.name,
                totalExecutions = 0,  // TODO: 실제 쿼리 구현
                successRate = 0.0,
                avgDurationMs = 0.0,
                avgTokens = 0,
                priority = agent.priority
            )
        }.sortedByDescending { it.priority }
    }

    /**
     * 시간대별 사용 트렌드
     */
    fun getHourlyTrend(days: Int = 7): List<HourlyTrend> {
        // 24시간 기준 트렌드 반환
        return (0..23).map { hour ->
            HourlyTrend(
                hour = hour,
                count = 0  // TODO: 실제 쿼리 구현
            )
        }
    }

    /**
     * 프로젝트별 통계
     */
    fun getProjectStats(): List<ProjectStat> {
        val agents = storage.getAllAgents()
        val projectIds = agents.mapNotNull { it.projectId }.distinct()

        return projectIds.map { projectId ->
            val projectAgents = agents.filter { it.projectId == projectId }
            ProjectStat(
                projectId = projectId,
                agentCount = projectAgents.size,
                totalExecutions = 0,  // TODO: 실제 쿼리 구현
                avgDurationMs = 0.0
            )
        }
    }

    /**
     * 피드백 분석
     */
    fun getFeedbackAnalysis(days: Int = 30): FeedbackAnalysis {
        val stats = storage.getStats()
        val total = stats.thumbsUp + stats.thumbsDown

        return FeedbackAnalysis(
            totalFeedback = total,
            positiveCount = stats.thumbsUp,
            negativeCount = stats.thumbsDown,
            positiveRate = if (total > 0) stats.thumbsUp.toDouble() / total else 0.0,
            negativeRate = if (total > 0) stats.thumbsDown.toDouble() / total else 0.0,
            satisfactionScore = calculateSatisfactionScore(stats.thumbsUp, stats.thumbsDown)
        )
    }

    /**
     * 토큰 사용량 분석
     */
    fun getTokenUsage(days: Int = 30): TokenUsage {
        val stats = storage.getStats()
        val costPerToken = 0.000003  // 예시 비용

        return TokenUsage(
            totalTokens = stats.totalTokens,
            inputTokens = 0,  // TODO: 별도 집계 필요
            outputTokens = 0,
            estimatedCost = stats.totalTokens * costPerToken,
            avgTokensPerRequest = if (stats.totalExecutions > 0)
                stats.totalTokens / stats.totalExecutions
            else 0
        )
    }

    /**
     * 에이전트 라우팅 효율성 분석
     */
    fun getRoutingEfficiency(): RoutingEfficiency {
        return RoutingEfficiency(
            keywordMatchRate = 0.0,  // TODO: 실제 구현
            semanticMatchRate = 0.0,
            llmFallbackRate = 0.0,
            defaultFallbackRate = 0.0,
            avgRoutingTimeMs = 0.0
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
    val semanticMatchRate: Double,
    val llmFallbackRate: Double,
    val defaultFallbackRate: Double,
    val avgRoutingTimeMs: Double
)
