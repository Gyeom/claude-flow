package ai.claudeflow.core.session

import ai.claudeflow.core.storage.repository.SessionEntity
import ai.claudeflow.core.storage.repository.SessionMessageEntity
import ai.claudeflow.core.storage.repository.SessionMessageRepository
import ai.claudeflow.core.storage.repository.SessionRepository
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
 * - DB 영속화 + 메모리 캐시 하이브리드 방식
 * - TTL 기반 자동 정리
 */
class SessionManager(
    private val sessionRepository: SessionRepository? = null,
    private val sessionMessageRepository: SessionMessageRepository? = null,
    private val sessionTtlMinutes: Long = 60,  // 기본 1시간
    private val maxSessions: Int = 1000,
    private val maxMessagesPerSession: Int = 50
) {
    // 성능을 위한 메모리 캐시 (DB와 동기화)
    private val sessionCache = ConcurrentHashMap<String, Session>()

    init {
        // 영속화 모드면 DB에서 활성 세션 로드
        if (sessionRepository != null) {
            loadActiveSessionsFromDb()
        }
    }

    /**
     * DB에서 활성 세션 로드 (앱 시작 시)
     */
    private fun loadActiveSessionsFromDb() {
        try {
            val activeSessions = sessionRepository?.findActiveSessions(sessionTtlMinutes) ?: return
            for (entity in activeSessions) {
                val messages = sessionMessageRepository?.findBySessionId(entity.id) ?: emptyList()
                val session = Session(
                    id = entity.id,
                    channel = entity.channel,
                    userId = entity.userId,
                    createdAt = entity.createdAt,
                    lastActivityAt = entity.lastActivityAt,
                    claudeSessionId = entity.claudeSessionId,
                    messages = messages.map { msg ->
                        SessionMessage(
                            role = msg.role,
                            content = msg.content,
                            timestamp = msg.timestamp
                        )
                    }.toMutableList()
                )
                sessionCache[entity.id] = session
            }
            logger.info { "Loaded ${activeSessions.size} active sessions from database" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load sessions from database, starting fresh" }
        }
    }

    /**
     * 세션 생성 또는 조회
     */
    fun getOrCreate(
        threadId: String,
        channel: String,
        userId: String
    ): Session {
        cleanupExpired()

        return sessionCache.getOrPut(threadId) {
            // DB에서 먼저 조회
            val existingEntity = sessionRepository?.findById(threadId)
            if (existingEntity != null) {
                val messages = sessionMessageRepository?.findBySessionId(threadId) ?: emptyList()
                return@getOrPut Session(
                    id = existingEntity.id,
                    channel = existingEntity.channel,
                    userId = existingEntity.userId,
                    createdAt = existingEntity.createdAt,
                    lastActivityAt = Instant.now(),
                    claudeSessionId = existingEntity.claudeSessionId,
                    messages = messages.map { msg ->
                        SessionMessage(msg.role, msg.content, msg.timestamp)
                    }.toMutableList()
                ).also {
                    // 활동 시간 업데이트
                    sessionRepository?.updateLastActivity(threadId)
                }
            }

            // 새 세션 생성
            val session = Session(
                id = threadId,
                channel = channel,
                userId = userId,
                createdAt = Instant.now(),
                lastActivityAt = Instant.now()
            )

            // DB에 저장
            persistSession(session)

            logger.info { "Created new session: $threadId" }
            session
        }.also {
            it.lastActivityAt = Instant.now()
            sessionRepository?.updateLastActivity(threadId)
        }
    }

    /**
     * 세션 조회
     */
    fun get(threadId: String): Session? {
        return sessionCache[threadId]?.also {
            it.lastActivityAt = Instant.now()
            sessionRepository?.updateLastActivity(threadId)
        }
    }

    /**
     * Claude 세션 ID 저장
     */
    fun setClaudeSessionId(threadId: String, claudeSessionId: String) {
        sessionCache[threadId]?.let { session ->
            session.claudeSessionId = claudeSessionId
            session.lastActivityAt = Instant.now()

            // DB 업데이트
            sessionRepository?.updateClaudeSessionId(threadId, claudeSessionId)

            logger.debug { "Set Claude session ID for $threadId: $claudeSessionId" }
        }
    }

    /**
     * 세션에 메시지 추가
     */
    fun addMessage(threadId: String, role: String, content: String) {
        sessionCache[threadId]?.let { session ->
            val message = SessionMessage(
                role = role,
                content = content,
                timestamp = Instant.now()
            )
            session.messages.add(message)
            session.lastActivityAt = Instant.now()

            // 메시지 수 제한 (최근 N개만 유지)
            while (session.messages.size > maxMessagesPerSession) {
                session.messages.removeAt(0)
            }

            // DB에 메시지 저장
            sessionMessageRepository?.save(SessionMessageEntity(
                sessionId = threadId,
                role = role,
                content = content,
                timestamp = message.timestamp
            ))

            // DB에서도 오래된 메시지 정리
            sessionMessageRepository?.deleteOldMessages(threadId, maxMessagesPerSession)

            sessionRepository?.updateLastActivity(threadId)
        }
    }

    /**
     * 세션 컨텍스트 조회 (대화 히스토리)
     */
    fun getContext(threadId: String, maxMessages: Int = 10): List<SessionMessage> {
        return sessionCache[threadId]?.messages?.takeLast(maxMessages) ?: emptyList()
    }

    /**
     * 세션 메타데이터 설정
     */
    fun setMetadata(threadId: String, key: String, value: Any) {
        sessionCache[threadId]?.let { session ->
            session.metadata[key] = value
            session.lastActivityAt = Instant.now()
            sessionRepository?.updateLastActivity(threadId)
        }
    }

    /**
     * 세션 메타데이터 조회
     */
    fun getMetadata(threadId: String, key: String): Any? {
        return sessionCache[threadId]?.metadata?.get(key)
    }

    /**
     * 세션 종료
     */
    fun close(threadId: String) {
        sessionCache.remove(threadId)?.let {
            // DB에서도 삭제
            sessionMessageRepository?.deleteBySessionId(threadId)
            sessionRepository?.deleteById(threadId)
            logger.info { "Closed session: $threadId" }
        }
    }

    /**
     * 활성 세션 수
     */
    fun activeCount(): Int = sessionCache.size

    /**
     * 만료된 세션 정리
     */
    private fun cleanupExpired() {
        val now = Instant.now()
        val expiredBefore = now.minusSeconds(sessionTtlMinutes * 60)

        val expired = sessionCache.filter { (_, session) ->
            session.lastActivityAt.isBefore(expiredBefore)
        }

        expired.forEach { (id, _) ->
            sessionCache.remove(id)
        }

        if (expired.isNotEmpty()) {
            logger.info { "Cleaned up ${expired.size} expired sessions from cache" }

            // DB에서도 만료된 세션 삭제
            sessionRepository?.deleteExpired(sessionTtlMinutes)
        }

        // 최대 세션 수 초과 시 가장 오래된 세션 제거
        if (sessionCache.size > maxSessions) {
            val toRemove = sessionCache.entries
                .sortedBy { it.value.lastActivityAt }
                .take(sessionCache.size - maxSessions)

            toRemove.forEach { (id, _) ->
                sessionCache.remove(id)
            }
            logger.info { "Removed ${toRemove.size} oldest sessions from cache due to capacity limit" }

            // DB에서도 오래된 세션 삭제
            sessionRepository?.deleteOldest(maxSessions)
        }
    }

    /**
     * 세션 DB 저장
     */
    private fun persistSession(session: Session) {
        sessionRepository?.save(SessionEntity(
            id = session.id,
            channel = session.channel,
            userId = session.userId,
            claudeSessionId = session.claudeSessionId,
            createdAt = session.createdAt,
            lastActivityAt = session.lastActivityAt
        ))
    }

    /**
     * 세션 통계
     */
    fun getStats(): SessionStats {
        val now = Instant.now()
        val activeThreshold = now.minusSeconds(5 * 60)  // 5분 이내

        val activeSessions = sessionCache.values.count { it.lastActivityAt.isAfter(activeThreshold) }
        val totalMessages = sessionCache.values.sumOf { it.messages.size }
        val avgMessagesPerSession = if (sessionCache.isNotEmpty()) {
            totalMessages.toDouble() / sessionCache.size
        } else 0.0

        return SessionStats(
            totalSessions = sessionCache.size,
            activeSessions = activeSessions,
            totalMessages = totalMessages,
            avgMessagesPerSession = avgMessagesPerSession
        )
    }

    /**
     * 영속화 모드 여부
     */
    fun isPersistenceEnabled(): Boolean = sessionRepository != null
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
