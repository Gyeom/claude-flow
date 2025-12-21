package ai.claudeflow.api.rest

import ai.claudeflow.core.analytics.Analytics
import ai.claudeflow.core.analytics.DashboardStats
import ai.claudeflow.core.analytics.FeedbackAnalysis
import ai.claudeflow.core.analytics.TokenUsage
import ai.claudeflow.core.analytics.RoutingEfficiency
import ai.claudeflow.core.analytics.ProjectStat
import ai.claudeflow.core.storage.*
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * Analytics REST API
 */
@RestController
@RequestMapping("/api/v1/analytics")
class AnalyticsController(
    private val storage: Storage
) {
    private val analytics by lazy { Analytics(storage) }

    /**
     * 대시보드 통계
     * @param days 일 단위 기간 (기본값: 30)
     * @param hours 시간 단위 기간 (days보다 우선)
     */
    @GetMapping("/dashboard")
    fun getDashboard(
        @RequestParam(defaultValue = "30") days: Int,
        @RequestParam(required = false) hours: Int?
    ): Mono<ResponseEntity<DashboardStats>> = mono {
        val effectiveDays = hours?.let { it / 24.0 }?.toInt()?.coerceAtLeast(1) ?: days
        logger.info { "Get dashboard stats for ${hours?.let { "$it hours" } ?: "$days days"}" }
        val stats = analytics.getDashboard(effectiveDays, hours)
        ResponseEntity.ok(stats)
    }

    /**
     * 피드백 분석
     */
    @GetMapping("/feedback")
    fun getFeedback(@RequestParam(defaultValue = "30") days: Int): Mono<ResponseEntity<FeedbackAnalysis>> = mono {
        logger.info { "Get feedback analysis for $days days" }
        val analysis = analytics.getFeedbackAnalysis(days)
        ResponseEntity.ok(analysis)
    }

    /**
     * 토큰 사용량
     */
    @GetMapping("/tokens")
    fun getTokenUsage(@RequestParam(defaultValue = "30") days: Int): Mono<ResponseEntity<TokenUsage>> = mono {
        logger.info { "Get token usage for $days days" }
        val usage = analytics.getTokenUsage(days)
        ResponseEntity.ok(usage)
    }

    /**
     * 라우팅 효율
     */
    @GetMapping("/routing")
    fun getRoutingEfficiency(): Mono<ResponseEntity<RoutingEfficiency>> = mono {
        logger.info { "Get routing efficiency" }
        val efficiency = analytics.getRoutingEfficiency()
        ResponseEntity.ok(efficiency)
    }

    /**
     * 프로젝트별 통계
     */
    @GetMapping("/projects")
    fun getProjectStats(): Mono<ResponseEntity<List<ProjectStat>>> = mono {
        logger.info { "Get project stats" }
        val stats = analytics.getProjectStats()
        ResponseEntity.ok(stats)
    }

    /**
     * 기본 통계
     */
    @GetMapping("/stats")
    fun getStats(): Mono<ResponseEntity<StorageStatsDto>> = mono {
        val stats = storage.getStats()
        ResponseEntity.ok(StorageStatsDto(
            totalExecutions = stats.totalExecutions,
            successRate = stats.successRate,
            totalTokens = stats.totalTokens,
            avgDurationMs = stats.avgDurationMs,
            thumbsUp = stats.thumbsUp,
            thumbsDown = stats.thumbsDown
        ))
    }

    // ==================== Advanced Analytics APIs ====================

    /**
     * 종합 통계 (Overview)
     * P50/P90/P95/P99 백분위수 + 이전 기간 대비 변화율 포함
     */
    @GetMapping("/overview")
    fun getOverview(
        @RequestParam(defaultValue = "7") days: Int,
        @RequestParam(required = false) hours: Int?
    ): Mono<ResponseEntity<OverviewStats>> = mono {
        logger.info { "Get overview stats for ${hours?.let { "$it hours" } ?: "$days days"}" }
        val dateRange = if (hours != null) DateRange.lastHours(hours) else DateRange.lastDays(days)
        val stats = storage.analyticsRepository.getOverviewStats(dateRange)
        ResponseEntity.ok(stats)
    }

    /**
     * 백분위수 조회 (P50, P90, P95, P99)
     */
    @GetMapping("/percentiles")
    fun getPercentiles(
        @RequestParam(defaultValue = "7") days: Int
    ): Mono<ResponseEntity<PercentileStats>> = mono {
        logger.info { "Get percentiles for $days days" }
        val percentiles = storage.getPercentiles(days)
        ResponseEntity.ok(percentiles)
    }

    /**
     * 시계열 데이터
     */
    @GetMapping("/timeseries")
    fun getTimeSeries(
        @RequestParam(defaultValue = "7") days: Int,
        @RequestParam(defaultValue = "auto") granularity: String
    ): Mono<ResponseEntity<List<TimeSeriesPoint>>> = mono {
        val actualGranularity = when {
            granularity != "auto" -> granularity
            days <= 1 -> "hour"
            days <= 30 -> "day"
            else -> "week"
        }
        logger.info { "Get timeseries for $days days with $actualGranularity granularity" }
        val timeseries = storage.getTimeSeries(days, actualGranularity)
        ResponseEntity.ok(timeseries)
    }

    /**
     * 모델별 통계
     */
    @GetMapping("/models")
    fun getModelStats(
        @RequestParam(defaultValue = "7") days: Int
    ): Mono<ResponseEntity<List<ModelStats>>> = mono {
        logger.info { "Get model stats for $days days" }
        val stats = storage.getModelStats(days)
        ResponseEntity.ok(stats)
    }

    /**
     * 에러 통계
     */
    @GetMapping("/errors")
    fun getErrorStats(
        @RequestParam(defaultValue = "7") days: Int
    ): Mono<ResponseEntity<List<ErrorStats>>> = mono {
        logger.info { "Get error stats for $days days" }
        val stats = storage.getErrorStats(days)
        ResponseEntity.ok(stats)
    }

    /**
     * 사용자별 통계
     */
    @GetMapping("/users")
    fun getUserStats(
        @RequestParam(defaultValue = "7") days: Int,
        @RequestParam(defaultValue = "20") limit: Int
    ): Mono<ResponseEntity<List<UserStats>>> = mono {
        logger.info { "Get user stats for $days days, limit $limit" }
        val stats = storage.getUserStats(days, limit)
        ResponseEntity.ok(stats)
    }

    /**
     * 피드백 상세 통계
     */
    @GetMapping("/feedback/detailed")
    fun getFeedbackDetailed(
        @RequestParam(defaultValue = "7") days: Int
    ): Mono<ResponseEntity<FeedbackStats>> = mono {
        logger.info { "Get detailed feedback stats for $days days" }
        val since = java.time.Instant.now().minusSeconds(days * 24 * 60 * 60L).toString()
        val stats = storage.getFeedbackStats(since)
        ResponseEntity.ok(stats)
    }

    /**
     * Verified Feedback 통계
     * 요청자의 피드백만 실제 점수에 반영
     */
    @GetMapping("/feedback/verified")
    fun getVerifiedFeedback(
        @RequestParam(defaultValue = "7") days: Int
    ): Mono<ResponseEntity<VerifiedFeedbackStatsDto>> = mono {
        logger.info { "Get verified feedback stats for $days days" }
        val dateRange = DateRange.lastDays(days)
        val stats = storage.feedbackRepository.getVerifiedFeedbackStats(dateRange)
        ResponseEntity.ok(VerifiedFeedbackStatsDto(
            totalFeedback = stats.totalFeedback,
            verifiedFeedback = stats.verifiedFeedback,
            verifiedPositive = stats.verifiedPositive,
            verifiedNegative = stats.verifiedNegative,
            verificationRate = stats.verificationRate,
            satisfactionRate = stats.satisfactionRate
        ))
    }

    /**
     * 피드백 카테고리별 통계
     */
    @GetMapping("/feedback/categories")
    fun getFeedbackByCategory(
        @RequestParam(defaultValue = "7") days: Int
    ): Mono<ResponseEntity<List<FeedbackByCategoryDto>>> = mono {
        logger.info { "Get feedback by category for $days days" }
        val dateRange = DateRange.lastDays(days)

        // 카테고리별 피드백 집계
        val categories = listOf("feedback", "trigger", "action", "other")
        val result = categories.map { category ->
            val feedbackList = storage.feedbackRepository.findByCategory(category, dateRange)
            FeedbackByCategoryDto(
                category = category,
                count = feedbackList.size.toLong(),
                verifiedCount = feedbackList.count { it.isVerified }.toLong()
            )
        }
        ResponseEntity.ok(result)
    }

    // ==================== Time Series APIs ====================

    /**
     * 요청 소스별 통계 (Slack, API 등)
     */
    @GetMapping("/sources")
    fun getSourceStats(
        @RequestParam(defaultValue = "7") days: Int
    ): Mono<ResponseEntity<List<SourceStatsDto>>> = mono {
        logger.info { "Get source stats for $days days" }
        val dateRange = DateRange.lastDays(days)
        val stats = storage.analyticsRepository.getSourceStats(dateRange)
        ResponseEntity.ok(stats.map { SourceStatsDto(it.source, it.requests, it.successRate) })
    }

    /**
     * 토큰 사용량 시계열
     */
    @GetMapping("/tokens/trend")
    fun getTokensTrend(
        @RequestParam(defaultValue = "7") days: Int,
        @RequestParam(required = false) hours: Int?
    ): Mono<ResponseEntity<List<TokenTrendPointDto>>> = mono {
        logger.info { "Get tokens trend for ${hours?.let { "$it hours" } ?: "$days days"}" }
        val dateRange = if (hours != null) DateRange.lastHours(hours) else DateRange.lastDays(days)
        val trend = storage.analyticsRepository.getTokensTrend(dateRange)
        ResponseEntity.ok(trend.map { TokenTrendPointDto(it.date, it.inputTokens, it.outputTokens) })
    }

    /**
     * 에러 시계열
     */
    @GetMapping("/errors/trend")
    fun getErrorsTrend(
        @RequestParam(defaultValue = "7") days: Int
    ): Mono<ResponseEntity<List<ErrorTrendPointDto>>> = mono {
        logger.info { "Get errors trend for $days days" }
        val dateRange = DateRange.lastDays(days)
        val trend = storage.analyticsRepository.getErrorsTrend(dateRange)
        ResponseEntity.ok(trend.map { ErrorTrendPointDto(it.date, it.errorCount) })
    }

    /**
     * 피드백 시계열
     */
    @GetMapping("/feedback/trend")
    fun getFeedbackTrend(
        @RequestParam(defaultValue = "7") days: Int
    ): Mono<ResponseEntity<List<FeedbackTrendPointDto>>> = mono {
        logger.info { "Get feedback trend for $days days" }
        val dateRange = DateRange.lastDays(days)
        val trend = storage.analyticsRepository.getFeedbackTrend(dateRange)
        ResponseEntity.ok(trend.map { FeedbackTrendPointDto(it.date, it.positive, it.negative) })
    }
}

data class StorageStatsDto(
    val totalExecutions: Int,
    val successRate: Double,
    val totalTokens: Long,
    val avgDurationMs: Double,
    val thumbsUp: Int,
    val thumbsDown: Int
)

data class VerifiedFeedbackStatsDto(
    val totalFeedback: Long,
    val verifiedFeedback: Long,
    val verifiedPositive: Long,
    val verifiedNegative: Long,
    val verificationRate: Double,
    val satisfactionRate: Double
)

data class FeedbackByCategoryDto(
    val category: String,
    val count: Long,
    val verifiedCount: Long
)

// Time Series DTOs
data class SourceStatsDto(
    val source: String,
    val requests: Long,
    val successRate: Double
)

data class TokenTrendPointDto(
    val date: String,
    val inputTokens: Long,
    val outputTokens: Long
)

data class ErrorTrendPointDto(
    val date: String,
    val errorCount: Long
)

data class FeedbackTrendPointDto(
    val date: String,
    val positive: Long,
    val negative: Long
)
