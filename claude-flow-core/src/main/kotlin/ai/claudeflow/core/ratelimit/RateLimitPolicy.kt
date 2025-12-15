package ai.claudeflow.core.ratelimit

import java.time.Duration

/**
 * Rate Limit 정책 정의
 *
 * 다차원 Rate Limiting 지원:
 * - 시간 기반: RPM, RPH, RPD
 * - 리소스 기반: 토큰 제한, 비용 제한
 * - 범위 기반: 사용자별, 프로젝트별, 에이전트별, 모델별
 */
data class RateLimitPolicy(
    val id: String,
    val name: String,
    val description: String = "",

    // 시간 기반 제한
    val requestsPerMinute: Int? = null,     // RPM
    val requestsPerHour: Int? = null,       // RPH
    val requestsPerDay: Int? = null,        // RPD

    // 리소스 기반 제한
    val tokensPerMinute: Long? = null,      // TPM
    val tokensPerHour: Long? = null,        // TPH
    val tokensPerDay: Long? = null,         // TPD
    val maxTokensPerRequest: Int? = null,   // 요청당 최대 토큰

    // 비용 기반 제한
    val costPerHour: Double? = null,        // 시간당 최대 비용 (USD)
    val costPerDay: Double? = null,         // 일일 최대 비용 (USD)
    val costPerMonth: Double? = null,       // 월간 최대 비용 (USD)

    // 동시성 제한
    val maxConcurrentRequests: Int? = null, // 동시 요청 수

    // 적용 범위
    val scope: RateLimitScope = RateLimitScope.GLOBAL,
    val scopeValue: String? = null,         // userId, projectId, agentId, model 등

    // 버스트 허용
    val burstSize: Int? = null,             // 버스트 허용량
    val burstRecoveryRate: Double = 1.0,    // 버스트 복구 속도 (초당)

    // 우선순위 (높을수록 먼저 적용)
    val priority: Int = 0,

    // 초과시 동작
    val onExceeded: RateLimitAction = RateLimitAction.REJECT,
    val retryAfter: Duration? = null,       // 재시도 대기 시간

    // 활성화 여부
    val enabled: Boolean = true
) {
    companion object {
        // 기본 정책들
        val DEFAULT_USER = RateLimitPolicy(
            id = "default-user",
            name = "Default User Policy",
            description = "기본 사용자 rate limit 정책",
            requestsPerMinute = 60,
            requestsPerHour = 1000,
            tokensPerDay = 1_000_000,
            costPerDay = 10.0,
            scope = RateLimitScope.USER
        )

        val DEFAULT_PROJECT = RateLimitPolicy(
            id = "default-project",
            name = "Default Project Policy",
            description = "기본 프로젝트 rate limit 정책",
            requestsPerMinute = 100,
            requestsPerHour = 5000,
            tokensPerDay = 10_000_000,
            costPerDay = 100.0,
            scope = RateLimitScope.PROJECT
        )

        val FREE_TIER = RateLimitPolicy(
            id = "free-tier",
            name = "Free Tier",
            description = "무료 사용자 rate limit 정책",
            requestsPerMinute = 10,
            requestsPerHour = 100,
            requestsPerDay = 500,
            tokensPerDay = 100_000,
            costPerDay = 1.0,
            scope = RateLimitScope.USER
        )

        val PREMIUM_TIER = RateLimitPolicy(
            id = "premium-tier",
            name = "Premium Tier",
            description = "프리미엄 사용자 rate limit 정책",
            requestsPerMinute = 120,
            requestsPerHour = 3000,
            tokensPerDay = 5_000_000,
            costPerDay = 50.0,
            scope = RateLimitScope.USER
        )

        val ENTERPRISE_TIER = RateLimitPolicy(
            id = "enterprise-tier",
            name = "Enterprise Tier",
            description = "엔터프라이즈 rate limit 정책",
            requestsPerMinute = 500,
            requestsPerHour = 10000,
            tokensPerDay = 50_000_000,
            costPerDay = 500.0,
            maxConcurrentRequests = 50,
            scope = RateLimitScope.PROJECT
        )

        val AGENT_LIMIT = RateLimitPolicy(
            id = "agent-limit",
            name = "Per-Agent Limit",
            description = "에이전트별 rate limit 정책",
            requestsPerMinute = 30,
            requestsPerHour = 500,
            scope = RateLimitScope.AGENT
        )

        val MODEL_OPUS = RateLimitPolicy(
            id = "model-opus",
            name = "Claude Opus Limit",
            description = "Claude Opus 모델 사용 제한",
            requestsPerMinute = 10,
            requestsPerHour = 100,
            tokensPerDay = 500_000,
            costPerDay = 50.0,
            scope = RateLimitScope.MODEL,
            scopeValue = "claude-3-opus"
        )

        val BURST_ALLOWED = RateLimitPolicy(
            id = "burst-allowed",
            name = "Burst Allowed Policy",
            description = "버스트 트래픽 허용 정책",
            requestsPerMinute = 60,
            burstSize = 20,
            burstRecoveryRate = 1.0,
            scope = RateLimitScope.USER
        )
    }

    /**
     * 정책 병합 (다중 정책 적용시)
     */
    fun mergeWith(other: RateLimitPolicy): RateLimitPolicy {
        return RateLimitPolicy(
            id = "$id+${other.id}",
            name = "$name + ${other.name}",
            description = "Merged policy",
            requestsPerMinute = minOfNullable(requestsPerMinute, other.requestsPerMinute),
            requestsPerHour = minOfNullable(requestsPerHour, other.requestsPerHour),
            requestsPerDay = minOfNullable(requestsPerDay, other.requestsPerDay),
            tokensPerMinute = minOfNullable(tokensPerMinute, other.tokensPerMinute),
            tokensPerHour = minOfNullable(tokensPerHour, other.tokensPerHour),
            tokensPerDay = minOfNullable(tokensPerDay, other.tokensPerDay),
            maxTokensPerRequest = minOfNullable(maxTokensPerRequest, other.maxTokensPerRequest),
            costPerHour = minOfNullable(costPerHour, other.costPerHour),
            costPerDay = minOfNullable(costPerDay, other.costPerDay),
            costPerMonth = minOfNullable(costPerMonth, other.costPerMonth),
            maxConcurrentRequests = minOfNullable(maxConcurrentRequests, other.maxConcurrentRequests),
            priority = maxOf(priority, other.priority),
            enabled = enabled && other.enabled
        )
    }

    private fun <T : Comparable<T>> minOfNullable(a: T?, b: T?): T? {
        return when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }
    }
}

/**
 * Rate Limit 적용 범위
 */
enum class RateLimitScope {
    GLOBAL,     // 전역
    USER,       // 사용자별
    PROJECT,    // 프로젝트별
    AGENT,      // 에이전트별
    MODEL,      // 모델별
    API_KEY,    // API 키별
    IP,         // IP 주소별
    CUSTOM      // 커스텀 범위
}

/**
 * Rate Limit 초과시 동작
 */
enum class RateLimitAction {
    REJECT,     // 요청 거부 (429 Too Many Requests)
    QUEUE,      // 대기열에 추가
    DEGRADE,    // 성능 저하 (더 느린 모델 사용 등)
    WARN,       // 경고만 (요청은 허용)
    THROTTLE    // 속도 제한 (지연 후 처리)
}

/**
 * Rate Limit 상태
 */
data class RateLimitStatus(
    val policyId: String,
    val scope: RateLimitScope,
    val scopeValue: String?,
    val isExceeded: Boolean,
    val currentCount: Long,
    val limit: Long,
    val remaining: Long,
    val resetAt: Long,           // Unix timestamp (ms)
    val retryAfterMs: Long? = null
) {
    val percentUsed: Double get() = if (limit > 0) currentCount.toDouble() / limit * 100 else 0.0
}

/**
 * 고급 Rate Limit 체크 결과
 */
data class AdvancedRateLimitResult(
    val allowed: Boolean,
    val statuses: List<RateLimitStatus>,
    val action: RateLimitAction? = null,
    val message: String? = null,
    val retryAfterMs: Long? = null
) {
    companion object {
        fun allowed() = AdvancedRateLimitResult(true, emptyList())

        fun denied(statuses: List<RateLimitStatus>, action: RateLimitAction, message: String, retryAfterMs: Long? = null) =
            AdvancedRateLimitResult(false, statuses, action, message, retryAfterMs)
    }
}

/**
 * Rate Limit 컨텍스트 (요청 정보)
 */
data class RateLimitContext(
    val userId: String? = null,
    val projectId: String? = null,
    val agentId: String? = null,
    val model: String? = null,
    val apiKey: String? = null,
    val ipAddress: String? = null,
    val estimatedTokens: Long = 0,
    val estimatedCost: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)
