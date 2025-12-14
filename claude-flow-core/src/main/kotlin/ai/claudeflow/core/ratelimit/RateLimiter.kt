package ai.claudeflow.core.ratelimit

import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * 프로젝트별 Rate Limiter
 *
 * Sliding window 방식으로 분당 요청 수 제한
 */
class RateLimiter(
    private val defaultRpm: Int = 60,  // requests per minute
    private val maxCacheSize: Int = 1000
) {
    private val limiters = ConcurrentHashMap<String, ProjectLimiter>()
    private val customLimits = ConcurrentHashMap<String, Int>()

    /**
     * 요청 허용 여부 확인
     */
    fun checkLimit(projectId: String): RateLimitResult {
        val limiter = getOrCreateLimiter(projectId)
        val rpm = customLimits[projectId] ?: defaultRpm

        if (rpm <= 0) {
            return RateLimitResult(allowed = true, remaining = Int.MAX_VALUE)
        }

        val now = Instant.now().epochSecond
        limiter.cleanup(now)

        val currentCount = limiter.getCount()
        if (currentCount >= rpm) {
            val retryAfter = 60 - (now - limiter.windowStart)
            logger.warn { "Rate limit exceeded for project $projectId: $currentCount/$rpm (retry after ${retryAfter}s)" }
            return RateLimitResult(
                allowed = false,
                remaining = 0,
                retryAfterSeconds = retryAfter.toInt()
            )
        }

        limiter.increment()
        return RateLimitResult(
            allowed = true,
            remaining = rpm - currentCount - 1
        )
    }

    /**
     * 프로젝트별 제한 설정
     */
    fun setLimit(projectId: String, rpm: Int) {
        customLimits[projectId] = rpm
        logger.info { "Set rate limit for project $projectId: $rpm rpm" }
    }

    /**
     * 프로젝트별 제한 제거
     */
    fun removeLimit(projectId: String) {
        customLimits.remove(projectId)
    }

    private fun getOrCreateLimiter(projectId: String): ProjectLimiter {
        enforceCacheLimit()
        return limiters.computeIfAbsent(projectId) { ProjectLimiter() }
    }

    private fun enforceCacheLimit() {
        if (limiters.size > maxCacheSize) {
            // LRU 방식으로 오래된 항목 제거
            val oldest = limiters.entries
                .sortedBy { it.value.lastUsed }
                .take(limiters.size - maxCacheSize + 100)

            oldest.forEach { limiters.remove(it.key) }
            logger.debug { "Evicted ${oldest.size} rate limiters" }
        }
    }

    /**
     * 현재 상태 조회 (디버깅용)
     */
    fun getStatus(): Map<String, RateLimiterStatus> {
        return limiters.mapValues { (projectId, limiter) ->
            val rpm = customLimits[projectId] ?: defaultRpm
            RateLimiterStatus(
                currentCount = limiter.getCount(),
                limit = rpm,
                windowStart = limiter.windowStart
            )
        }
    }
}

/**
 * 개별 프로젝트의 Rate Limiter
 */
private class ProjectLimiter {
    private val count = AtomicInteger(0)
    var windowStart: Long = Instant.now().epochSecond
        private set
    var lastUsed: Long = Instant.now().epochSecond
        private set

    fun increment() {
        count.incrementAndGet()
        lastUsed = Instant.now().epochSecond
    }

    fun getCount(): Int = count.get()

    fun cleanup(now: Long) {
        // 1분 경과 시 윈도우 리셋
        if (now - windowStart >= 60) {
            count.set(0)
            windowStart = now
        }
    }
}

data class RateLimitResult(
    val allowed: Boolean,
    val remaining: Int,
    val retryAfterSeconds: Int = 0
)

data class RateLimiterStatus(
    val currentCount: Int,
    val limit: Int,
    val windowStart: Long
)
