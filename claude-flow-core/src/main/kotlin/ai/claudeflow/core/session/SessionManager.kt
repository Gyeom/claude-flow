package ai.claudeflow.core.session

import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 세션 관리자
 *
 * Slack 스레드 기반 대화 세션 관리
 * - 스레드 ID를 키로 세션 유지
 * - 세션별 Claude 세션 ID 저장
 * - TTL 기반 자동 정리
 */
class SessionManager(
    private val sessionTtlMinutes: Long = 60,  // 기본 1시간
    private val maxSessions: Int = 1000
) {
    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * 세션 생성 또는 조회
     */
    fun getOrCreate(
        threadId: String,
        channel: String,
        userId: String
    ): Session {
        cleanupExpired()

        return sessions.getOrPut(threadId) {
            val session = Session(
                id = threadId,
                channel = channel,
                userId = userId,
                createdAt = Instant.now(),
                lastActivityAt = Instant.now()
            )
            logger.info { "Created new session: $threadId" }
            session
        }.also {
            it.lastActivityAt = Instant.now()
        }
    }

    /**
     * 세션 조회
     */
    fun get(threadId: String): Session? {
        return sessions[threadId]?.also {
            it.lastActivityAt = Instant.now()
        }
    }

    /**
     * Claude 세션 ID 저장
     */
    fun setClaudeSessionId(threadId: String, claudeSessionId: String) {
        sessions[threadId]?.let { session ->
            session.claudeSessionId = claudeSessionId
            session.lastActivityAt = Instant.now()
            logger.debug { "Set Claude session ID for $threadId: $claudeSessionId" }
        }
    }

    /**
     * 세션에 메시지 추가
     */
    fun addMessage(threadId: String, role: String, content: String) {
        sessions[threadId]?.let { session ->
            session.messages.add(
                SessionMessage(
                    role = role,
                    content = content,
                    timestamp = Instant.now()
                )
            )
            session.lastActivityAt = Instant.now()

            // 메시지 수 제한 (최근 50개만 유지)
            while (session.messages.size > 50) {
                session.messages.removeAt(0)
            }
        }
    }

    /**
     * 세션 컨텍스트 조회 (대화 히스토리)
     */
    fun getContext(threadId: String, maxMessages: Int = 10): List<SessionMessage> {
        return sessions[threadId]?.messages?.takeLast(maxMessages) ?: emptyList()
    }

    /**
     * 세션 메타데이터 설정
     */
    fun setMetadata(threadId: String, key: String, value: Any) {
        sessions[threadId]?.let { session ->
            session.metadata[key] = value
            session.lastActivityAt = Instant.now()
        }
    }

    /**
     * 세션 메타데이터 조회
     */
    fun getMetadata(threadId: String, key: String): Any? {
        return sessions[threadId]?.metadata?.get(key)
    }

    /**
     * 세션 종료
     */
    fun close(threadId: String) {
        sessions.remove(threadId)?.let {
            logger.info { "Closed session: $threadId" }
        }
    }

    /**
     * 활성 세션 수
     */
    fun activeCount(): Int = sessions.size

    /**
     * 만료된 세션 정리
     */
    private fun cleanupExpired() {
        val now = Instant.now()
        val expiredBefore = now.minusSeconds(sessionTtlMinutes * 60)

        val expired = sessions.filter { (_, session) ->
            session.lastActivityAt.isBefore(expiredBefore)
        }

        expired.forEach { (id, _) ->
            sessions.remove(id)
            logger.debug { "Cleaned up expired session: $id" }
        }

        if (expired.isNotEmpty()) {
            logger.info { "Cleaned up ${expired.size} expired sessions" }
        }

        // 최대 세션 수 초과 시 가장 오래된 세션 제거
        if (sessions.size > maxSessions) {
            val toRemove = sessions.entries
                .sortedBy { it.value.lastActivityAt }
                .take(sessions.size - maxSessions)

            toRemove.forEach { (id, _) ->
                sessions.remove(id)
            }
            logger.info { "Removed ${toRemove.size} oldest sessions due to capacity limit" }
        }
    }

    /**
     * 세션 통계
     */
    fun getStats(): SessionStats {
        val now = Instant.now()
        val activeThreshold = now.minusSeconds(5 * 60)  // 5분 이내

        val activeSessions = sessions.values.count { it.lastActivityAt.isAfter(activeThreshold) }
        val totalMessages = sessions.values.sumOf { it.messages.size }
        val avgMessagesPerSession = if (sessions.isNotEmpty()) {
            totalMessages.toDouble() / sessions.size
        } else 0.0

        return SessionStats(
            totalSessions = sessions.size,
            activeSessions = activeSessions,
            totalMessages = totalMessages,
            avgMessagesPerSession = avgMessagesPerSession
        )
    }
}

/**
 * 세션 데이터
 */
data class Session(
    val id: String,
    val channel: String,
    val userId: String,
    val createdAt: Instant,
    var lastActivityAt: Instant,
    var claudeSessionId: String? = null,
    val messages: MutableList<SessionMessage> = mutableListOf(),
    val metadata: MutableMap<String, Any> = mutableMapOf()
)

/**
 * 세션 메시지
 */
data class SessionMessage(
    val role: String,  // "user" or "assistant"
    val content: String,
    val timestamp: Instant
)

/**
 * 세션 통계
 */
data class SessionStats(
    val totalSessions: Int,
    val activeSessions: Int,
    val totalMessages: Int,
    val avgMessagesPerSession: Double
)
