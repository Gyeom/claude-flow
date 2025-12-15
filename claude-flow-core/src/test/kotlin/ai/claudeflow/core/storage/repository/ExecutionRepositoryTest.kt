package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.DateRange
import ai.claudeflow.core.storage.ExecutionRecord
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.comparables.shouldBeGreaterThan
import java.time.Instant

class ExecutionRepositoryTest : DescribeSpec({

    describe("ExecutionRecord") {
        it("should create execution record with required fields") {
            val record = ExecutionRecord(
                id = "test-1",
                prompt = "Test prompt",
                result = "Test result",
                status = "SUCCESS",
                agentId = "general",
                projectId = "test-project",
                userId = "user-1",
                channel = "C123",
                threadTs = null,
                replyTs = null,
                durationMs = 1500
            )

            record.id shouldBe "test-1"
            record.status shouldBe "SUCCESS"
            record.agentId shouldBe "general"
        }

        it("should support optional fields") {
            val record = ExecutionRecord(
                id = "test-2",
                prompt = "Test",
                result = "Result",
                status = "SUCCESS",
                agentId = "code-reviewer",
                projectId = null,
                userId = null,
                channel = null,
                threadTs = null,
                replyTs = null,
                durationMs = 1000,
                model = "claude-3-sonnet",
                routingMethod = "keyword",
                routingConfidence = 0.95
            )

            record.model shouldBe "claude-3-sonnet"
            record.routingMethod shouldBe "keyword"
            record.routingConfidence shouldBe 0.95
        }
    }

    describe("DateRange") {
        it("should create date range for last N days") {
            val range = DateRange.lastDays(7)

            range.from shouldNotBe null
            range.to shouldNotBe null
            range.from.isBefore(range.to) shouldBe true
        }

        it("should create date range for last N hours") {
            val range = DateRange.lastHours(24)
            val now = Instant.now()

            range.to.epochSecond shouldBeGreaterThan (now.epochSecond - 60)
        }
    }
})
