package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.*
import ai.claudeflow.core.storage.query.QueryBuilder
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Repository for user context management
 */
class UserContextRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<UserContext, String>(connectionProvider) {

    override val tableName: String = "user_contexts"
    override val primaryKeyColumn: String = "user_id"

    override fun mapRow(rs: ResultSet): UserContext {
        return UserContext(
            userId = rs.getString("user_id"),
            displayName = rs.getString("display_name"),
            preferredLanguage = rs.getString("preferred_language") ?: "ko",
            domain = rs.getString("domain"),
            lastSeen = Instant.parse(rs.getString("last_seen")),
            totalInteractions = rs.getInt("total_interactions"),
            summary = rs.getString("summary"),
            summaryUpdatedAt = rs.getString("summary_updated_at")?.let { Instant.parse(it) },
            summaryLockId = rs.getString("summary_lock_id"),
            summaryLockAt = rs.getString("summary_lock_at")?.let { Instant.parse(it) },
            totalChars = rs.getLong("total_chars")
        )
    }

    override fun getId(entity: UserContext): String = entity.userId

    override fun save(entity: UserContext) {
        insert()
            .columns(
                "user_id" to entity.userId,
                "display_name" to entity.displayName,
                "preferred_language" to entity.preferredLanguage,
                "domain" to entity.domain,
                "last_seen" to entity.lastSeen.toString(),
                "total_interactions" to entity.totalInteractions,
                "summary" to entity.summary,
                "summary_updated_at" to entity.summaryUpdatedAt?.toString(),
                "summary_lock_id" to entity.summaryLockId,
                "summary_lock_at" to entity.summaryLockAt?.toString(),
                "total_chars" to entity.totalChars
            )
            .executeOrReplace()
    }

    override fun findAll(): List<UserContext> {
        return query()
            .select("*")
            .orderBy("last_seen", QueryBuilder.SortDirection.DESC)
            .execute { mapRow(it) }
    }

    /**
     * Get user context with full response data
     */
    fun getUserContextResponse(
        userId: String,
        acquireLock: Boolean = false,
        lockId: String? = null,
        userRuleRepository: UserRuleRepository,
        executionRepository: ExecutionRepository
    ): UserContextResponse {
        val context = findById(userId)
        val rules = userRuleRepository.findRulesByUserId(userId)
        val recentConversations = getRecentConversations(userId, executionRepository)
        val conversationCount = executionRepository.countByUserId(userId).toInt()

        val needsSummary = context?.let {
            UserContextResponse.needsSummary(
                it.totalChars,
                conversationCount,
                it.summaryUpdatedAt,
                it.summary
            )
        } ?: false

        var newLockId: String? = null
        var summaryLocked = context?.summaryLockId != null &&
            context.summaryLockAt?.let {
                Instant.now().epochSecond - it.epochSecond < UserContextResponse.SUMMARY_LOCK_TTL_SECS
            } ?: false

        if (acquireLock && needsSummary && !summaryLocked) {
            newLockId = lockId ?: UUID.randomUUID().toString()
            acquireSummaryLock(userId, newLockId)
            summaryLocked = true
        }

        return UserContextResponse(
            rules = rules,
            summary = context?.summary,
            recentConversations = recentConversations,
            totalConversationCount = conversationCount,
            needsSummary = needsSummary,
            summaryLocked = summaryLocked,
            lockId = newLockId
        )
    }

    /**
     * Save user summary
     */
    fun saveUserSummary(userId: String, summary: String): Boolean {
        return update()
            .set("summary", summary)
            .set("summary_updated_at", Instant.now().toString())
            .set("summary_lock_id", null)
            .set("summary_lock_at", null)
            .where("user_id = ?", userId)
            .execute() > 0
    }

    /**
     * Acquire summary lock
     */
    fun acquireSummaryLock(userId: String, lockId: String): Boolean {
        val now = Instant.now()
        val expiredBefore = now.minusSeconds(UserContextResponse.SUMMARY_LOCK_TTL_SECS)

        return executeUpdate(
            """
            UPDATE user_contexts
            SET summary_lock_id = ?, summary_lock_at = ?
            WHERE user_id = ? AND (summary_lock_id IS NULL OR summary_lock_at < ?)
            """.trimIndent(),
            lockId, now.toString(), userId, expiredBefore.toString()
        ) > 0
    }

    /**
     * Release summary lock
     */
    fun releaseSummaryLock(userId: String, lockId: String): Boolean {
        return executeUpdate(
            """
            UPDATE user_contexts
            SET summary_lock_id = NULL, summary_lock_at = NULL
            WHERE user_id = ? AND summary_lock_id = ?
            """.trimIndent(),
            userId, lockId
        ) > 0
    }

    /**
     * Get recent conversations for user
     */
    fun getRecentConversations(
        userId: String,
        executionRepository: ExecutionRepository,
        limit: Int = 10
    ): List<RecentConversation> {
        return executeQuery(
            """
            SELECT e.id, e.prompt, e.result, e.created_at,
                   EXISTS(SELECT 1 FROM feedback f WHERE f.execution_id = e.id) as has_reactions
            FROM executions e
            WHERE e.user_id = ?
            ORDER BY e.created_at DESC
            LIMIT ?
            """.trimIndent(),
            userId, limit
        ) {
            RecentConversation(
                id = it.getString("id"),
                userMessage = it.getString("prompt"),
                response = it.getString("result"),
                createdAt = it.getString("created_at"),
                hasReactions = it.getBoolean("has_reactions")
            )
        }
    }

    /**
     * Update last seen timestamp
     */
    fun updateLastSeen(userId: String): Boolean {
        return update()
            .set("last_seen", Instant.now().toString())
            .where("user_id = ?", userId)
            .execute() > 0
    }

    /**
     * Increment interaction count
     */
    fun incrementInteractions(userId: String): Boolean {
        return executeUpdate(
            """
            UPDATE user_contexts
            SET total_interactions = total_interactions + 1, last_seen = ?
            WHERE user_id = ?
            """.trimIndent(),
            Instant.now().toString(), userId
        ) > 0
    }

    /**
     * Update total chars for summarization trigger
     */
    fun addChars(userId: String, charCount: Long): Boolean {
        return executeUpdate(
            """
            UPDATE user_contexts
            SET total_chars = total_chars + ?
            WHERE user_id = ?
            """.trimIndent(),
            charCount, userId
        ) > 0
    }
}
