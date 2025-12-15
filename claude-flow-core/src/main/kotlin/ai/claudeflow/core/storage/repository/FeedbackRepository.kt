package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.*
import java.sql.ResultSet
import java.time.Instant

// FeedbackStats is defined in ExecutionRecord.kt

/**
 * Repository for feedback records
 */
class FeedbackRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<FeedbackRecord, String>(connectionProvider) {

    override val tableName: String = "feedback"
    override val primaryKeyColumn: String = "id"

    override fun mapRow(rs: ResultSet): FeedbackRecord {
        return FeedbackRecord(
            id = rs.getString("id"),
            executionId = rs.getString("execution_id"),
            userId = rs.getString("user_id"),
            reaction = rs.getString("reaction"),
            createdAt = Instant.parse(rs.getString("created_at"))
        )
    }

    override fun getId(entity: FeedbackRecord): String = entity.id

    override fun save(entity: FeedbackRecord) {
        insert()
            .columns(
                "id" to entity.id,
                "execution_id" to entity.executionId,
                "user_id" to entity.userId,
                "reaction" to entity.reaction,
                "created_at" to entity.createdAt.toString()
            )
            .execute()
    }

    fun findByExecutionId(executionId: String): List<FeedbackRecord> {
        return query()
            .select("*")
            .where("execution_id = ?", executionId)
            .execute { mapRow(it) }
    }

    fun findByUserId(userId: String): List<FeedbackRecord> {
        return query()
            .select("*")
            .where("user_id = ?", userId)
            .execute { mapRow(it) }
    }

    fun deleteByExecutionUserReaction(executionId: String, userId: String, reaction: String): Boolean {
        return delete()
            .where("execution_id = ?", executionId)
            .where("user_id = ?", userId)
            .where("reaction = ?", reaction)
            .execute() > 0
    }

    /**
     * Get feedback statistics for a date range
     */
    fun getFeedbackStats(dateRange: DateRange? = null): FeedbackStats {
        val sql = if (dateRange != null) {
            """
            SELECT
                SUM(CASE WHEN reaction IN ('thumbsup', '+1') THEN 1 ELSE 0 END) as positive,
                SUM(CASE WHEN reaction IN ('thumbsdown', '-1') THEN 1 ELSE 0 END) as negative
            FROM feedback
            WHERE created_at BETWEEN ? AND ?
            """.trimIndent()
        } else {
            """
            SELECT
                SUM(CASE WHEN reaction IN ('thumbsup', '+1') THEN 1 ELSE 0 END) as positive,
                SUM(CASE WHEN reaction IN ('thumbsdown', '-1') THEN 1 ELSE 0 END) as negative
            FROM feedback
            """.trimIndent()
        }

        val (positive, negative) = if (dateRange != null) {
            executeQueryOne(sql, dateRange.from.toString(), dateRange.to.toString()) {
                Pair(it.getLong("positive"), it.getLong("negative"))
            } ?: Pair(0L, 0L)
        } else {
            executeQueryOne(sql) {
                Pair(it.getLong("positive"), it.getLong("negative"))
            } ?: Pair(0L, 0L)
        }

        // Count executions without feedback
        val pendingSql = if (dateRange != null) {
            """
            SELECT COUNT(*) FROM executions e
            WHERE e.created_at BETWEEN ? AND ?
            AND NOT EXISTS (
                SELECT 1 FROM feedback f WHERE f.execution_id = e.id
                AND f.reaction IN ('thumbsup', '+1', 'thumbsdown', '-1')
            )
            """.trimIndent()
        } else {
            """
            SELECT COUNT(*) FROM executions e
            WHERE NOT EXISTS (
                SELECT 1 FROM feedback f WHERE f.execution_id = e.id
                AND f.reaction IN ('thumbsup', '+1', 'thumbsdown', '-1')
            )
            """.trimIndent()
        }

        val pending = if (dateRange != null) {
            executeQueryOne(pendingSql, dateRange.from.toString(), dateRange.to.toString()) {
                it.getLong(1)
            } ?: 0L
        } else {
            executeQueryOne(pendingSql) { it.getLong(1) } ?: 0L
        }

        val total = positive + negative
        val satisfactionRate = if (total > 0) positive.toDouble() / total else 0.0

        return FeedbackStats(
            positive = positive,
            negative = negative,
            satisfactionRate = satisfactionRate,
            pendingFeedback = pending
        )
    }

    fun countPositive(dateRange: DateRange? = null): Long {
        return if (dateRange != null) {
            countWhere(
                "reaction IN ('thumbsup', '+1') AND created_at BETWEEN ? AND ?",
                dateRange.from.toString(), dateRange.to.toString()
            )
        } else {
            countWhere("reaction IN ('thumbsup', '+1')")
        }
    }

    fun countNegative(dateRange: DateRange? = null): Long {
        return if (dateRange != null) {
            countWhere(
                "reaction IN ('thumbsdown', '-1') AND created_at BETWEEN ? AND ?",
                dateRange.from.toString(), dateRange.to.toString()
            )
        } else {
            countWhere("reaction IN ('thumbsdown', '-1')")
        }
    }
}
