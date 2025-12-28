package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.time.Instant

/**
 * 관리자 상세 피드백 Repository
 *
 * 관리자가 직접 평가하는 고품질 피드백 데이터 관리
 */
class AdminFeedbackRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<AdminFeedback, String>(connectionProvider) {

    private val objectMapper = jacksonObjectMapper()

    override val tableName: String = "admin_feedback"
    override val primaryKeyColumn: String = "id"

    override fun mapRow(rs: ResultSet): AdminFeedback {
        val issuesJson = rs.getString("issues")
        val issues: List<FeedbackIssue> = if (issuesJson.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                val issueNames: List<String> = objectMapper.readValue(issuesJson)
                issueNames.mapNotNull { name ->
                    try { FeedbackIssue.valueOf(name) } catch (e: Exception) { null }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        return AdminFeedback(
            id = rs.getString("id"),
            executionId = rs.getString("execution_id"),
            adminId = rs.getString("admin_id"),
            quickRating = QuickRating.valueOf(rs.getString("quick_rating") ?: "PENDING"),
            correctness = rs.getInt("correctness").takeIf { !rs.wasNull() },
            helpfulness = rs.getInt("helpfulness").takeIf { !rs.wasNull() },
            verbosity = rs.getInt("verbosity").takeIf { !rs.wasNull() },
            issues = issues,
            comment = rs.getString("comment"),
            isExemplary = rs.getInt("is_exemplary") == 1,
            goldResponse = rs.getString("gold_response"),
            createdAt = Instant.parse(rs.getString("created_at")),
            updatedAt = Instant.parse(rs.getString("updated_at"))
        )
    }

    override fun getId(entity: AdminFeedback): String = entity.id

    override fun save(entity: AdminFeedback) {
        val issuesJson = objectMapper.writeValueAsString(entity.issues.map { it.name })

        val sql = """
            INSERT OR REPLACE INTO admin_feedback
            (id, execution_id, admin_id, quick_rating, correctness, helpfulness, verbosity,
             issues, comment, is_exemplary, gold_response, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        executeUpdate(sql,
            entity.id,
            entity.executionId,
            entity.adminId,
            entity.quickRating.name,
            entity.correctness,
            entity.helpfulness,
            entity.verbosity,
            issuesJson,
            entity.comment,
            if (entity.isExemplary) 1 else 0,
            entity.goldResponse,
            entity.createdAt.toString(),
            entity.updatedAt.toString()
        )
    }

    /**
     * execution_id로 관리자 피드백 조회
     */
    fun findByExecutionId(executionId: String): AdminFeedback? {
        return query()
            .select("*")
            .where("execution_id = ?", executionId)
            .executeOne { mapRow(it) }
    }

    /**
     * 우수 사례 목록 조회 (Few-shot 예제용)
     */
    fun findExemplary(limit: Int = 10): List<AdminFeedback> {
        return query()
            .select("*")
            .where("is_exemplary = ?", 1)
            .where("quick_rating = ?", QuickRating.POSITIVE.name)
            .orderBy("updated_at DESC")
            .limit(limit)
            .execute { mapRow(it) }
    }

    /**
     * Gold Response가 있는 피드백 조회 (학습 데이터용)
     */
    fun findWithGoldResponse(limit: Int = 50): List<AdminFeedback> {
        return query()
            .select("*")
            .where("gold_response IS NOT NULL")
            .where("gold_response != ''")
            .orderBy("updated_at DESC")
            .limit(limit)
            .execute { mapRow(it) }
    }

    /**
     * 에이전트별 우수 사례 조회
     */
    fun findExemplaryByAgent(agentId: String, limit: Int = 5): List<AdminFeedback> {
        val sql = """
            SELECT af.* FROM admin_feedback af
            JOIN executions e ON af.execution_id = e.id
            WHERE af.is_exemplary = 1
            AND af.quick_rating = ?
            AND e.agent_id = ?
            ORDER BY af.updated_at DESC
            LIMIT ?
        """.trimIndent()

        return executeQuery(sql, QuickRating.POSITIVE.name, agentId, limit) { mapRow(it) }
    }

    /**
     * 미평가 execution 목록 조회 (관리자 작업 대기열)
     */
    fun findPendingExecutions(limit: Int = 50): List<String> {
        val sql = """
            SELECT e.id FROM executions e
            LEFT JOIN admin_feedback af ON e.id = af.execution_id
            WHERE af.id IS NULL
            ORDER BY e.created_at DESC
            LIMIT ?
        """.trimIndent()

        return executeQuery(sql, limit) { it.getString("id") }
    }

    /**
     * 문제 유형별 통계
     */
    fun getIssueDistribution(dateRange: DateRange? = null): Map<FeedbackIssue, Long> {
        val feedbacks = if (dateRange != null) {
            query()
                .select("issues")
                .where("created_at BETWEEN ? AND ?", dateRange.from.toString(), dateRange.to.toString())
                .execute { it.getString("issues") }
        } else {
            query()
                .select("issues")
                .execute { it.getString("issues") }
        }

        val distribution = mutableMapOf<FeedbackIssue, Long>()

        for (issuesJson in feedbacks) {
            if (issuesJson.isNullOrBlank()) continue
            try {
                val issueNames: List<String> = objectMapper.readValue(issuesJson)
                for (name in issueNames) {
                    try {
                        val issue = FeedbackIssue.valueOf(name)
                        distribution[issue] = (distribution[issue] ?: 0L) + 1
                    } catch (e: Exception) { /* ignore */ }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        return distribution
    }

    /**
     * 관리자 피드백 통계
     */
    fun getStats(dateRange: DateRange? = null): AdminFeedbackStats {
        val baseSql = """
            SELECT
                COUNT(*) as total,
                SUM(CASE WHEN quick_rating = 'POSITIVE' THEN 1 ELSE 0 END) as positive,
                SUM(CASE WHEN quick_rating = 'NEGATIVE' THEN 1 ELSE 0 END) as negative,
                SUM(CASE WHEN quick_rating = 'PENDING' THEN 1 ELSE 0 END) as pending,
                SUM(CASE WHEN is_exemplary = 1 THEN 1 ELSE 0 END) as exemplary,
                SUM(CASE WHEN gold_response IS NOT NULL AND gold_response != '' THEN 1 ELSE 0 END) as gold,
                AVG(correctness) as avg_correctness,
                AVG(helpfulness) as avg_helpfulness,
                AVG(verbosity) as avg_verbosity
            FROM admin_feedback
        """.trimIndent()

        val sql = if (dateRange != null) {
            "$baseSql WHERE created_at BETWEEN ? AND ?"
        } else {
            baseSql
        }

        val result = if (dateRange != null) {
            executeQueryOne(sql, dateRange.from.toString(), dateRange.to.toString()) { rs ->
                AdminFeedbackStats(
                    totalReviewed = rs.getLong("total"),
                    positiveCount = rs.getLong("positive"),
                    negativeCount = rs.getLong("negative"),
                    pendingCount = rs.getLong("pending"),
                    exemplaryCount = rs.getLong("exemplary"),
                    goldResponseCount = rs.getLong("gold"),
                    avgCorrectness = rs.getDouble("avg_correctness").takeIf { !rs.wasNull() },
                    avgHelpfulness = rs.getDouble("avg_helpfulness").takeIf { !rs.wasNull() },
                    avgVerbosity = rs.getDouble("avg_verbosity").takeIf { !rs.wasNull() },
                    issueDistribution = emptyMap()  // 별도 계산
                )
            }
        } else {
            executeQueryOne(sql) { rs ->
                AdminFeedbackStats(
                    totalReviewed = rs.getLong("total"),
                    positiveCount = rs.getLong("positive"),
                    negativeCount = rs.getLong("negative"),
                    pendingCount = rs.getLong("pending"),
                    exemplaryCount = rs.getLong("exemplary"),
                    goldResponseCount = rs.getLong("gold"),
                    avgCorrectness = rs.getDouble("avg_correctness").takeIf { !rs.wasNull() },
                    avgHelpfulness = rs.getDouble("avg_helpfulness").takeIf { !rs.wasNull() },
                    avgVerbosity = rs.getDouble("avg_verbosity").takeIf { !rs.wasNull() },
                    issueDistribution = emptyMap()
                )
            }
        } ?: AdminFeedbackStats(0, 0, 0, 0, 0, 0, null, null, null, emptyMap())

        // 이슈 분포 추가
        val issueDistribution = getIssueDistribution(dateRange)
        return result.copy(issueDistribution = issueDistribution)
    }

    /**
     * 에이전트별 평균 점수
     */
    fun getAgentScores(): Map<String, Double> {
        val sql = """
            SELECT e.agent_id, AVG((af.correctness + af.helpfulness) / 2.0) as avg_score
            FROM admin_feedback af
            JOIN executions e ON af.execution_id = e.id
            WHERE af.correctness IS NOT NULL AND af.helpfulness IS NOT NULL
            GROUP BY e.agent_id
        """.trimIndent()

        val result = mutableMapOf<String, Double>()
        executeQuery(sql) { rs ->
            val agentId = rs.getString("agent_id")
            val avgScore = rs.getDouble("avg_score")
            if (agentId != null) {
                result[agentId] = avgScore
            }
        }
        return result
    }
}
