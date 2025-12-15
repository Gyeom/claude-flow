package ai.claudeflow.core.ratelimit

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldNotBeEmpty

class AdvancedRateLimiterTest : DescribeSpec({

    describe("AdvancedRateLimiter") {

        describe("checkLimit with default policy") {
            it("should allow requests under limit") {
                val limiter = AdvancedRateLimiter()
                val context = RateLimitContext(userId = "user-1")

                val result = limiter.checkLimit(context)
                result.allowed shouldBe true
            }

            it("should track requests correctly") {
                val limiter = AdvancedRateLimiter(mutableListOf(
                    RateLimitPolicy(
                        id = "test-policy",
                        name = "Test Policy",
                        requestsPerMinute = 5,
                        scope = RateLimitScope.USER
                    )
                ))

                val context = RateLimitContext(userId = "user-2")

                // First 5 requests should succeed
                repeat(5) {
                    limiter.checkLimit(context).allowed shouldBe true
                    limiter.recordRequest(context)
                }

                // 6th request should be denied
                val result = limiter.checkLimit(context)
                result.allowed shouldBe false
                result.action shouldBe RateLimitAction.REJECT
            }
        }

        describe("multi-dimensional rate limiting") {
            it("should apply multiple policies") {
                val limiter = AdvancedRateLimiter(mutableListOf(
                    RateLimitPolicy(
                        id = "user-policy",
                        name = "User Policy",
                        requestsPerMinute = 10,
                        scope = RateLimitScope.USER
                    ),
                    RateLimitPolicy(
                        id = "project-policy",
                        name = "Project Policy",
                        requestsPerMinute = 100,
                        scope = RateLimitScope.PROJECT
                    )
                ))

                val context = RateLimitContext(
                    userId = "user-3",
                    projectId = "project-1"
                )

                val result = limiter.checkLimit(context)
                result.allowed shouldBe true
                result.statuses.shouldNotBeEmpty()
            }
        }

        describe("policy management") {
            it("should add and remove policies") {
                val limiter = AdvancedRateLimiter()

                val newPolicy = RateLimitPolicy(
                    id = "custom-policy",
                    name = "Custom Policy",
                    requestsPerMinute = 30,
                    scope = RateLimitScope.AGENT
                )

                limiter.addPolicy(newPolicy)
                limiter.getPolicy("custom-policy") shouldNotBe null

                limiter.removePolicy("custom-policy")
                limiter.getPolicy("custom-policy") shouldBe null
            }
        }

        describe("token-based limiting") {
            it("should create policy with token limits") {
                val policy = RateLimitPolicy(
                    id = "token-policy",
                    name = "Token Policy",
                    tokensPerDay = 10000,
                    scope = RateLimitScope.USER
                )

                policy.tokensPerDay shouldBe 10000
                policy.scope shouldBe RateLimitScope.USER
            }

            it("should include estimated tokens in context") {
                val context = RateLimitContext(
                    userId = "user-token-test",
                    estimatedTokens = 5000
                )

                context.estimatedTokens shouldBe 5000
            }
        }

        describe("reset counters") {
            it("should reset all counters") {
                val limiter = AdvancedRateLimiter(mutableListOf(
                    RateLimitPolicy(
                        id = "reset-test",
                        name = "Reset Test",
                        requestsPerMinute = 3,
                        scope = RateLimitScope.USER
                    )
                ))

                val context = RateLimitContext(userId = "user-5")

                // Use up limit
                repeat(3) {
                    limiter.recordRequest(context)
                }
                limiter.checkLimit(context).allowed shouldBe false

                // Reset counters
                limiter.resetCounters()

                // Should be allowed again
                limiter.checkLimit(context).allowed shouldBe true
            }
        }
    }
})
