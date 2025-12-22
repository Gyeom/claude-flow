package ai.claudeflow.core.ratelimit

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * 고급 Rate Limiter
 *
 * 차별화 기능:
 * 1. 다차원 Rate Limiting (사용자/프로젝트/에이전트/모델별)
 * 2. 토큰/비용 기반 제한
 * 3. 슬라이딩 윈도우 알고리즘
 * 4. 버스트 트래픽 허용
 * 5. 정책 기반 동적 제한
 */
class AdvancedRateLimiter(
    private val policies: MutableList<RateLimitPolicy> = mutableListOf()
) {
    // 카운터 저장소 (scope:value:window -> counter)
    private val counters = ConcurrentHashMap<String, SlidingWindowCounter>()

    // 동시성 카운터
    private val concurrencyCounters = ConcurrentHashMap<String, AtomicLong>()

    init {
        // 기본 정책 추가
        if (policies.isEmpty()) {
            policies.add(RateLimitPolicy.DEFAULT_USER)
        }
    }

    /**
     * 요청 허용 여부 확인
     */
    fun checkLimit(context: RateLimitContext): AdvancedRateLimitResult {
        val applicablePolicies = findApplicablePolicies(context)
        val statuses = mutableListOf<RateLimitStatus>()
        var deniedPolicy: RateLimitPolicy? = null

        for (policy in applicablePolicies) {
            if (!policy.enabled) continue

            val status = checkPolicy(policy, context)
            statuses.add(status)

            if (status.isExceeded && deniedPolicy == null) {
                deniedPolicy = policy
            }
        }

        return if (deniedPolicy != null) {
            val exceededStatus = statuses.first { it.isExceeded }
            AdvancedRateLimitResult.denied(
                statuses = statuses,
                action = deniedPolicy.onExceeded,
                message = "Rate limit exceeded: ${deniedPolicy.name}",
                retryAfterMs = exceededStatus.retryAfterMs
            )
        } else {
            AdvancedRateLimitResult(
                allowed = true,
                statuses = statuses
            )
        }
    }

    /**
     * 요청 기록 (허용된 경우)
     */
    fun recordRequest(context: RateLimitContext) {
        val applicablePolicies = findApplicablePolicies(context)

        for (policy in applicablePolicies) {
            if (!policy.enabled) continue
            recordForPolicy(policy, context)
        }

        logger.debug { "Request recorded for ${context.userId}" }
    }

    /**
     * 요청 완료 (동시성 카운터 감소)
     */
    fun completeRequest(context: RateLimitContext) {
        val applicablePolicies = findApplicablePolicies(context)

        for (policy in applicablePolicies) {
            if (policy.maxConcurrentRequests != null) {
                val key = buildConcurrencyKey(policy, context)
                concurrencyCounters[key]?.decrementAndGet()
            }
        }
    }

    /**
     * 적용 가능한 정책 찾기
     */
    private fun findApplicablePolicies(context: RateLimitContext): List<RateLimitPolicy> {
        return policies.filter { policy ->
            when (policy.scope) {
                RateLimitScope.GLOBAL -> true
                RateLimitScope.USER -> context.userId != null
                RateLimitScope.PROJECT -> context.projectId != null
                RateLimitScope.AGENT -> context.agentId != null
                RateLimitScope.MODEL -> {
                    context.model != null &&
                    (policy.scopeValue == null || context.model.contains(policy.scopeValue, ignoreCase = true))
                }
                RateLimitScope.API_KEY -> context.apiKey != null
                RateLimitScope.IP -> context.ipAddress != null
                RateLimitScope.CUSTOM -> policy.scopeValue != null
            }
        }.sortedByDescending { it.priority }
    }

    /**
     * 시간 기반 요청 제한 확인 (RPM/RPH/RPD)
     * 중복 코드 제거를 위한 헬퍼 함수
     */
    private fun checkRequestLimit(
        key: String,
        counterType: String,
        windowMs: Long,
        limit: Int,
        policy: RateLimitPolicy,
        context: RateLimitContext,
        now: Long
    ): RateLimitStatus? {
        val counter = getOrCreateCounter(key, counterType, windowMs)
        if (counter.getCount() >= limit) {
            return RateLimitStatus(
                policyId = policy.id,
                scope = policy.scope,
                scopeValue = getScopeValue(policy, context),
                isExceeded = true,
                currentCount = counter.getCount(),
                limit = limit.toLong(),
                remaining = 0,
                resetAt = counter.getResetTime(),
                retryAfterMs = counter.getResetTime() - now
            )
        }
        return null
    }

    /**
     * 정책별 제한 확인
     */
    private fun checkPolicy(policy: RateLimitPolicy, context: RateLimitContext): RateLimitStatus {
        val key = buildKey(policy, context)
        val now = System.currentTimeMillis()

        // RPM/RPH/RPD 확인 (헬퍼 함수 사용)
        policy.requestsPerMinute?.let { limit ->
            checkRequestLimit(key, "rpm", 60_000L, limit, policy, context, now)?.let { return it }
        }

        policy.requestsPerHour?.let { limit ->
            checkRequestLimit(key, "rph", 3_600_000L, limit, policy, context, now)?.let { return it }
        }

        policy.requestsPerDay?.let { limit ->
            checkRequestLimit(key, "rpd", 86_400_000L, limit, policy, context, now)?.let { return it }
        }

        // TPD 확인
        policy.tokensPerDay?.let { limit ->
            val counter = getOrCreateCounter(key, "tpd", 86_400_000L)
            val estimatedTotal = counter.getCount() + context.estimatedTokens
            if (estimatedTotal >= limit) {
                return RateLimitStatus(
                    policyId = policy.id,
                    scope = policy.scope,
                    scopeValue = getScopeValue(policy, context),
                    isExceeded = true,
                    currentCount = counter.getCount(),
                    limit = limit,
                    remaining = maxOf(0, limit - counter.getCount()),
                    resetAt = counter.getResetTime(),
                    retryAfterMs = counter.getResetTime() - now
                )
            }
        }

        // 동시성 확인
        policy.maxConcurrentRequests?.let { limit ->
            val concurrencyKey = buildConcurrencyKey(policy, context)
            val current = concurrencyCounters.getOrPut(concurrencyKey) { AtomicLong(0) }.get()
            if (current >= limit) {
                return RateLimitStatus(
                    policyId = policy.id,
                    scope = policy.scope,
                    scopeValue = getScopeValue(policy, context),
                    isExceeded = true,
                    currentCount = current,
                    limit = limit.toLong(),
                    remaining = 0,
                    resetAt = now + 1000, // 1초 후 재시도
                    retryAfterMs = 1000
                )
            }
        }

        // 비용 확인
        policy.costPerDay?.let { limit ->
            val counter = getOrCreateCounter(key, "cost_day", 86_400_000L)
            val currentCost = counter.getCount().toDouble() / 1000 // millicents to dollars
            val estimatedTotal = currentCost + context.estimatedCost
            if (estimatedTotal >= limit) {
                return RateLimitStatus(
                    policyId = policy.id,
                    scope = policy.scope,
                    scopeValue = getScopeValue(policy, context),
                    isExceeded = true,
                    currentCount = (currentCost * 100).toLong(), // cents
                    limit = (limit * 100).toLong(),
                    remaining = maxOf(0, ((limit - currentCost) * 100).toLong()),
                    resetAt = counter.getResetTime(),
                    retryAfterMs = counter.getResetTime() - now
                )
            }
        }

        // 모든 제한 통과
        val rpm = policy.requestsPerMinute
        val counter = if (rpm != null) getOrCreateCounter(key, "rpm", 60_000L) else null

        return RateLimitStatus(
            policyId = policy.id,
            scope = policy.scope,
            scopeValue = getScopeValue(policy, context),
            isExceeded = false,
            currentCount = counter?.getCount() ?: 0,
            limit = rpm?.toLong() ?: Long.MAX_VALUE,
            remaining = if (rpm != null) maxOf(0, rpm.toLong() - (counter?.getCount() ?: 0)) else Long.MAX_VALUE,
            resetAt = counter?.getResetTime() ?: (now + 60_000)
        )
    }

    /**
     * 시간 기반 요청 카운터 증가 헬퍼
     */
    private data class CounterConfig(val type: String, val windowMs: Long)

    private val REQUEST_COUNTERS = listOf(
        CounterConfig("rpm", 60_000L),
        CounterConfig("rph", 3_600_000L),
        CounterConfig("rpd", 86_400_000L)
    )

    /**
     * 정책별 요청 기록
     */
    private fun recordForPolicy(policy: RateLimitPolicy, context: RateLimitContext) {
        val key = buildKey(policy, context)

        // RPM/RPH/RPD 기록 (데이터 기반 처리)
        val limits = listOf(
            policy.requestsPerMinute to REQUEST_COUNTERS[0],
            policy.requestsPerHour to REQUEST_COUNTERS[1],
            policy.requestsPerDay to REQUEST_COUNTERS[2]
        )
        limits.forEach { (limit, config) ->
            if (limit != null) {
                getOrCreateCounter(key, config.type, config.windowMs).increment()
            }
        }

        // TPD 기록
        policy.tokensPerDay?.let {
            getOrCreateCounter(key, "tpd", 86_400_000L).add(context.estimatedTokens)
        }

        // 동시성 증가
        policy.maxConcurrentRequests?.let {
            val concurrencyKey = buildConcurrencyKey(policy, context)
            concurrencyCounters.getOrPut(concurrencyKey) { AtomicLong(0) }.incrementAndGet()
        }

        // 비용 기록 (millicents로 저장)
        policy.costPerDay?.let {
            getOrCreateCounter(key, "cost_day", 86_400_000L).add((context.estimatedCost * 1000).toLong())
        }
    }

    /**
     * 키 생성
     */
    private fun buildKey(policy: RateLimitPolicy, context: RateLimitContext): String {
        val scopeValue = getScopeValue(policy, context)
        return "${policy.scope}:${scopeValue ?: "global"}"
    }

    /**
     * 동시성 키 생성
     */
    private fun buildConcurrencyKey(policy: RateLimitPolicy, context: RateLimitContext): String {
        return "concurrent:${buildKey(policy, context)}"
    }

    /**
     * 범위 값 추출
     */
    private fun getScopeValue(policy: RateLimitPolicy, context: RateLimitContext): String? {
        return when (policy.scope) {
            RateLimitScope.GLOBAL -> null
            RateLimitScope.USER -> context.userId
            RateLimitScope.PROJECT -> context.projectId
            RateLimitScope.AGENT -> context.agentId
            RateLimitScope.MODEL -> context.model
            RateLimitScope.API_KEY -> context.apiKey?.take(8)
            RateLimitScope.IP -> context.ipAddress
            RateLimitScope.CUSTOM -> policy.scopeValue
        }
    }

    /**
     * 카운터 조회/생성
     */
    private fun getOrCreateCounter(key: String, type: String, windowMs: Long): SlidingWindowCounter {
        val fullKey = "$key:$type"
        return counters.getOrPut(fullKey) { SlidingWindowCounter(windowMs) }
    }

    // ==================== 정책 관리 ====================

    /**
     * 정책 추가
     */
    fun addPolicy(policy: RateLimitPolicy) {
        policies.add(policy)
        logger.info { "Rate limit policy added: ${policy.id}" }
    }

    /**
     * 정책 제거
     */
    fun removePolicy(policyId: String): Boolean {
        val removed = policies.removeIf { it.id == policyId }
        if (removed) {
            logger.info { "Rate limit policy removed: $policyId" }
        }
        return removed
    }

    /**
     * 정책 업데이트
     */
    fun updatePolicy(policy: RateLimitPolicy): Boolean {
        val index = policies.indexOfFirst { it.id == policy.id }
        if (index == -1) return false
        policies[index] = policy
        logger.info { "Rate limit policy updated: ${policy.id}" }
        return true
    }

    /**
     * 정책 조회
     */
    fun getPolicy(policyId: String): RateLimitPolicy? = policies.find { it.id == policyId }

    /**
     * 모든 정책 조회
     */
    fun getAllPolicies(): List<RateLimitPolicy> = policies.toList()

    /**
     * 카운터 초기화
     */
    fun resetCounters(scope: RateLimitScope? = null, scopeValue: String? = null) {
        if (scope == null && scopeValue == null) {
            counters.clear()
            concurrencyCounters.clear()
            logger.info { "All rate limit counters reset" }
        } else {
            val prefix = "${scope ?: "*"}:${scopeValue ?: "*"}"
            counters.keys.removeIf { it.startsWith(prefix.replace("*", "")) }
            concurrencyCounters.keys.removeIf { it.contains(prefix.replace("*", "")) }
            logger.info { "Rate limit counters reset for: $prefix" }
        }
    }

    /**
     * 현재 사용량 조회
     */
    fun getUsage(context: RateLimitContext): List<RateLimitStatus> {
        return findApplicablePolicies(context).map { checkPolicy(it, context) }
    }
}

/**
 * 슬라이딩 윈도우 카운터
 */
class SlidingWindowCounter(
    private val windowMs: Long
) {
    private val count = AtomicLong(0)
    private var windowStart = System.currentTimeMillis()

    @Synchronized
    fun increment(): Long {
        resetIfNeeded()
        return count.incrementAndGet()
    }

    @Synchronized
    fun add(amount: Long): Long {
        resetIfNeeded()
        return count.addAndGet(amount)
    }

    @Synchronized
    fun getCount(): Long {
        resetIfNeeded()
        return count.get()
    }

    fun getResetTime(): Long = windowStart + windowMs

    @Synchronized
    private fun resetIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - windowStart >= windowMs) {
            count.set(0)
            windowStart = now
        }
    }
}
