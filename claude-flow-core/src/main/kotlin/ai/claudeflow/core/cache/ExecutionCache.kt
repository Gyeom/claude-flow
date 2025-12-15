package ai.claudeflow.core.cache

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache
import mu.KotlinLogging
import java.security.MessageDigest
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Execution Cache System (L1 In-Memory)
 *
 * Caffeine 기반 고성능 인메모리 캐시
 *
 * 캐시 전략:
 * - 프롬프트 해시 기반: 동일 프롬프트 → 동일 결과
 * - TTL 기반: 시간 경과 후 만료
 */
class ExecutionCache(
    private val maxSize: Long = 10_000,
    private val ttlMinutes: Long = 60
) {
    private val cache: Cache<String, CachedResult> = Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
        .recordStats()
        .build()

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
        PROMPT_USER        // 프롬프트 + 사용자 ID
    }

    /**
     * 캐시 조회
     */
    fun get(
        prompt: String,
        agentId: String? = null,
        userId: String? = null,
        strategy: CacheKeyStrategy = CacheKeyStrategy.PROMPT_HASH
    ): CachedResult? {
        val key = buildKey(prompt, agentId, userId, strategy)

        cache.getIfPresent(key)?.let { result ->
            if (!result.isExpired()) {
                logger.debug { "Cache hit: $key" }
                return result
            }
            cache.invalidate(key)
        }

        logger.debug { "Cache miss: $key" }
        return null
    }

    /**
     * 캐시 저장
     */
    fun put(
        prompt: String,
        result: CachedResult,
        agentId: String? = null,
        userId: String? = null,
        strategy: CacheKeyStrategy = CacheKeyStrategy.PROMPT_HASH
    ) {
        val key = buildKey(prompt, agentId, userId, strategy)
        cache.put(key, result)
        logger.debug { "Cache put: $key" }
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
        cache.invalidate(key)
        logger.info { "Cache invalidated: $key" }
    }

    /**
     * 사용자별 캐시 전체 무효화
     */
    fun invalidateByUser(userId: String) {
        cache.asMap().keys
            .filter { it.contains(":user=$userId") }
            .forEach { cache.invalidate(it) }
        logger.info { "User cache invalidated: $userId" }
    }

    /**
     * 전체 캐시 초기화
     */
    fun clear() {
        cache.invalidateAll()
        logger.info { "Cache cleared" }
    }

    /**
     * 캐시 통계
     */
    fun getStats(): CacheStatistics {
        val stats = cache.stats()
        return CacheStatistics(
            hitCount = stats.hitCount(),
            missCount = stats.missCount(),
            hitRate = stats.hitRate(),
            size = cache.estimatedSize(),
            evictionCount = stats.evictionCount()
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
}

/**
 * 캐시 통계
 */
data class CacheStatistics(
    val hitCount: Long,
    val missCount: Long,
    val hitRate: Double,
    val size: Long,
    val evictionCount: Long
) {
    val totalRequests: Long get() = hitCount + missCount
}
