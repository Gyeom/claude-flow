package ai.claudeflow.core.session

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SessionManagerTest : BehaviorSpec({

    Given("SessionManager가 초기화되었을 때") {
        val sessionManager = SessionManager(sessionTtlMinutes = 60, maxSessions = 100)

        Then("활성 세션이 0개여야 한다") {
            sessionManager.activeCount() shouldBe 0
        }
    }

    Given("새 세션을 생성할 때") {
        val sessionManager = SessionManager()

        When("getOrCreate를 호출하면") {
            val session = sessionManager.getOrCreate(
                threadId = "thread-123",
                channel = "C1234567",
                userId = "U1234567"
            )

            Then("세션이 생성되어야 한다") {
                session shouldNotBe null
                session.id shouldBe "thread-123"
                session.channel shouldBe "C1234567"
                session.userId shouldBe "U1234567"
            }

            Then("활성 세션이 1개여야 한다") {
                sessionManager.activeCount() shouldBe 1
            }
        }

        When("같은 threadId로 다시 getOrCreate를 호출하면") {
            val session1 = sessionManager.getOrCreate("thread-456", "C1234567", "U1234567")
            val session2 = sessionManager.getOrCreate("thread-456", "C1234567", "U1234567")

            Then("같은 세션이 반환되어야 한다") {
                session1.id shouldBe session2.id
            }
        }
    }

    Given("세션에 메시지를 추가할 때") {
        val sessionManager = SessionManager()
        sessionManager.getOrCreate("thread-789", "C1234567", "U1234567")

        When("addMessage를 호출하면") {
            sessionManager.addMessage("thread-789", "user", "Hello")
            sessionManager.addMessage("thread-789", "assistant", "Hi there!")

            val context = sessionManager.getContext("thread-789")

            Then("메시지가 저장되어야 한다") {
                context.size shouldBe 2
            }

            Then("메시지 순서가 유지되어야 한다") {
                context[0].role shouldBe "user"
                context[0].content shouldBe "Hello"
                context[1].role shouldBe "assistant"
                context[1].content shouldBe "Hi there!"
            }
        }
    }

    Given("Claude 세션 ID를 설정할 때") {
        val sessionManager = SessionManager()
        sessionManager.getOrCreate("thread-101", "C1234567", "U1234567")

        When("setClaudeSessionId를 호출하면") {
            sessionManager.setClaudeSessionId("thread-101", "claude-session-abc123")

            val session = sessionManager.get("thread-101")

            Then("Claude 세션 ID가 저장되어야 한다") {
                session?.claudeSessionId shouldBe "claude-session-abc123"
            }
        }
    }

    Given("세션 메타데이터를 설정할 때") {
        val sessionManager = SessionManager()
        sessionManager.getOrCreate("thread-202", "C1234567", "U1234567")

        When("setMetadata를 호출하면") {
            sessionManager.setMetadata("thread-202", "projectId", "my-project")
            sessionManager.setMetadata("thread-202", "agentId", "code-reviewer")

            Then("메타데이터가 저장되어야 한다") {
                sessionManager.getMetadata("thread-202", "projectId") shouldBe "my-project"
                sessionManager.getMetadata("thread-202", "agentId") shouldBe "code-reviewer"
            }
        }
    }

    Given("세션을 종료할 때") {
        val sessionManager = SessionManager()
        sessionManager.getOrCreate("thread-303", "C1234567", "U1234567")

        When("close를 호출하면") {
            sessionManager.close("thread-303")

            Then("세션이 삭제되어야 한다") {
                sessionManager.get("thread-303") shouldBe null
            }

            Then("활성 세션 수가 감소해야 한다") {
                sessionManager.activeCount() shouldBe 0
            }
        }
    }

    Given("세션 통계를 조회할 때") {
        val sessionManager = SessionManager()

        sessionManager.getOrCreate("thread-1", "C1", "U1")
        sessionManager.getOrCreate("thread-2", "C1", "U2")
        sessionManager.addMessage("thread-1", "user", "msg1")
        sessionManager.addMessage("thread-1", "assistant", "msg2")
        sessionManager.addMessage("thread-2", "user", "msg3")

        When("getStats를 호출하면") {
            val stats = sessionManager.getStats()

            Then("정확한 통계가 반환되어야 한다") {
                stats.totalSessions shouldBe 2
                stats.totalMessages shouldBe 3
            }
        }
    }
})
