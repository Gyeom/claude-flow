package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.*
import ai.claudeflow.core.storage.query.QueryBuilder
import java.sql.ResultSet
import java.time.Instant

// Re-use data classes from storage package
// TimeSeriesPoint, ErrorStats, UserStats are defined in ExecutionRecord.kt

/**
 * Repository for execution records
 */
class ExecutionRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<ExecutionRecord, String>(connectionProvider) {

    override val tableName: String = "executions"
    override val primaryKeyColumn: String = "id"

    override fun mapRow(rs: ResultSet): ExecutionRecord {
        return ExecutionRecord(
            id = rs.getString("id"),
            prompt = rs.getString("prompt"),
            result = rs.getString("result"),
            status = rs.getString("status"),
            agentId = rs.getString("agent_id"),
            projectId = rs.getString("project_id"),
            userId = rs.getString("user_id"),
            channel = rs.getString("channel"),
            threadTs = rs.getString("thread_ts"),
            replyTs = rs.getString("reply_ts"),
            durationMs = rs.getLong("duration_ms"),
            inputTokens = rs.getInt("input_tokens"),
            outputTokens = rs.getInt("output_tokens"),
            cost = rs.getObject("cost") as? Double,
            error = rs.getString("error"),
            model = rs.getString("model"),
            source = rs.getString("source"),
            routingMethod = rs.getString("routing_method"),
            routingConfidence = rs.getObject("routing_confidence") as? Double,
            // MR 리뷰 필드
            mrIid = rs.getObject("mr_iid") as? Int,
            gitlabNoteId = rs.getObject("gitlab_note_id") as? Int,
            discussionId = rs.getString("discussion_id"),
            mrContext = rs.getString("mr_context"),
            createdAt = Instant.parse(rs.getString("created_at"))
        )
    }

    override fun getId(entity: ExecutionRecord): String = entity.id

    override fun save(entity: ExecutionRecord) {
        insert()
            .columns(
                "id" to entity.id,
                "prompt" to entity.prompt,
                "result" to entity.result,
                "status" to entity.status,
                "agent_id" to entity.agentId,
                "project_id" to entity.projectId,
                "user_id" to entity.userId,
                "channel" to entity.channel,
                "thread_ts" to entity.threadTs,
                "reply_ts" to entity.replyTs,
                "duration_ms" to entity.durationMs,
                "input_tokens" to entity.inputTokens,
                "output_tokens" to entity.outputTokens,
                "cost" to entity.cost,
                "error" to entity.error,
                "model" to entity.model,
                "source" to entity.source,
                "routing_method" to entity.routingMethod,
                "routing_confidence" to entity.routingConfidence,
                // MR 리뷰 필드
                "mr_iid" to entity.mrIid,
                "gitlab_note_id" to entity.gitlabNoteId,
                "discussion_id" to entity.discussionId,
                "mr_context" to entity.mrContext,
                "created_at" to entity.createdAt.toString()
            )
            .execute()
    }

    fun findByReplyTs(replyTs: String): ExecutionRecord? {
        return query()
            .select("*")
            .where("reply_ts = ?", replyTs)
            .executeOne { mapRow(it) }
    }

    fun findRecent(limit: Int = 50): List<ExecutionRecord> {
        return query()
            .select("*")
            .orderBy("created_at", QueryBuilder.SortDirection.DESC)
            .limit(limit)
            .execute { mapRow(it) }
    }

    fun findByUserId(userId: String, pageRequest: PageRequest? = null): List<ExecutionRecord> {
        val q = query()
            .select("*")
            .where("user_id = ?", userId)
            .orderBy("created_at", QueryBuilder.SortDirection.DESC)

        pageRequest?.let {
            q.limit(it.size).offset(it.offset)
        }

        return q.execute { mapRow(it) }
    }

    fun findByChannel(channel: String, pageRequest: PageRequest? = null): List<ExecutionRecord> {
        val q = query()
            .select("*")
            .where("channel = ?", channel)
            .orderBy("created_at", QueryBuilder.SortDirection.DESC)

        pageRequest?.let {
            q.limit(it.size).offset(it.offset)
        }

        return q.execute { mapRow(it) }
    }

    fun findByDateRange(dateRange: DateRange, pageRequest: PageRequest? = null): List<ExecutionRecord> {
        return findByDateRange("created_at", dateRange, pageRequest)
    }

    fun updateReplyTs(id: String, replyTs: String): Boolean {
        return update()
            .set("reply_ts", replyTs)
            .where("id = ?", id)
            .execute() > 0
    }

    fun countByStatus(status: String, dateRange: DateRange? = null): Long {
        return if (dateRange != null) {
            countWhere(
                "status = ? AND created_at BETWEEN ? AND ?",
                status, dateRange.from.toString(), dateRange.to.toString()
            )
        } else {
            countWhere("status = ?", status)
        }
    }

    fun countByUserId(userId: String): Long {
        return countWhere("user_id = ?", userId)
    }

    /**
     * Get durations for percentile calculation
     */
    fun getSuccessfulDurations(dateRange: DateRange): List<Long> {
        return executeQuery(
            """
            SELECT duration_ms FROM executions
            WHERE status = 'SUCCESS' AND duration_ms > 0
            AND created_at BETWEEN ? AND ?
            ORDER BY duration_ms
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) { it.getLong("duration_ms") }
    }

    /**
     * Get aggregated stats for a period
     */
    fun getAggregatedStats(dateRange: DateRange): AggregatedStats {
        return executeQueryOne(
            """
            SELECT
                COUNT(*) as total,
                SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) as successful,
                -- cost: DB 값 우선, 없으면 토큰 기반 계산 (Sonnet 4: Input $3/1M, Output $15/1M)
                COALESCE(
                    SUM(cost),
                    SUM(COALESCE(input_tokens, 0)) * 0.000003 + SUM(COALESCE(output_tokens, 0)) * 0.000015
                ) as total_cost,
                COALESCE(SUM(input_tokens), 0) as input_tokens,
                COALESCE(SUM(output_tokens), 0) as output_tokens,
                COALESCE(AVG(duration_ms), 0) as avg_duration
            FROM executions
            WHERE created_at BETWEEN ? AND ?
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) {
            AggregatedStats(
                totalRequests = it.getLong("total"),
                successfulRequests = it.getLong("successful"),
                totalCostUsd = it.getDouble("total_cost"),
                totalInputTokens = it.getLong("input_tokens"),
                totalOutputTokens = it.getLong("output_tokens"),
                avgDurationMs = it.getDouble("avg_duration")
            )
        } ?: AggregatedStats(0, 0, 0.0, 0, 0, 0.0)
    }

    /**
     * Get time series data
     */
    fun getTimeSeries(dateRange: DateRange, granularity: TimeGranularity): List<TimeSeriesPoint> {
        val dateFormat = when (granularity) {
            TimeGranularity.HOUR -> "%Y-%m-%dT%H:00:00"
            TimeGranularity.DAY -> "%Y-%m-%d"
            TimeGranularity.WEEK -> "%Y-W%W"
        }

        return executeQuery(
            """
            SELECT
                strftime('$dateFormat', created_at) as time_bucket,
                COUNT(*) as requests,
                SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) as successful,
                SUM(CASE WHEN status != 'SUCCESS' THEN 1 ELSE 0 END) as failed,
                COALESCE(SUM(cost), 0) as cost,
                COALESCE(SUM(input_tokens + output_tokens), 0) as tokens,
                COALESCE(AVG(duration_ms), 0) as avg_duration
            FROM executions
            WHERE created_at BETWEEN ? AND ?
            GROUP BY time_bucket
            ORDER BY time_bucket
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) {
            TimeSeriesPoint(
                timestamp = it.getString("time_bucket"),
                requests = it.getLong("requests"),
                successful = it.getLong("successful"),
                failed = it.getLong("failed"),
                cost = it.getDouble("cost"),
                tokens = it.getLong("tokens"),
                avgDurationMs = it.getLong("avg_duration"),
                p95DurationMs = 0  // Calculated separately if needed
            )
        }
    }

    /**
     * Get error statistics
     */
    fun getErrorStats(dateRange: DateRange): List<ErrorStats> {
        return executeQuery(
            """
            SELECT
                CASE
                    WHEN error LIKE '%timeout%' THEN 'timeout'
                    WHEN error LIKE '%rate limit%' THEN 'rate_limit'
                    WHEN error LIKE '%max_turns%' THEN 'max_turns'
                    WHEN error LIKE '%network%' OR error LIKE '%connection%' THEN 'network_error'
                    WHEN error LIKE '%invalid%' OR error LIKE '%bad request%' THEN 'invalid_request'
                    WHEN error LIKE '%auth%' OR error LIKE '%unauthorized%' OR error LIKE '%forbidden%' THEN 'authentication'
                    WHEN error LIKE '%internal%' OR error LIKE '%server error%' THEN 'internal_error'
                    ELSE 'other'
                END as error_type,
                COUNT(*) as count
            FROM executions
            WHERE status != 'SUCCESS' AND created_at BETWEEN ? AND ?
            GROUP BY error_type
            ORDER BY count DESC
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) {
            ErrorStats(
                errorType = it.getString("error_type"),
                count = it.getLong("count"),
                percentage = 0.0,  // Calculated in service
                trend = "stable"   // Calculated in service
            )
        }
    }

    /**
     * Get user statistics
     */
    fun getUserStats(dateRange: DateRange, limit: Int = 20): List<UserStats> {
        return executeQuery(
            """
            SELECT
                e.user_id,
                uc.display_name,
                COUNT(*) as total_requests,
                SUM(CASE WHEN e.status = 'SUCCESS' THEN 1 ELSE 0 END) as successful,
                COALESCE(SUM(e.input_tokens + e.output_tokens), 0) as total_tokens,
                COALESCE(SUM(e.cost), 0) as total_cost,
                MAX(e.created_at) as last_seen
            FROM executions e
            LEFT JOIN user_contexts uc ON e.user_id = uc.user_id
            WHERE e.created_at BETWEEN ? AND ? AND e.user_id IS NOT NULL
            GROUP BY e.user_id
            ORDER BY total_requests DESC
            LIMIT ?
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString(), limit
        ) {
            val totalRequests = it.getLong("total_requests")
            val successful = it.getLong("successful")
            UserStats(
                userId = it.getString("user_id"),
                displayName = it.getString("display_name"),
                totalRequests = totalRequests,
                successRate = if (totalRequests > 0) successful.toDouble() / totalRequests else 0.0,
                totalTokens = it.getLong("total_tokens"),
                totalCost = it.getDouble("total_cost"),
                lastSeen = it.getString("last_seen")
            )
        }
    }

    // ==================== 통합 조회 API ====================

    /**
     * Source 필터링된 조회 (통합 Interactions)
     * @param sources 조회할 source 목록 (null이면 전체)
     * @param search 검색어 (prompt, result에서 검색)
     * @param dateRange 날짜 범위
     * @param pageRequest 페이징
     */
    fun findByFilters(
        sources: List<String>? = null,
        search: String? = null,
        dateRange: DateRange? = null,
        pageRequest: PageRequest? = null
    ): List<ExecutionRecord> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        // Source 필터
        if (!sources.isNullOrEmpty()) {
            val placeholders = sources.joinToString(",") { "?" }
            conditions.add("(source IN ($placeholders) OR (source IS NULL AND ? = 'other'))")
            params.addAll(sources)
            params.add(if (sources.contains("other")) "other" else "")
        }

        // 검색어 필터
        if (!search.isNullOrBlank()) {
            conditions.add("(prompt LIKE ? OR result LIKE ? OR mr_context LIKE ?)")
            val searchPattern = "%$search%"
            params.add(searchPattern)
            params.add(searchPattern)
            params.add(searchPattern)
        }

        // 날짜 범위 필터
        if (dateRange != null) {
            conditions.add("created_at BETWEEN ? AND ?")
            params.add(dateRange.from.toString())
            params.add(dateRange.to.toString())
        }

        val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val limitClause = pageRequest?.let { "LIMIT ${it.size} OFFSET ${it.offset}" } ?: "LIMIT 100"

        val sql = """
            SELECT * FROM executions
            $whereClause
            ORDER BY created_at DESC
            $limitClause
        """.trimIndent()

        return executeQuery(sql, *params.toTypedArray()) { mapRow(it) }
    }

    /**
     * Source별 통계
     */
    fun getStatsBySource(dateRange: DateRange? = null): List<SourceStats> {
        val whereClause = dateRange?.let {
            "WHERE created_at BETWEEN '${it.from}' AND '${it.to}'"
        } ?: ""

        return executeQuery(
            """
            SELECT
                COALESCE(source, 'other') as source,
                COUNT(*) as requests,
                SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) as successful
            FROM executions
            $whereClause
            GROUP BY COALESCE(source, 'other')
            ORDER BY requests DESC
            """.trimIndent()
        ) {
            val requests = it.getLong("requests")
            SourceStats(
                source = it.getString("source") ?: "other",
                requests = requests,
                successRate = if (requests > 0)
                    it.getLong("successful").toDouble() / requests else 0.0
            )
        }
    }

    /**
     * 전체 카운트 (필터 적용)
     */
    fun countByFilters(
        sources: List<String>? = null,
        search: String? = null,
        dateRange: DateRange? = null
    ): Long {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (!sources.isNullOrEmpty()) {
            val placeholders = sources.joinToString(",") { "?" }
            conditions.add("(source IN ($placeholders) OR (source IS NULL AND ? = 'other'))")
            params.addAll(sources)
            params.add(if (sources.contains("other")) "other" else "")
        }

        if (!search.isNullOrBlank()) {
            conditions.add("(prompt LIKE ? OR result LIKE ? OR mr_context LIKE ?)")
            val searchPattern = "%$search%"
            params.add(searchPattern)
            params.add(searchPattern)
            params.add(searchPattern)
        }

        if (dateRange != null) {
            conditions.add("created_at BETWEEN ? AND ?")
            params.add(dateRange.from.toString())
            params.add(dateRange.to.toString())
        }

        val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

        return executeQueryOne(
            "SELECT COUNT(*) as cnt FROM executions $whereClause",
            *params.toTypedArray()
        ) { it.getLong("cnt") } ?: 0L
    }

    /**
     * GitLab note ID로 조회
     */
    fun findByGitlabNoteId(noteId: Int): ExecutionRecord? {
        return query()
            .select("*")
            .where("gitlab_note_id = ?", noteId)
            .executeOne { mapRow(it) }
    }

    /**
     * MR 리뷰 조회
     */
    fun findMrReviews(projectId: String? = null, limit: Int = 50): List<ExecutionRecord> {
        val q = query()
            .select("*")
            .where("source = ?", ExecutionRecord.SOURCE_MR_REVIEW)

        if (projectId != null) {
            q.where("project_id = ?", projectId)
        }

        return q.orderBy("created_at", QueryBuilder.SortDirection.DESC)
            .limit(limit)
            .execute { mapRow(it) }
    }
}

data class AggregatedStats(
    val totalRequests: Long,
    val successfulRequests: Long,
    val totalCostUsd: Double,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val avgDurationMs: Double
)

enum class TimeGranularity {
    HOUR, DAY, WEEK
}
