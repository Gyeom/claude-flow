package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.*
import java.sql.ResultSet
import java.time.Instant

// GitLabReviewRecord is in ExecutionRecord.kt (same package: ai.claudeflow.core.storage)

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
            source = rs.getString("source") ?: "unknown",
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
                "source" to entity.source,
                "is_verified" to (if (entity.isVerified) 1 else 0),
                "verified_at" to entity.verifiedAt?.toString(),
                "created_at" to entity.createdAt.toString()
            )
            .execute()
    }

    /**
     * Verified Feedback 저장 (요청자 확인)
     * @param source 피드백 출처 (slack, chat, gitlab_emoji, gitlab_note, api)
     */
    fun saveWithVerification(
        id: String,
        executionId: String,
        userId: String,
        reaction: String,
        requesterId: String,
        source: String = "unknown"
    ): FeedbackRecord {
        val feedback = FeedbackRecord.createVerified(id, executionId, userId, reaction, requesterId, source)
        save(feedback)
        return feedback
    }

    fun findByExecutionId(executionId: String): List<FeedbackRecord> {
        return query()
            .select("*")
            .where("execution_id = ?", executionId)
            .execute { mapRow(it) }
    }

    /**
     * 여러 execution ID에 대한 피드백을 한 번에 조회 (batch)
     * @return execution_id를 키로 하는 맵
     */
    fun findByExecutionIds(executionIds: List<String>): Map<String, List<FeedbackRecord>> {
        if (executionIds.isEmpty()) return emptyMap()

        val placeholders = executionIds.joinToString(",") { "?" }
        val sql = "SELECT * FROM feedback WHERE execution_id IN ($placeholders)"

        val feedbacks = executeQuery(sql, *executionIds.toTypedArray()) { mapRow(it) }
        return feedbacks.groupBy { it.executionId }
    }

    fun findByUserId(userId: String): List<FeedbackRecord> {
        return query()
            .select("*")
            .where("user_id = ?", userId)
            .execute { mapRow(it) }
    }

    /**
     * Source별 피드백 조회
     * @param source 피드백 출처 (slack, chat, gitlab_emoji, gitlab_note, api)
     */
    fun findBySource(source: String, dateRange: DateRange? = null): List<FeedbackRecord> {
        return if (dateRange != null) {
            query()
                .select("*")
                .where("source = ?", source)
                .where("created_at BETWEEN ? AND ?", dateRange.from.toString(), dateRange.to.toString())
                .execute { mapRow(it) }
        } else {
            query()
                .select("*")
                .where("source = ?", source)
                .execute { mapRow(it) }
        }
    }

    /**
     * Source별 피드백 통계
     */
    fun getFeedbackStatsBySource(source: String, dateRange: DateRange? = null): FeedbackStats {
        val baseSql = """
            SELECT
                SUM(CASE WHEN reaction IN ('thumbsup', '+1') THEN 1 ELSE 0 END) as positive,
                SUM(CASE WHEN reaction IN ('thumbsdown', '-1') THEN 1 ELSE 0 END) as negative
            FROM feedback
            WHERE source = ?
        """.trimIndent()

        val sql = if (dateRange != null) {
            "$baseSql AND created_at BETWEEN ? AND ?"
        } else {
            baseSql
        }

        val (positive, negative) = if (dateRange != null) {
            executeQueryOne(sql, source, dateRange.from.toString(), dateRange.to.toString()) {
                Pair(it.getLong("positive"), it.getLong("negative"))
            } ?: Pair(0L, 0L)
        } else {
            executeQueryOne(sql, source) {
                Pair(it.getLong("positive"), it.getLong("negative"))
            } ?: Pair(0L, 0L)
        }

        val total = positive + negative
        val satisfactionRate = if (total > 0) positive.toDouble() / total else 0.0

        return FeedbackStats(
            positive = positive,
            negative = negative,
            satisfactionRate = satisfactionRate,
            pendingFeedback = 0
        )
    }

    /**
     * 전체 Source별 피드백 분포 조회
     */
    fun getFeedbackDistributionBySource(dateRange: DateRange? = null): Map<String, Long> {
        val baseSql = """
            SELECT source, COUNT(*) as count
            FROM feedback
        """.trimIndent()

        val sql = if (dateRange != null) {
            "$baseSql WHERE created_at BETWEEN ? AND ? GROUP BY source"
        } else {
            "$baseSql GROUP BY source"
        }

        val result = mutableMapOf<String, Long>()
        if (dateRange != null) {
            executeQuery(sql, dateRange.from.toString(), dateRange.to.toString()) { rs ->
                val source = rs.getString("source") ?: "unknown"
                val count = rs.getLong("count")
                result[source] = count
            }
        } else {
            executeQuery(sql) { rs ->
                val source = rs.getString("source") ?: "unknown"
                val count = rs.getLong("count")
                result[source] = count
            }
        }
        return result
    }

    /**
     * Source별 피드백 상세 통계 (positive/negative/total)
     */
    fun getFeedbackDetailedBySource(dateRange: DateRange? = null): List<FeedbackBySourceStats> {
        val baseSql = """
            SELECT
                source,
                SUM(CASE WHEN reaction IN ('thumbsup', '+1', 'heart', 'tada') THEN 1 ELSE 0 END) as positive,
                SUM(CASE WHEN reaction IN ('thumbsdown', '-1') THEN 1 ELSE 0 END) as negative,
                COUNT(*) as total
            FROM feedback
            WHERE category = 'feedback'
        """.trimIndent()

        val sql = if (dateRange != null) {
            "$baseSql AND created_at BETWEEN ? AND ? GROUP BY source ORDER BY total DESC"
        } else {
            "$baseSql GROUP BY source ORDER BY total DESC"
        }

        return if (dateRange != null) {
            executeQuery(sql, dateRange.from.toString(), dateRange.to.toString()) { rs ->
                FeedbackBySourceStats(
                    source = rs.getString("source") ?: "unknown",
                    positive = rs.getLong("positive"),
                    negative = rs.getLong("negative"),
                    total = rs.getLong("total")
                )
            }
        } else {
            executeQuery(sql) { rs ->
                FeedbackBySourceStats(
                    source = rs.getString("source") ?: "unknown",
                    positive = rs.getLong("positive"),
                    negative = rs.getLong("negative"),
                    total = rs.getLong("total")
                )
            }
        }
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

    // ==================== GitLab Review Records ====================

    /**
     * GitLab 리뷰 레코드 저장
     */
    fun saveReviewRecord(record: GitLabReviewRecord) {
        val sql = """
            INSERT OR REPLACE INTO gitlab_reviews
            (id, project_id, mr_iid, note_id, discussion_id, review_content, mr_context, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        executeUpdate(sql,
            record.id,
            record.projectId,
            record.mrIid,
            record.noteId,
            record.discussionId,
            record.reviewContent,
            record.mrContext,
            record.createdAt.toString()
        )
    }

    /**
     * GitLab note ID로 리뷰 레코드 조회
     */
    fun findReviewByNoteId(noteId: Int): GitLabReviewRecord? {
        val sql = "SELECT * FROM gitlab_reviews WHERE note_id = ?"
        return executeQueryOne(sql, noteId) { rs ->
            GitLabReviewRecord(
                id = rs.getString("id"),
                projectId = rs.getString("project_id"),
                mrIid = rs.getInt("mr_iid"),
                noteId = rs.getInt("note_id"),
                discussionId = rs.getString("discussion_id"),
                reviewContent = rs.getString("review_content"),
                mrContext = rs.getString("mr_context"),
                createdAt = java.time.Instant.parse(rs.getString("created_at"))
            )
        }
    }

    /**
     * GitLab discussion ID로 리뷰 레코드 조회
     */
    fun findReviewByDiscussionId(discussionId: String): GitLabReviewRecord? {
        val sql = "SELECT * FROM gitlab_reviews WHERE discussion_id = ?"
        return executeQueryOne(sql, discussionId) { rs ->
            GitLabReviewRecord(
                id = rs.getString("id"),
                projectId = rs.getString("project_id"),
                mrIid = rs.getInt("mr_iid"),
                noteId = rs.getInt("note_id"),
                discussionId = rs.getString("discussion_id"),
                reviewContent = rs.getString("review_content"),
                mrContext = rs.getString("mr_context"),
                createdAt = java.time.Instant.parse(rs.getString("created_at"))
            )
        }
    }

    /**
     * 프로젝트의 모든 리뷰 레코드 조회
     */
    fun findReviewsByProject(projectId: String): List<GitLabReviewRecord> {
        val sql = "SELECT * FROM gitlab_reviews WHERE project_id = ? ORDER BY created_at DESC"
        return executeQuery(sql, projectId) { rs ->
            GitLabReviewRecord(
                id = rs.getString("id"),
                projectId = rs.getString("project_id"),
                mrIid = rs.getInt("mr_iid"),
                noteId = rs.getInt("note_id"),
                discussionId = rs.getString("discussion_id"),
                reviewContent = rs.getString("review_content"),
                mrContext = rs.getString("mr_context"),
                createdAt = java.time.Instant.parse(rs.getString("created_at"))
            )
        }
    }

    /**
     * 모든 리뷰 레코드 조회 (최근 N일)
     */
    fun findAllReviews(days: Int = 30): List<GitLabReviewRecord> {
        val sql = """
            SELECT * FROM gitlab_reviews
            WHERE created_at >= datetime('now', '-$days days')
            ORDER BY created_at DESC
        """.trimIndent()
        return executeQuery(sql) { rs ->
            GitLabReviewRecord(
                id = rs.getString("id"),
                projectId = rs.getString("project_id"),
                mrIid = rs.getInt("mr_iid"),
                noteId = rs.getInt("note_id"),
                discussionId = rs.getString("discussion_id"),
                reviewContent = rs.getString("review_content"),
                mrContext = rs.getString("mr_context"),
                createdAt = java.time.Instant.parse(rs.getString("created_at"))
            )
        }
    }

    /**
     * GitLab 소스 피드백 저장
     */
    fun saveGitLabFeedback(
        id: String,
        gitlabProjectId: String,
        mrIid: Int,
        noteId: Int,
        reaction: String,
        userId: String,
        source: String,  // gitlab_emoji, gitlab_note
        comment: String? = null
    ): FeedbackRecord {
        val sql = """
            INSERT INTO feedback
            (id, execution_id, user_id, reaction, category, is_verified, created_at, source, gitlab_project_id, gitlab_mr_iid, gitlab_note_id, comment)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        val category = FeedbackRecord.categorizeReaction(reaction)
        val now = java.time.Instant.now()

        executeUpdate(sql,
            id,
            "",  // execution_id는 GitLab 피드백에서는 빈 문자열
            userId,
            reaction,
            category,
            1,  // GitLab 피드백은 모두 verified로 처리
            now.toString(),
            source,
            gitlabProjectId,
            mrIid,
            noteId,
            comment
        )

        return FeedbackRecord(
            id = id,
            executionId = "",
            userId = userId,
            reaction = reaction,
            category = category,
            source = source,
            isVerified = true,
            verifiedAt = now,
            createdAt = now
        )
    }

    /**
     * GitLab 피드백 조회 (note_id로)
     */
    fun findGitLabFeedbackByNoteId(noteId: Int): List<FeedbackRecord> {
        val sql = "SELECT * FROM feedback WHERE gitlab_note_id = ?"
        return executeQuery(sql, noteId) { mapRow(it) }
    }

    /**
     * GitLab 피드백 중복 확인 (noteId + userId + reaction)
     * 동일한 사용자가 동일한 코멘트에 동일한 이모지를 이미 추가했는지 확인
     */
    fun existsGitLabFeedback(noteId: Int, userId: String, reaction: String): Boolean {
        val sql = "SELECT COUNT(*) FROM feedback WHERE gitlab_note_id = ? AND user_id = ? AND reaction = ?"
        return (executeQueryOne(sql, noteId, userId, reaction) { it.getLong(1) } ?: 0L) > 0
    }

    /**
     * GitLab 피드백 조회 (project_id + mr_iid로)
     * Interactions API에서 MR 리뷰 실행의 피드백을 조회할 때 사용
     */
    fun findGitLabFeedbackByProjectMr(projectId: String, mrIid: Int): List<FeedbackRecord> {
        val sql = """
            SELECT f.*, f.gitlab_project_id, f.gitlab_mr_iid, f.gitlab_note_id, f.comment
            FROM feedback f
            WHERE f.gitlab_project_id = ? AND f.gitlab_mr_iid = ?
        """.trimIndent()
        return executeQuery(sql, projectId, mrIid) { mapRowWithGitLab(it) }
    }

    /**
     * 여러 프로젝트+MR에 대한 GitLab 피드백을 한 번에 조회 (batch)
     * @param projectMrPairs (projectId, mrIid) 쌍의 리스트
     * @return "projectId:mrIid"를 키로 하는 맵
     */
    fun findGitLabFeedbackByProjectMrs(projectMrPairs: List<Pair<String, Int>>): Map<String, List<FeedbackRecord>> {
        if (projectMrPairs.isEmpty()) return emptyMap()

        // 각 쌍에 대해 OR 조건으로 쿼리
        val conditions = projectMrPairs.joinToString(" OR ") {
            "(gitlab_project_id = ? AND gitlab_mr_iid = ?)"
        }
        val params = projectMrPairs.flatMap { listOf(it.first, it.second) }.toTypedArray()

        val sql = """
            SELECT f.*, f.gitlab_project_id, f.gitlab_mr_iid, f.gitlab_note_id, f.comment
            FROM feedback f
            WHERE $conditions
        """.trimIndent()

        val feedbacks = executeQuery(sql, *params) { mapRowWithGitLab(it) }

        // projectId:mrIid 형태로 그룹화
        return feedbacks.groupBy { fb ->
            "${fb.gitlabProjectId}:${fb.gitlabMrIid}"
        }
    }

    /**
     * GitLab 관련 컬럼을 포함한 FeedbackRecord 매핑
     */
    private fun mapRowWithGitLab(rs: java.sql.ResultSet): FeedbackRecord {
        return FeedbackRecord(
            id = rs.getString("id"),
            executionId = rs.getString("execution_id"),
            userId = rs.getString("user_id"),
            reaction = rs.getString("reaction"),
            category = rs.getString("category") ?: FeedbackRecord.categorizeReaction(rs.getString("reaction")),
            source = rs.getString("source") ?: "unknown",
            isVerified = rs.getInt("is_verified") == 1,
            verifiedAt = rs.getString("verified_at")?.let { java.time.Instant.parse(it) },
            createdAt = java.time.Instant.parse(rs.getString("created_at")),
            gitlabProjectId = rs.getString("gitlab_project_id"),
            gitlabMrIid = rs.getInt("gitlab_mr_iid").takeIf { !rs.wasNull() },
            gitlabNoteId = rs.getInt("gitlab_note_id").takeIf { !rs.wasNull() }
        )
    }

    /**
     * GitLab 피드백 통계
     */
    fun getGitLabFeedbackStats(dateRange: DateRange? = null): FeedbackStats {
        val baseSql = """
            SELECT
                SUM(CASE WHEN reaction IN ('thumbsup', '+1') THEN 1 ELSE 0 END) as positive,
                SUM(CASE WHEN reaction IN ('thumbsdown', '-1') THEN 1 ELSE 0 END) as negative
            FROM feedback
            WHERE source IN ('gitlab_emoji', 'gitlab_note')
        """.trimIndent()

        val sql = if (dateRange != null) {
            "$baseSql AND created_at BETWEEN ? AND ?"
        } else {
            baseSql
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

        val total = positive + negative
        val satisfactionRate = if (total > 0) positive.toDouble() / total else 0.0

        return FeedbackStats(
            positive = positive,
            negative = negative,
            satisfactionRate = satisfactionRate,
            pendingFeedback = 0  // GitLab 피드백은 pending 개념 없음
        )
    }
}
