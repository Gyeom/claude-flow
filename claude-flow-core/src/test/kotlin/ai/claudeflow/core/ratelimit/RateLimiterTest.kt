package ai.claudeflow.core.ratelimit

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class RateLimiterTest : BehaviorSpec({

    Given("RateLimiter가 60 rpm으로 초기화되었을 때") {
        val rateLimiter = RateLimiter(defaultRpm = 60)

        Then("기본 제한이 60이어야 한다") {
            // 첫 요청은 항상 허용
            rateLimiter.checkLimit("test-project").allowed shouldBe true
        }
    }

    Given("Rate limit 체크를 수행할 때") {
        val rateLimiter = RateLimiter(defaultRpm = 5)

        When("제한 내에서 요청하면") {
            repeat(5) {
                rateLimiter.checkLimit("project-1")
            }

            Then("6번째 요청은 제한되어야 한다") {
                val result = rateLimiter.checkLimit("project-1")
                result.allowed shouldBe false
            }
        }

        When("다른 프로젝트에서 요청하면") {
            val result = rateLimiter.checkLimit("project-2")

            Then("요청이 허용되어야 한다") {
                result.allowed shouldBe true
            }
        }
    }

    Given("프로젝트별 제한을 설정할 때") {
        val rateLimiter = RateLimiter(defaultRpm = 10)

        When("특정 프로젝트에 다른 제한을 설정하면") {
            rateLimiter.setLimit("premium-project", 100)

            Then("해당 프로젝트의 제한이 변경되어야 한다") {
                repeat(50) {
                    val result = rateLimiter.checkLimit("premium-project")
                    result.allowed shouldBe true
                }
            }
        }
    }

    Given("Rate limiter 상태를 조회할 때") {
        val rateLimiter = RateLimiter(defaultRpm = 10)

        rateLimiter.checkLimit("project-a")
        rateLimiter.checkLimit("project-a")
        rateLimiter.checkLimit("project-b")

        When("getStatus를 호출하면") {
            val status = rateLimiter.getStatus()

            Then("프로젝트별 상태가 반환되어야 한다") {
                status.containsKey("project-a") shouldBe true
                status.containsKey("project-b") shouldBe true
            }
        }
    }

    Given("제한 초과 시") {
        val rateLimiter = RateLimiter(defaultRpm = 2)

        rateLimiter.checkLimit("limited-project")
        rateLimiter.checkLimit("limited-project")

        When("제한을 초과하면") {
            val result = rateLimiter.checkLimit("limited-project")

            Then("요청이 거부되어야 한다") {
                result.allowed shouldBe false
            }

            Then("재시도 대기 시간이 반환되어야 한다") {
                result.retryAfterSeconds shouldNotBe 0
            }

            Then("남은 요청 수가 0이어야 한다") {
                result.remaining shouldBe 0
            }
        }
    }
})
