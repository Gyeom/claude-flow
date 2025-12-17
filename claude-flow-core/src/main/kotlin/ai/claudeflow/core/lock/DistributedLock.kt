package ai.claudeflow.core.lock

import ai.claudeflow.core.storage.ConnectionProvider
import mu.KotlinLogging
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 분산 락 서비스
 *
 * SQLite 기반의 분산 락으로 동시 작업 충돌을 방지합니다.
 * 주요 사용 사례:
 * - 사용자 컨텍스트 요약
 * - 프로젝트별 작업
 * - 에이전트 실행 제한
 *
 * @property connectionProvider DB 연결 제공자
 * @property defaultTtlSeconds 기본 락 TTL (초)
 */
class DistributedLockService(
    private val connectionProvider: ConnectionProvider,
    private val defaultTtlSeconds: Long = 300L  // 5분
) {
    // 로컬 락 캐시 (성능 최적화)
    private val localLocks = ConcurrentHashMap<String, LockInfo>()

    init {
        // 락 테이블 생성
        initTable()
    }

    private fun initTable() {
        connectionProvider.getConnection().createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS distributed_locks (
                    lock_key TEXT PRIMARY KEY,
                    lock_id TEXT NOT NULL,
                    owner TEXT,
                    acquired_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    metadata TEXT
                )
            """)
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_locks_expires ON distributed_locks(expires_at)")
        }
    }

    /**
     * 락 획득 시도
     *
     * @param key 락 키
     * @param owner 락 소유자 (식별용)
     * @param ttlSeconds TTL (초)
     * @return 락 정보 (성공 시) 또는 null (실패 시)
     */
    fun acquire(key: String, owner: String? = null, ttlSeconds: Long = defaultTtlSeconds): LockInfo? {
        val lockId = UUID.randomUUID().toString()
        val now = Instant.now()
        val expiresAt = now.plusSeconds(ttlSeconds)

        // 만료된 락 정리
        cleanupExpired()

        val conn = connectionProvider.getConnection()
        return try {
            // 1. 기존 락 확인
            val existingLock = getLock(key)
            if (existingLock != null && !existingLock.isExpired()) {
                logger.debug { "Lock already held: $key (by ${existingLock.owner})" }
                return null
            }

            // 2. 락 획득 (INSERT OR REPLACE)
            conn.prepareStatement("""
                INSERT OR REPLACE INTO distributed_locks
                (lock_key, lock_id, owner, acquired_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
            """).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, lockId)
                stmt.setString(3, owner)
                stmt.setString(4, now.toString())
                stmt.setString(5, expiresAt.toString())
                stmt.executeUpdate()
            }

            val lockInfo = LockInfo(
                key = key,
                lockId = lockId,
                owner = owner,
                acquiredAt = now,
                expiresAt = expiresAt
            )

            localLocks[key] = lockInfo
            logger.info { "Lock acquired: $key (lockId: $lockId, owner: $owner, TTL: ${ttlSeconds}s)" }
            lockInfo
        } catch (e: Exception) {
            logger.error(e) { "Failed to acquire lock: $key" }
            null
        }
    }

    /**
     * 락 해제
     *
     * @param key 락 키
     * @param lockId 락 ID (검증용)
     * @return 성공 여부
     */
    fun release(key: String, lockId: String): Boolean {
        val conn = connectionProvider.getConnection()
        return try {
            val deleted = conn.prepareStatement("""
                DELETE FROM distributed_locks
                WHERE lock_key = ? AND lock_id = ?
            """).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, lockId)
                stmt.executeUpdate() > 0
            }

            if (deleted) {
                localLocks.remove(key)
                logger.info { "Lock released: $key (lockId: $lockId)" }
            } else {
                logger.warn { "Lock not found or mismatched: $key (lockId: $lockId)" }
            }
            deleted
        } catch (e: Exception) {
            logger.error(e) { "Failed to release lock: $key" }
            false
        }
    }

    /**
     * 락 갱신 (TTL 연장)
     *
     * @param key 락 키
     * @param lockId 락 ID (검증용)
     * @param ttlSeconds 새 TTL (초)
     * @return 성공 여부
     */
    fun refresh(key: String, lockId: String, ttlSeconds: Long = defaultTtlSeconds): Boolean {
        val expiresAt = Instant.now().plusSeconds(ttlSeconds)
        val conn = connectionProvider.getConnection()

        return try {
            val updated = conn.prepareStatement("""
                UPDATE distributed_locks
                SET expires_at = ?
                WHERE lock_key = ? AND lock_id = ?
            """).use { stmt ->
                stmt.setString(1, expiresAt.toString())
                stmt.setString(2, key)
                stmt.setString(3, lockId)
                stmt.executeUpdate() > 0
            }

            if (updated) {
                localLocks[key]?.let {
                    localLocks[key] = it.copy(expiresAt = expiresAt)
                }
                logger.debug { "Lock refreshed: $key (new TTL: ${ttlSeconds}s)" }
            }
            updated
        } catch (e: Exception) {
            logger.error(e) { "Failed to refresh lock: $key" }
            false
        }
    }

    /**
     * 락 정보 조회
     */
    fun getLock(key: String): LockInfo? {
        // 로컬 캐시 확인
        localLocks[key]?.let { local ->
            if (!local.isExpired()) return local
            localLocks.remove(key)
        }

        val conn = connectionProvider.getConnection()
        return try {
            conn.prepareStatement("""
                SELECT lock_key, lock_id, owner, acquired_at, expires_at, metadata
                FROM distributed_locks
                WHERE lock_key = ?
            """).use { stmt ->
                stmt.setString(1, key)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    LockInfo(
                        key = rs.getString("lock_key"),
                        lockId = rs.getString("lock_id"),
                        owner = rs.getString("owner"),
                        acquiredAt = Instant.parse(rs.getString("acquired_at")),
                        expiresAt = Instant.parse(rs.getString("expires_at")),
                        metadata = rs.getString("metadata")
                    )
                } else null
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get lock: $key" }
            null
        }
    }

    /**
     * 락 보유 여부 확인
     */
    fun isHeld(key: String): Boolean {
        val lock = getLock(key)
        return lock != null && !lock.isExpired()
    }

    /**
     * 만료된 락 정리
     */
    fun cleanupExpired(): Int {
        val conn = connectionProvider.getConnection()
        return try {
            val deleted = conn.prepareStatement("""
                DELETE FROM distributed_locks
                WHERE expires_at < ?
            """).use { stmt ->
                stmt.setString(1, Instant.now().toString())
                stmt.executeUpdate()
            }

            if (deleted > 0) {
                // 로컬 캐시 정리
                localLocks.entries.removeIf { it.value.isExpired() }
                logger.info { "Cleaned up $deleted expired locks" }
            }
            deleted
        } catch (e: Exception) {
            logger.error(e) { "Failed to cleanup expired locks" }
            0
        }
    }

    /**
     * 모든 락 조회 (디버깅용)
     */
    fun listAll(): List<LockInfo> {
        val conn = connectionProvider.getConnection()
        return try {
            conn.prepareStatement("""
                SELECT lock_key, lock_id, owner, acquired_at, expires_at, metadata
                FROM distributed_locks
                ORDER BY acquired_at DESC
            """).use { stmt ->
                val rs = stmt.executeQuery()
                val locks = mutableListOf<LockInfo>()
                while (rs.next()) {
                    locks.add(LockInfo(
                        key = rs.getString("lock_key"),
                        lockId = rs.getString("lock_id"),
                        owner = rs.getString("owner"),
                        acquiredAt = Instant.parse(rs.getString("acquired_at")),
                        expiresAt = Instant.parse(rs.getString("expires_at")),
                        metadata = rs.getString("metadata")
                    ))
                }
                locks
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to list locks" }
            emptyList()
        }
    }

    /**
     * 락을 획득하고 작업 실행 (자동 해제)
     */
    suspend fun <T> withLock(
        key: String,
        owner: String? = null,
        ttlSeconds: Long = defaultTtlSeconds,
        block: suspend () -> T
    ): T? {
        val lock = acquire(key, owner, ttlSeconds) ?: return null
        return try {
            block()
        } finally {
            release(key, lock.lockId)
        }
    }
}

/**
 * 락 정보
 */
data class LockInfo(
    val key: String,
    val lockId: String,
    val owner: String?,
    val acquiredAt: Instant,
    val expiresAt: Instant,
    val metadata: String? = null
) {
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)

    fun remainingSeconds(): Long {
        val remaining = expiresAt.epochSecond - Instant.now().epochSecond
        return if (remaining > 0) remaining else 0
    }
}

/**
 * 락 키 생성 헬퍼
 */
object LockKeys {
    fun userSummary(userId: String) = "user:summary:$userId"
    fun projectExecution(projectId: String) = "project:execution:$projectId"
    fun agentExecution(agentId: String) = "agent:execution:$agentId"
    fun rateLimit(scope: String, id: String) = "rate:$scope:$id"
    fun custom(namespace: String, id: String) = "$namespace:$id"
}
