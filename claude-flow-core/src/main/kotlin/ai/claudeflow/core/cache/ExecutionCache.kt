package ai.claudeflow.core.cache

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.stats.CacheStats
import mu.KotlinLogging
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 3-Level 캐시 시스템
 *
 * L1: In-Memory (Caffeine) - 빠른 액세스, 제한된 용량
 * L2: 확장용 (Redis 등 외부 캐시 연동 가능)
 * L3: Database (SQLite) - 영구 저장
 *
 * 캐시 전략:
 * - 프롬프트 해시 기반: 동일 프롬프트 → 동일 결과
 * - 시맨틱 유사도 기반: 유사한 프롬프트 → 캐시된 결과 재사용 (TODO)
 * - TTL 기반: 시간 경과 후 만료
 */
class ExecutionCache(
    private val maxL1Size: Long = 10_000,
    private val l1TtlMinutes: Long = 60,
    private val l2Enabled: Boolean = false,  // Redis 연동 시 true
    private val l3Enabled: Boolean = true    // SQLite 영구 캐시
) {
    // L1: Caffeine In-Memory Cache
    private val l1Cache: Cache<String, CachedResult> = Caffeine.newBuilder()
        .maximumSize(maxL1Size)
        .expireAfterWrite(Duration.ofMinutes(l1TtlMinutes))
        .recordStats()
        .build()

    // L2: 외부 캐시 (Redis 등) - 인터페이스로 추상화
    private var l2Cache: L2CacheProvider? = null

    // L3: Database 캐시 - Storage를 통해 접근
    private var l3Cache: L3CacheProvider? = null

    /**
     * 캐시 결과 데이터
     */
    data class CachedResult(
        val result: String,
        val sessionId: String?,
        val inputTokens: Int,
        val outputTokens: Int,
        val cost: Double?,
        val cachedAt: Long = System.currentTimeMillis(),
        val ttlMs: Long = 3600_000  // 기본 1시간
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - cachedAt > ttlMs
    }

    /**
     * 캐시 키 전략
     */
    enum class CacheKeyStrategy {
        PROMPT_HASH,       // 프롬프트 텍스트 해시 (정확한 매칭)
        PROMPT_AGENT,      // 프롬프트 + 에이전트 ID
        PROMPT_USER,       // 프롬프트 + 사용자 ID
        SEMANTIC           // 시맨틱 유사도 기반 (TODO)
    }

    /**
     * 캐시 조회 (L1 → L2 → L3 순서)
     */
    fun get(
        prompt: String,
        agentId: String? = null,
        userId: String? = null,
        strategy: CacheKeyStrategy = CacheKeyStrategy.PROMPT_HASH
    ): CachedResult? {
        val key = buildKey(prompt, agentId, userId, strategy)

        // L1: In-Memory
        l1Cache.getIfPresent(key)?.let { result ->
            if (!result.isExpired()) {
                logger.debug { "L1 cache hit: $key" }
                return result
            }
            l1Cache.invalidate(key)
        }

        // L2: External Cache (Redis)
        if (l2Enabled) {
            l2Cache?.get(key)?.let { result ->
                if (!result.isExpired()) {
                    logger.debug { "L2 cache hit: $key" }
                    // L1에 승격
                    l1Cache.put(key, result)
                    return result
                }
            }
        }

        // L3: Database
        if (l3Enabled) {
            l3Cache?.get(key)?.let { result ->
                if (!result.isExpired()) {
                    logger.debug { "L3 cache hit: $key" }
                    // L1에 승격
                    l1Cache.put(key, result)
                    return result
                }
            }
        }

        logger.debug { "Cache miss: $key" }
        return null
    }

    /**
     * 캐시 저장 (모든 레벨에)
     */
    fun put(
        prompt: String,
        result: CachedResult,
        agentId: String? = null,
        userId: String? = null,
        strategy: CacheKeyStrategy = CacheKeyStrategy.PROMPT_HASH
    ) {
        val key = buildKey(prompt, agentId, userId, strategy)

        // L1: 항상 저장
        l1Cache.put(key, result)
        logger.debug { "L1 cache put: $key" }

        // L2: External Cache
        if (l2Enabled) {
            l2Cache?.put(key, result)
            logger.debug { "L2 cache put: $key" }
        }

        // L3: Database
        if (l3Enabled) {
            l3Cache?.put(key, result)
            logger.debug { "L3 cache put: $key" }
        }
    }

    /**
     * 특정 키 무효화
     */
    fun invalidate(
        prompt: String,
        agentId: String? = null,
        userId: String? = null,
        strategy: CacheKeyStrategy = CacheKeyStrategy.PROMPT_HASH
    ) {
        val key = buildKey(prompt, agentId, userId, strategy)
        l1Cache.invalidate(key)
        l2Cache?.invalidate(key)
        l3Cache?.invalidate(key)
        logger.info { "Cache invalidated: $key" }
    }

    /**
     * 사용자별 캐시 전체 무효화
     */
    fun invalidateByUser(userId: String) {
        // L1에서 해당 사용자의 캐시 삭제
        l1Cache.asMap().keys
            .filter { it.contains(":user=$userId") }
            .forEach { l1Cache.invalidate(it) }
        l2Cache?.invalidateByPattern("*:user=$userId*")
        l3Cache?.invalidateByPattern("%:user=$userId%")
        logger.info { "User cache invalidated: $userId" }
    }

    /**
     * 전체 캐시 초기화
     */
    fun clear() {
        l1Cache.invalidateAll()
        l2Cache?.clear()
        // L3 (Database)는 보통 유지
        logger.info { "L1/L2 cache cleared" }
    }

    /**
     * 캐시 통계
     */
    fun getStats(): CacheStatistics {
        val caffeineStats = l1Cache.stats()
        return CacheStatistics(
            l1HitCount = caffeineStats.hitCount(),
            l1MissCount = caffeineStats.missCount(),
            l1HitRate = caffeineStats.hitRate(),
            l1Size = l1Cache.estimatedSize(),
            l1EvictionCount = caffeineStats.evictionCount(),
            l2HitCount = l2Cache?.getHitCount() ?: 0,
            l2MissCount = l2Cache?.getMissCount() ?: 0,
            l3HitCount = l3Cache?.getHitCount() ?: 0,
            l3MissCount = l3Cache?.getMissCount() ?: 0
        )
    }

    /**
     * 캐시 키 생성
     */
    private fun buildKey(
        prompt: String,
        agentId: String?,
        userId: String?,
        strategy: CacheKeyStrategy
    ): String {
        val promptHash = hashPrompt(prompt)
        return when (strategy) {
            CacheKeyStrategy.PROMPT_HASH -> "exec:$promptHash"
            CacheKeyStrategy.PROMPT_AGENT -> "exec:$promptHash:agent=${agentId ?: "default"}"
            CacheKeyStrategy.PROMPT_USER -> "exec:$promptHash:user=${userId ?: "anonymous"}"
            CacheKeyStrategy.SEMANTIC -> "semantic:$promptHash"  // TODO: 임베딩 기반
        }
    }

    /**
     * 프롬프트 해시 (SHA-256)
     */
    private fun hashPrompt(prompt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(prompt.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * L2 캐시 프로바이더 설정 (Redis 등)
     */
    fun setL2Provider(provider: L2CacheProvider) {
        this.l2Cache = provider
        logger.info { "L2 cache provider set: ${provider.javaClass.simpleName}" }
    }

    /**
     * L3 캐시 프로바이더 설정 (Database)
     */
    fun setL3Provider(provider: L3CacheProvider) {
        this.l3Cache = provider
        logger.info { "L3 cache provider set: ${provider.javaClass.simpleName}" }
    }
}

/**
 * 캐시 통계
 */
data class CacheStatistics(
    val l1HitCount: Long,
    val l1MissCount: Long,
    val l1HitRate: Double,
    val l1Size: Long,
    val l1EvictionCount: Long,
    val l2HitCount: Long,
    val l2MissCount: Long,
    val l3HitCount: Long,
    val l3MissCount: Long
) {
    val totalHitCount: Long get() = l1HitCount + l2HitCount + l3HitCount
    val totalMissCount: Long get() = l1MissCount + l2MissCount + l3MissCount
    val overallHitRate: Double get() {
        val total = totalHitCount + totalMissCount
        return if (total > 0) totalHitCount.toDouble() / total else 0.0
    }
}

/**
 * L2 캐시 프로바이더 인터페이스 (Redis 등)
 */
interface L2CacheProvider {
    fun get(key: String): ExecutionCache.CachedResult?
    fun put(key: String, result: ExecutionCache.CachedResult)
    fun invalidate(key: String)
    fun invalidateByPattern(pattern: String)
    fun clear()
    fun getHitCount(): Long
    fun getMissCount(): Long
}

/**
 * L3 캐시 프로바이더 인터페이스 (Database)
 */
interface L3CacheProvider {
    fun get(key: String): ExecutionCache.CachedResult?
    fun put(key: String, result: ExecutionCache.CachedResult)
    fun invalidate(key: String)
    fun invalidateByPattern(pattern: String)
    fun getHitCount(): Long
    fun getMissCount(): Long
}
