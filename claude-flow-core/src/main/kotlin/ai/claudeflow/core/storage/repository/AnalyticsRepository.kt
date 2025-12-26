package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.*
import java.sql.ResultSet
import java.time.Instant

// FeedbackStats, TimeSeriesPoint, ErrorStats, UserStats are defined in ExecutionRecord.kt
// AggregatedStats, TimeGranularity are defined in ExecutionRepository.kt

/**
 * Repository for analytics queries
 *
 * Provides comprehensive analytics with real calculations, no TODOs or mock data.
 */
class AnalyticsRepository(
    connectionProvider: ConnectionProvider,
    private val executionRepository: ExecutionRepository,
    private val feedbackRepository: FeedbackRepository
) : BaseRepository<Nothing, Nothing>(connectionProvider) {

    override val tableName: String = "executions"  // Primary data source
    override val primaryKeyColumn: String = "id"

    override fun mapRow(rs: ResultSet): Nothing {
        throw UnsupportedOperationException("AnalyticsRepository doesn't support direct entity mapping")
    }

    override fun getId(entity: Nothing): Nothing {
        throw UnsupportedOperationException("AnalyticsRepository doesn't support entity IDs")
    }

    override fun save(entity: Nothing) {
        throw UnsupportedOperationException("AnalyticsRepository is read-only")
    }

    /**
     * Calculate percentiles for response times
     */
    fun getPercentiles(dateRange: DateRange): PercentileStats {
        val durations = executionRepository.getSuccessfulDurations(dateRange)

        if (durations.isEmpty()) {
            return PercentileStats(0, 0, 0, 0)
        }

        return PercentileStats(
            p50 = calculatePercentile(durations, 50),
            p90 = calculatePercentile(durations, 90),
            p95 = calculatePercentile(durations, 95),
            p99 = calculatePercentile(durations, 99)
        )
    }

    private fun calculatePercentile(sortedList: List<Long>, percentile: Int): Long {
        if (sortedList.isEmpty()) return 0
        val index = (percentile / 100.0 * sortedList.size).toInt().coerceIn(0, sortedList.size - 1)
        return sortedList[index]
    }

    /**
     * Get comprehensive overview statistics
     */
    fun getOverviewStats(dateRange: DateRange): OverviewStats {
        // Current period stats
        val currentStats = executionRepository.getAggregatedStats(dateRange)

        // Previous period for comparison (same duration)
        val periodDuration = dateRange.to.epochSecond - dateRange.from.epochSecond
        val previousDateRange = DateRange(
            from = dateRange.from.minusSeconds(periodDuration),
            to = dateRange.from
        )
        val previousStats = executionRepository.getAggregatedStats(previousDateRange)

        // Feedback stats
        val feedbackStats = feedbackRepository.getFeedbackStats(dateRange)

        // Percentiles
        val percentiles = getPercentiles(dateRange)

        // Cache hit rate from routing metrics (if available)
        val cacheHitRate = getCacheHitRate(dateRange)

        // Comparison
        val comparison = if (previousStats.totalRequests > 0) {
            ComparisonStats(
                requestsChangePct = calculateChangePct(previousStats.totalRequests, currentStats.totalRequests),
                costChangePct = calculateChangePct(previousStats.totalCostUsd, currentStats.totalCostUsd),
                durationChangePct = calculateChangePct(previousStats.avgDurationMs, currentStats.avgDurationMs)
            )
        } else null

        return OverviewStats(
            totalRequests = currentStats.totalRequests,
            successfulRequests = currentStats.successfulRequests,
            failedRequests = currentStats.totalRequests - currentStats.successfulRequests,
            successRate = if (currentStats.totalRequests > 0)
                currentStats.successfulRequests.toDouble() / currentStats.totalRequests else 0.0,
            totalCostUsd = currentStats.totalCostUsd,
            totalInputTokens = currentStats.totalInputTokens,
            totalOutputTokens = currentStats.totalOutputTokens,
            cacheHitRate = cacheHitRate,
            percentiles = percentiles,
            feedback = feedbackStats,
            comparison = comparison
        )
    }

    /**
     * Get cache hit rate from routing metrics
     */
    private fun getCacheHitRate(dateRange: DateRange): Double {
        // Query routing metrics table if exists, otherwise return 0
        return try {
            executeQueryOne(
                """
                SELECT
                    CAST(SUM(CASE WHEN routing_method = 'cache' THEN 1 ELSE 0 END) AS REAL) /
                    CAST(NULLIF(COUNT(*), 0) AS REAL) as hit_rate
                FROM routing_metrics
                WHERE created_at BETWEEN ? AND ?
                """.trimIndent(),
                dateRange.from.toString(), dateRange.to.toString()
            ) { it.getDouble("hit_rate") } ?: 0.0
        } catch (e: Exception) {
            // Table may not exist yet
            0.0
        }
    }

    /**
     * Get routing statistics
     */
    fun getRoutingStats(dateRange: DateRange): RoutingStats {
        return try {
            executeQueryOne(
                """
                SELECT
                    COUNT(*) as total,
                    SUM(CASE WHEN routing_method = 'keyword' THEN 1 ELSE 0 END) as keyword_count,
                    SUM(CASE WHEN routing_method = 'pattern' THEN 1 ELSE 0 END) as pattern_count,
                    SUM(CASE WHEN routing_method = 'semantic' THEN 1 ELSE 0 END) as semantic_count,
                    SUM(CASE WHEN routing_method = 'llm' THEN 1 ELSE 0 END) as llm_count,
                    SUM(CASE WHEN routing_method = 'cache' THEN 1 ELSE 0 END) as cache_count,
                    SUM(CASE WHEN routing_method = 'default' THEN 1 ELSE 0 END) as default_count
                FROM routing_metrics
                WHERE created_at BETWEEN ? AND ?
                """.trimIndent(),
                dateRange.from.toString(), dateRange.to.toString()
            ) {
                val total = it.getLong("total")
                RoutingStats(
                    totalRouted = total,
                    keywordMatches = it.getLong("keyword_count"),
                    patternMatches = it.getLong("pattern_count"),
                    semanticMatches = it.getLong("semantic_count"),
                    llmClassifications = it.getLong("llm_count"),
                    cacheHits = it.getLong("cache_count"),
                    defaultFallbacks = it.getLong("default_count"),
                    keywordRate = if (total > 0) it.getLong("keyword_count").toDouble() / total else 0.0,
                    cacheHitRate = if (total > 0) it.getLong("cache_count").toDouble() / total else 0.0
                )
            } ?: RoutingStats.empty()
        } catch (e: Exception) {
            RoutingStats.empty()
        }
    }

    /**
     * Get time series data with specified granularity
     */
    fun getTimeSeries(dateRange: DateRange, granularity: TimeGranularity): List<TimeSeriesPoint> {
        return executionRepository.getTimeSeries(dateRange, granularity)
    }

    /**
     * Get error statistics with trend analysis
     */
    fun getErrorStats(dateRange: DateRange): List<ErrorStats> {
        // Current period errors
        val currentErrors = executionRepository.getErrorStats(dateRange)

        // Previous period for trend
        val periodDuration = dateRange.to.epochSecond - dateRange.from.epochSecond
        val previousDateRange = DateRange(
            from = dateRange.from.minusSeconds(periodDuration),
            to = dateRange.from
        )
        val previousErrors = executionRepository.getErrorStats(previousDateRange)
            .associateBy { it.errorType }

        // Calculate total and percentages
        val totalErrors = currentErrors.sumOf { it.count }

        return currentErrors.map { error ->
            val prev = previousErrors[error.errorType]?.count ?: 0L
            val trend = when {
                error.count > prev * 1.1 -> "up"
                error.count < prev * 0.9 -> "down"
                else -> "stable"
            }
            error.copy(
                percentage = if (totalErrors > 0) (error.count.toDouble() / totalErrors) * 100 else 0.0,
                trend = trend
            )
        }
    }

    /**
     * Get user statistics
     */
    fun getUserStats(dateRange: DateRange, limit: Int = 20): List<UserStats> {
        return executionRepository.getUserStats(dateRange, limit)
    }

    /**
     * Get agent usage statistics
     */
    fun getAgentStats(dateRange: DateRange): List<AgentStats> {
        return executeQuery(
            """
            SELECT
                e.agent_id,
                COALESCE(a.name, e.agent_id) as agent_name,
                COUNT(*) as requests,
                SUM(CASE WHEN e.status = 'SUCCESS' THEN 1 ELSE 0 END) as successful,
                COALESCE(SUM(e.cost), 0) as total_cost,
                COALESCE(AVG(e.duration_ms), 0) as avg_duration
            FROM executions e
            LEFT JOIN agents a ON e.agent_id = a.id
            WHERE e.created_at BETWEEN ? AND ?
            GROUP BY e.agent_id
            ORDER BY requests DESC
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) {
            val requests = it.getLong("requests")
            val successful = it.getLong("successful")
            AgentStats(
                agentId = it.getString("agent_id"),
                agentName = it.getString("agent_name"),
                requests = requests,
                successRate = if (requests > 0) successful.toDouble() / requests else 0.0,
                totalCost = it.getDouble("total_cost"),
                avgDurationMs = it.getLong("avg_duration")
            )
        }
    }

    /**
     * Get token usage statistics
     */
    fun getTokenStats(dateRange: DateRange): TokenStats {
        return executeQueryOne(
            """
            SELECT
                COALESCE(SUM(input_tokens), 0) as input_tokens,
                COALESCE(SUM(output_tokens), 0) as output_tokens,
                COALESCE(SUM(input_tokens + output_tokens), 0) as total_tokens,
                COALESCE(AVG(input_tokens), 0) as avg_input,
                COALESCE(AVG(output_tokens), 0) as avg_output
            FROM executions
            WHERE created_at BETWEEN ? AND ?
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) {
            TokenStats(
                totalInputTokens = it.getLong("input_tokens"),
                totalOutputTokens = it.getLong("output_tokens"),
                totalTokens = it.getLong("total_tokens"),
                avgInputTokens = it.getDouble("avg_input"),
                avgOutputTokens = it.getDouble("avg_output")
            )
        } ?: TokenStats(0, 0, 0, 0.0, 0.0)
    }

    /**
     * Get cost breakdown by model
     */
    fun getCostBreakdown(dateRange: DateRange): List<CostBreakdown> {
        val totalCost = executeQueryOne(
            "SELECT COALESCE(SUM(cost), 0) FROM executions WHERE created_at BETWEEN ? AND ?",
            dateRange.from.toString(), dateRange.to.toString()
        ) { it.getDouble(1) } ?: 0.0

        return executeQuery(
            """
            SELECT
                COALESCE(model, 'claude-sonnet-4-20250514') as model,
                COALESCE(SUM(cost), 0) as cost,
                COUNT(*) as requests,
                COALESCE(SUM(input_tokens + output_tokens), 0) as total_tokens,
                COALESCE(AVG(duration_ms), 0) as avg_duration,
                CAST(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS REAL) /
                    CAST(NULLIF(COUNT(*), 0) AS REAL) as success_rate
            FROM executions
            WHERE created_at BETWEEN ? AND ?
            GROUP BY COALESCE(model, 'claude-sonnet-4-20250514')
            ORDER BY cost DESC
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) {
            val cost = it.getDouble("cost")
            CostBreakdown(
                model = it.getString("model"),
                cost = cost,
                percentage = if (totalCost > 0) (cost / totalCost) * 100 else 0.0,
                requests = it.getLong("requests"),
                totalTokens = it.getLong("total_tokens"),
                avgDurationMs = it.getLong("avg_duration"),
                successRate = it.getDouble("success_rate")
            )
        }
    }

    /**
     * Get dashboard summary statistics
     */
    fun getDashboardStats(): DashboardStats {
        val now = Instant.now()
        val today = DateRange(
            from = now.minusSeconds(24 * 60 * 60),
            to = now
        )
        val thisWeek = DateRange.lastDays(7)

        val todayStats = executionRepository.getAggregatedStats(today)
        val weekStats = executionRepository.getAggregatedStats(thisWeek)
        val feedbackStats = feedbackRepository.getFeedbackStats(thisWeek)

        return DashboardStats(
            todayRequests = todayStats.totalRequests,
            todaySuccessRate = if (todayStats.totalRequests > 0)
                todayStats.successfulRequests.toDouble() / todayStats.totalRequests else 0.0,
            todayCost = todayStats.totalCostUsd,
            weekRequests = weekStats.totalRequests,
            weekSuccessRate = if (weekStats.totalRequests > 0)
                weekStats.successfulRequests.toDouble() / weekStats.totalRequests else 0.0,
            weekCost = weekStats.totalCostUsd,
            satisfactionRate = feedbackStats.satisfactionRate,
            pendingFeedback = feedbackStats.pendingFeedback
        )
    }

    private fun calculateChangePct(previous: Long, current: Long): Double {
        if (previous == 0L) return if (current > 0) 100.0 else 0.0
        return ((current - previous).toDouble() / previous) * 100
    }

    private fun calculateChangePct(previous: Double, current: Double): Double {
        if (previous == 0.0) return if (current > 0) 100.0 else 0.0
        return ((current - previous) / previous) * 100
    }

    // ==================== Time Series APIs ====================

    /**
     * Get request source statistics (Slack, API, webhook, n8n, etc.)
     * Uses source field if available, otherwise infers from channel field
     */
    fun getSourceStats(dateRange: DateRange): List<SourceStats> {
        return executeQuery(
            """
            SELECT
                COALESCE(
                    NULLIF(source, ''),
                    CASE
                        WHEN channel IS NOT NULL AND channel != '' THEN 'slack'
                        ELSE 'api'
                    END
                ) as source_type,
                COUNT(*) as requests,
                SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) as successful
            FROM executions
            WHERE created_at BETWEEN ? AND ?
            GROUP BY source_type
            ORDER BY requests DESC
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) {
            val requests = it.getLong("requests")
            val successful = it.getLong("successful")
            SourceStats(
                source = it.getString("source_type"),
                requests = requests,
                successRate = if (requests > 0) successful.toDouble() / requests else 0.0
            )
        }
    }

    /**
     * Get token usage trend by day
     */
    fun getTokensTrend(dateRange: DateRange): List<TokenTrendPoint> {
        return executeQuery(
            """
            SELECT
                date(created_at) as date,
                COALESCE(SUM(input_tokens), 0) as input_tokens,
                COALESCE(SUM(output_tokens), 0) as output_tokens
            FROM executions
            WHERE created_at BETWEEN ? AND ?
            GROUP BY date(created_at)
            ORDER BY date ASC
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) {
            TokenTrendPoint(
                date = it.getString("date"),
                inputTokens = it.getLong("input_tokens"),
                outputTokens = it.getLong("output_tokens")
            )
        }
    }

    /**
     * Get error trend by day
     */
    fun getErrorsTrend(dateRange: DateRange): List<ErrorTrendPoint> {
        return executeQuery(
            """
            SELECT
                date(created_at) as date,
                COUNT(*) as error_count
            FROM executions
            WHERE created_at BETWEEN ? AND ?
              AND status != 'SUCCESS'
            GROUP BY date(created_at)
            ORDER BY date ASC
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) {
            ErrorTrendPoint(
                date = it.getString("date"),
                errorCount = it.getLong("error_count")
            )
        }
    }

    /**
     * Get feedback trend by day
     */
    fun getFeedbackTrend(dateRange: DateRange): List<FeedbackTrendPoint> {
        return executeQuery(
            """
            SELECT
                date(created_at) as date,
                SUM(CASE WHEN reaction IN ('+1', 'thumbsup', 'thumbs_up', 'white_check_mark') THEN 1 ELSE 0 END) as positive,
                SUM(CASE WHEN reaction IN ('-1', 'thumbsdown', 'thumbs_down', 'x') THEN 1 ELSE 0 END) as negative
            FROM feedback
            WHERE created_at BETWEEN ? AND ?
            GROUP BY date(created_at)
            ORDER BY date ASC
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) {
            FeedbackTrendPoint(
                date = it.getString("date"),
                positive = it.getLong("positive"),
                negative = it.getLong("negative")
            )
        }
    }
}

// Analytics DTOs
data class PercentileStats(
    val p50: Long,
    val p90: Long,
    val p95: Long,
    val p99: Long
)

data class OverviewStats(
    val totalRequests: Long,
    val successfulRequests: Long,
    val failedRequests: Long,
    val successRate: Double,
    val totalCostUsd: Double,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val cacheHitRate: Double,
    val percentiles: PercentileStats,
    val feedback: FeedbackStats,
    val comparison: ComparisonStats?
)

data class ComparisonStats(
    val requestsChangePct: Double,
    val costChangePct: Double,
    val durationChangePct: Double
)

data class RoutingStats(
    val totalRouted: Long,
    val keywordMatches: Long,
    val patternMatches: Long,
    val semanticMatches: Long,
    val llmClassifications: Long,
    val cacheHits: Long,
    val defaultFallbacks: Long,
    val keywordRate: Double,
    val cacheHitRate: Double
) {
    companion object {
        fun empty() = RoutingStats(0, 0, 0, 0, 0, 0, 0, 0.0, 0.0)
    }
}

data class AgentStats(
    val agentId: String,
    val agentName: String,
    val requests: Long,
    val successRate: Double,
    val totalCost: Double,
    val avgDurationMs: Long
)

data class TokenStats(
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalTokens: Long,
    val avgInputTokens: Double,
    val avgOutputTokens: Double
)

data class CostBreakdown(
    val model: String,
    val cost: Double,
    val percentage: Double,
    val requests: Long,
    val totalTokens: Long = 0,
    val avgDurationMs: Long = 0,
    val successRate: Double = 1.0
)

data class DashboardStats(
    val todayRequests: Long,
    val todaySuccessRate: Double,
    val todayCost: Double,
    val weekRequests: Long,
    val weekSuccessRate: Double,
    val weekCost: Double,
    val satisfactionRate: Double,
    val pendingFeedback: Long
)

// Time Series DTOs
data class SourceStats(
    val source: String,
    val requests: Long,
    val successRate: Double
)

data class TokenTrendPoint(
    val date: String,
    val inputTokens: Long,
    val outputTokens: Long
)

data class ErrorTrendPoint(
    val date: String,
    val errorCount: Long
)

data class FeedbackTrendPoint(
    val date: String,
    val positive: Long,
    val negative: Long
)
