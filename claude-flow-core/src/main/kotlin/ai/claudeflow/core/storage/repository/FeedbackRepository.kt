package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.*
import java.sql.ResultSet
import java.time.Instant

// FeedbackStats is defined in ExecutionRecord.kt

/**
 * Repository for feedback records
 *
 * - 카테고리 분류: feedback, trigger, action, other
 * - Verified Feedback: 요청자의 피드백만 실제 점수에 반영
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
            category = rs.getString("category") ?: FeedbackRecord.categorizeReaction(rs.getString("reaction")),
            isVerified = rs.getInt("is_verified") == 1,
            verifiedAt = rs.getString("verified_at")?.let { Instant.parse(it) },
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
                "category" to entity.category,
                "is_verified" to (if (entity.isVerified) 1 else 0),
                "verified_at" to entity.verifiedAt?.toString(),
                "created_at" to entity.createdAt.toString()
            )
            .execute()
    }

    /**
     * Verified Feedback 저장 (요청자 확인)
     */
    fun saveWithVerification(
        id: String,
        executionId: String,
        userId: String,
        reaction: String,
        requesterId: String
    ): FeedbackRecord {
        val feedback = FeedbackRecord.createVerified(id, executionId, userId, reaction, requesterId)
        save(feedback)
        return feedback
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

    /**
     * Verified Feedback 통계
     * 요청자의 피드백만 실제 점수에 반영
     */
    fun getVerifiedFeedbackStats(dateRange: DateRange? = null): VerifiedFeedbackStats {
        val baseSql = """
            SELECT
                COUNT(*) as total,
                SUM(CASE WHEN is_verified = 1 THEN 1 ELSE 0 END) as verified,
                SUM(CASE WHEN is_verified = 1 AND reaction IN ('thumbsup', '+1', 'heart', 'tada') THEN 1 ELSE 0 END) as verified_positive,
                SUM(CASE WHEN is_verified = 1 AND reaction IN ('thumbsdown', '-1') THEN 1 ELSE 0 END) as verified_negative
            FROM feedback
            WHERE category = 'feedback'
        """.trimIndent()

        val sql = if (dateRange != null) {
            "$baseSql AND created_at BETWEEN ? AND ?"
        } else {
            baseSql
        }

        val stats = if (dateRange != null) {
            executeQueryOne(sql, dateRange.from.toString(), dateRange.to.toString()) {
                VerifiedFeedbackStats(
                    totalFeedback = it.getLong("total"),
                    verifiedFeedback = it.getLong("verified"),
                    verifiedPositive = it.getLong("verified_positive"),
                    verifiedNegative = it.getLong("verified_negative"),
                    verificationRate = 0.0,  // 계산됨
                    satisfactionRate = 0.0   // 계산됨
                )
            }
        } else {
            executeQueryOne(sql) {
                VerifiedFeedbackStats(
                    totalFeedback = it.getLong("total"),
                    verifiedFeedback = it.getLong("verified"),
                    verifiedPositive = it.getLong("verified_positive"),
                    verifiedNegative = it.getLong("verified_negative"),
                    verificationRate = 0.0,
                    satisfactionRate = 0.0
                )
            }
        } ?: VerifiedFeedbackStats(0, 0, 0, 0, 0.0, 0.0)

        // Calculate rates
        val verificationRate = if (stats.totalFeedback > 0)
            stats.verifiedFeedback.toDouble() / stats.totalFeedback else 0.0
        val satisfactionTotal = stats.verifiedPositive + stats.verifiedNegative
        val satisfactionRate = if (satisfactionTotal > 0)
            stats.verifiedPositive.toDouble() / satisfactionTotal else 0.0

        return stats.copy(
            verificationRate = verificationRate,
            satisfactionRate = satisfactionRate
        )
    }

    /**
     * 카테고리별 피드백 조회
     */
    fun findByCategory(category: String, dateRange: DateRange? = null): List<FeedbackRecord> {
        return if (dateRange != null) {
            query()
                .select("*")
                .where("category = ?", category)
                .where("created_at BETWEEN ? AND ?", dateRange.from.toString(), dateRange.to.toString())
                .execute { mapRow(it) }
        } else {
            query()
                .select("*")
                .where("category = ?", category)
                .execute { mapRow(it) }
        }
    }

    /**
     * Verified Feedback만 조회
     */
    fun findVerified(dateRange: DateRange? = null): List<FeedbackRecord> {
        return if (dateRange != null) {
            query()
                .select("*")
                .where("is_verified = ?", 1)
                .where("created_at BETWEEN ? AND ?", dateRange.from.toString(), dateRange.to.toString())
                .execute { mapRow(it) }
        } else {
            query()
                .select("*")
                .where("is_verified = ?", 1)
                .execute { mapRow(it) }
        }
    }

    /**
     * 특정 실행의 Verified Feedback 조회
     */
    fun findVerifiedByExecutionId(executionId: String): List<FeedbackRecord> {
        return query()
            .select("*")
            .where("execution_id = ?", executionId)
            .where("is_verified = ?", 1)
            .execute { mapRow(it) }
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
