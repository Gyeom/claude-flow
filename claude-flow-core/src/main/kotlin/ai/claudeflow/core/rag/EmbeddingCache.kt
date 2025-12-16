package ai.claudeflow.core.rag

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.stats.CacheStats
import mu.KotlinLogging
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * 임베딩 캐시
 *
 * Caffeine 기반 고성능 캐시로 임베딩 재사용
 * 텍스트 해시를 키로 사용하여 메모리 효율성 확보
 */
class EmbeddingCache(
    maxSize: Long = 10_000,
    expireAfterWrite: Duration = Duration.ofHours(1),
    expireAfterAccess: Duration = Duration.ofMinutes(30)
) {
    private val cache: Cache<String, FloatArray> = Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(expireAfterWrite)
        .expireAfterAccess(expireAfterAccess)
        .recordStats()
        .build()

    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)

    /**
     * 캐시에서 임베딩 조회
     */
    fun get(text: String): FloatArray? {
        val key = computeKey(text)
        val result = cache.getIfPresent(key)
        if (result != null) {
            hitCount.incrementAndGet()
        } else {
            missCount.incrementAndGet()
        }
        return result
    }

    /**
     * 임베딩 캐시에 저장
     */
    fun put(text: String, embedding: FloatArray) {
        val key = computeKey(text)
        cache.put(key, embedding)
    }

    /**
     * 캐시 통계
     */
    fun stats(): EmbeddingCacheStats {
        val caffeineStats = cache.stats()
        val total = hitCount.get() + missCount.get()
        return EmbeddingCacheStats(
            hitCount = hitCount.get(),
            missCount = missCount.get(),
            hitRate = if (total > 0) hitCount.get().toDouble() / total else 0.0,
            estimatedSize = cache.estimatedSize(),
            evictionCount = caffeineStats.evictionCount()
        )
    }

    /**
     * 캐시 초기화
     */
    fun invalidateAll() {
        cache.invalidateAll()
        hitCount.set(0)
        missCount.set(0)
        logger.info { "Embedding cache invalidated" }
    }

    /**
     * 특정 텍스트의 캐시 무효화
     */
    fun invalidate(text: String) {
        cache.invalidate(computeKey(text))
    }

    /**
     * 텍스트의 해시 키 계산 (SHA-256)
     */
    private fun computeKey(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}

data class EmbeddingCacheStats(
    val hitCount: Long,
    val missCount: Long,
    val hitRate: Double,
    val estimatedSize: Long,
    val evictionCount: Long
)
