package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.BaseRepository
import ai.claudeflow.core.storage.ConnectionProvider
import ai.claudeflow.core.storage.DateRange
import ai.claudeflow.core.storage.query.QueryBuilder
import java.sql.ResultSet
import java.time.Instant

/**
 * 세션 저장소
 *
 * Slack 스레드 기반 대화 세션의 영속화를 담당합니다.
 * 앱 재시작 후에도 세션 컨텍스트가 유지됩니다.
 */
class SessionRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<SessionEntity, String>(connectionProvider) {

    override val tableName: String = "sessions"
    override val primaryKeyColumn: String = "id"

    override fun mapRow(rs: ResultSet): SessionEntity {
        return SessionEntity(
            id = rs.getString("id"),
            channel = rs.getString("channel"),
            userId = rs.getString("user_id"),
            claudeSessionId = rs.getString("claude_session_id"),
            createdAt = Instant.parse(rs.getString("created_at")),
            lastActivityAt = Instant.parse(rs.getString("last_activity_at"))
        )
    }

    override fun getId(entity: SessionEntity): String = entity.id

    override fun save(entity: SessionEntity) {
        // UPSERT: INSERT OR REPLACE
        val sql = """
            INSERT OR REPLACE INTO sessions (id, channel, user_id, claude_session_id, created_at, last_activity_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """
        executeUpdate(
            sql,
            entity.id,
            entity.channel,
            entity.userId,
            entity.claudeSessionId,
            entity.createdAt.toString(),
            entity.lastActivityAt.toString()
        )
    }

    /**
     * Claude 세션 ID 업데이트
     */
    fun updateClaudeSessionId(sessionId: String, claudeSessionId: String): Boolean {
        return update()
            .set("claude_session_id", claudeSessionId)
            .set("last_activity_at", Instant.now().toString())
            .where("id = ?", sessionId)
            .execute() > 0
    }

    /**
     * 마지막 활동 시간 업데이트
     */
    fun updateLastActivity(sessionId: String): Boolean {
        return update()
            .set("last_activity_at", Instant.now().toString())
            .where("id = ?", sessionId)
            .execute() > 0
    }

    /**
     * 활성 세션 조회 (지정된 분 이내 활동)
     */
    fun findActiveSessions(withinMinutes: Long): List<SessionEntity> {
        val threshold = Instant.now().minusSeconds(withinMinutes * 60)
        return query()
            .select("*")
            .where("last_activity_at > ?", threshold.toString())
            .orderBy("last_activity_at", QueryBuilder.SortDirection.DESC)
            .execute { mapRow(it) }
    }

    /**
     * 채널별 세션 조회
     */
    fun findByChannel(channel: String): List<SessionEntity> {
        return query()
            .select("*")
            .where("channel = ?", channel)
            .orderBy("last_activity_at", QueryBuilder.SortDirection.DESC)
            .execute { mapRow(it) }
    }

    /**
     * 사용자별 세션 조회
     */
    fun findByUserId(userId: String): List<SessionEntity> {
        return query()
            .select("*")
            .where("user_id = ?", userId)
            .orderBy("last_activity_at", QueryBuilder.SortDirection.DESC)
            .execute { mapRow(it) }
    }

    /**
     * 만료된 세션 삭제
     */
    fun deleteExpired(ttlMinutes: Long): Int {
        val threshold = Instant.now().minusSeconds(ttlMinutes * 60)
        return delete()
            .where("last_activity_at < ?", threshold.toString())
            .execute()
    }

    /**
     * 최대 세션 수 초과 시 오래된 세션 삭제
     */
    fun deleteOldest(keepCount: Int): Int {
        // 가장 오래된 세션 ID 조회 후 삭제
        val oldestIds = executeQuery(
            """
            SELECT id FROM sessions
            ORDER BY last_activity_at DESC
            LIMIT -1 OFFSET ?
            """.trimIndent(),
            keepCount
        ) { it.getString("id") }

        if (oldestIds.isEmpty()) return 0

        val placeholders = oldestIds.joinToString(",") { "?" }
        return executeUpdate(
            "DELETE FROM sessions WHERE id IN ($placeholders)",
            *oldestIds.toTypedArray()
        )
    }

    /**
     * 세션 통계
     */
    fun getStats(activeThresholdMinutes: Long = 5): SessionStats {
        val activeThreshold = Instant.now().minusSeconds(activeThresholdMinutes * 60)

        return executeQueryOne(
            """
            SELECT
                COUNT(*) as total_sessions,
                SUM(CASE WHEN last_activity_at > ? THEN 1 ELSE 0 END) as active_sessions
            FROM sessions
            """.trimIndent(),
            activeThreshold.toString()
        ) {
            SessionStats(
                totalSessions = it.getInt("total_sessions"),
                activeSessions = it.getInt("active_sessions")
            )
        } ?: SessionStats(0, 0)
    }
}

/**
 * 세션 메시지 저장소
 */
class SessionMessageRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<SessionMessageEntity, Long>(connectionProvider) {

    override val tableName: String = "session_messages"
    override val primaryKeyColumn: String = "id"

    override fun mapRow(rs: ResultSet): SessionMessageEntity {
        return SessionMessageEntity(
            id = rs.getLong("id"),
            sessionId = rs.getString("session_id"),
            role = rs.getString("role"),
            content = rs.getString("content"),
            timestamp = Instant.parse(rs.getString("timestamp"))
        )
    }

    override fun getId(entity: SessionMessageEntity): Long = entity.id

    override fun save(entity: SessionMessageEntity) {
        insert()
            .columns(
                "session_id" to entity.sessionId,
                "role" to entity.role,
                "content" to entity.content,
                "timestamp" to entity.timestamp.toString()
            )
            .execute()
    }

    /**
     * 세션의 메시지 조회 (최신순)
     */
    fun findBySessionId(sessionId: String, limit: Int? = null): List<SessionMessageEntity> {
        val q = query()
            .select("*")
            .where("session_id = ?", sessionId)
            .orderBy("timestamp", QueryBuilder.SortDirection.ASC)

        limit?.let { q.limit(it) }

        return q.execute { mapRow(it) }
    }

    /**
     * 세션의 최근 메시지 조회
     */
    fun findRecentBySessionId(sessionId: String, limit: Int = 10): List<SessionMessageEntity> {
        return executeQuery(
            """
            SELECT * FROM session_messages
            WHERE session_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
            """.trimIndent(),
            sessionId, limit
        ) { mapRow(it) }.reversed()  // 시간순으로 정렬
    }

    /**
     * 세션의 메시지 수
     */
    fun countBySessionId(sessionId: String): Long {
        return countWhere("session_id = ?", sessionId)
    }

    /**
     * 오래된 메시지 삭제 (세션당 최대 N개 유지)
     */
    fun deleteOldMessages(sessionId: String, keepCount: Int): Int {
        return executeUpdate(
            """
            DELETE FROM session_messages
            WHERE session_id = ? AND id NOT IN (
                SELECT id FROM session_messages
                WHERE session_id = ?
                ORDER BY timestamp DESC
                LIMIT ?
            )
            """.trimIndent(),
            sessionId, sessionId, keepCount
        )
    }

    /**
     * 세션의 모든 메시지 삭제
     */
    fun deleteBySessionId(sessionId: String): Int {
        return delete()
            .where("session_id = ?", sessionId)
            .execute()
    }

    /**
     * 총 메시지 수 통계
     */
    fun getTotalMessageCount(): Long {
        return count()
    }
}

// ==================== Data Classes ====================

/**
 * 세션 엔티티
 */
data class SessionEntity(
    val id: String,
    val channel: String,
    val userId: String,
    val claudeSessionId: String? = null,
    val createdAt: Instant,
    val lastActivityAt: Instant
)

/**
 * 세션 메시지 엔티티
 */
data class SessionMessageEntity(
    val id: Long = 0,
    val sessionId: String,
    val role: String,  // "user" or "assistant"
    val content: String,
    val timestamp: Instant
)

/**
 * 세션 통계
 */
data class SessionStats(
    val totalSessions: Int,
    val activeSessions: Int
)
