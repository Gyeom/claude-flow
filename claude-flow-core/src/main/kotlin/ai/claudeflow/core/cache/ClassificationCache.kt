package ai.claudeflow.core.cache

import ai.claudeflow.core.storage.ClassifyResult
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache
import mu.KotlinLogging
import java.security.MessageDigest
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * LLM 분류 결과 캐시
 *
 * LLM 분류는 비용이 높고(~2-5초, 토큰 소비) 동일한 프롬프트에 대해
 * 같은 에이전트로 라우팅되는 경향이 있으므로 캐싱이 매우 효과적
 *
 * 캐시 전략:
 * - 프롬프트 정규화 후 해시
 * - 프로젝트별 분리
 * - 짧은 TTL (5분) - 에이전트 설정 변경 반영
 */
class ClassificationCache(
    private val maxSize: Long = 5_000,
    private val ttlMinutes: Long = 5
) {
    // Caffeine 캐시
    private val cache: Cache<String, CachedClassification> = Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
        .recordStats()
        .build()

    data class CachedClassification(
        val result: ClassifyResult,
        val cachedAt: Long = System.currentTimeMillis()
    )

    /**
     * 분류 결과 조회
     */
    fun get(prompt: String, projectId: String? = null): ClassifyResult? {
        val key = buildKey(prompt, projectId)
        val cached = cache.getIfPresent(key)

        return if (cached != null) {
            logger.debug { "Classification cache hit: ${key.take(32)}..." }
            cached.result
        } else {
            logger.debug { "Classification cache miss: ${key.take(32)}..." }
            null
        }
    }

    /**
     * 분류 결과 저장
     */
    fun put(prompt: String, result: ClassifyResult, projectId: String? = null) {
        val key = buildKey(prompt, projectId)
        cache.put(key, CachedClassification(result))
        logger.debug { "Classification cached: ${key.take(32)}... -> ${result.agent}" }
    }

    /**
     * 프로젝트 캐시 무효화 (에이전트 설정 변경 시)
     */
    fun invalidateByProject(projectId: String) {
        val keysToRemove = cache.asMap().keys
            .filter { it.contains(":project=$projectId") }
        keysToRemove.forEach { cache.invalidate(it) }
        logger.info { "Classification cache invalidated for project: $projectId (${keysToRemove.size} entries)" }
    }

    /**
     * 전체 캐시 초기화
     */
    fun clear() {
        cache.invalidateAll()
        logger.info { "Classification cache cleared" }
    }

    /**
     * 캐시 통계
     */
    fun getStats(): ClassificationCacheStats {
        val stats = cache.stats()
        return ClassificationCacheStats(
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
    private fun buildKey(prompt: String, projectId: String?): String {
        val normalizedPrompt = normalizePrompt(prompt)
        val promptHash = hashPrompt(normalizedPrompt)
        return "classify:$promptHash:project=${projectId ?: "global"}"
    }

    /**
     * 프롬프트 정규화 (캐시 히트율 향상)
     */
    private fun normalizePrompt(prompt: String): String {
        return prompt
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")  // 연속 공백 정규화
            .replace(Regex("[!?.]+$"), "")  // 문장 끝 특수문자 제거
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

data class ClassificationCacheStats(
    val hitCount: Long,
    val missCount: Long,
    val hitRate: Double,
    val size: Long,
    val evictionCount: Long
) {
    val totalRequests: Long get() = hitCount + missCount
    val estimatedSavingsMs: Long get() = hitCount * 3000  // LLM 분류 평균 3초 가정
    val estimatedTokensSaved: Long get() = hitCount * 500  // 분류당 평균 500 토큰 가정
}
