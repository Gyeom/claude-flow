package ai.claudeflow.core.routing

import ai.claudeflow.core.model.Agent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class AgentRouterTest : BehaviorSpec({

    Given("AgentRouter가 기본 에이전트로 초기화되었을 때") {
        val router = AgentRouter()

        Then("3개의 기본 에이전트가 있어야 한다") {
            router.listAgents().size shouldBe 3
        }

        Then("general, code-reviewer, bug-fixer 에이전트가 있어야 한다") {
            val agentIds = router.listAgents().map { it.id }
            agentIds shouldBe listOf("code-reviewer", "bug-fixer", "general")
        }
    }

    Given("코드 리뷰 관련 메시지가 주어졌을 때") {
        val router = AgentRouter()

        When("'코드 리뷰 해줘' 메시지로 라우팅하면") {
            val match = router.route("코드 리뷰 해줘")

            Then("code-reviewer 에이전트가 선택되어야 한다") {
                match.agent.id shouldBe "code-reviewer"
            }

            Then("confidence가 높아야 한다") {
                match.confidence shouldBeGreaterThan 0.8
            }
        }

        When("'MR 좀 봐줘' 메시지로 라우팅하면") {
            val match = router.route("MR 좀 봐줘")

            Then("code-reviewer 에이전트가 선택되어야 한다") {
                match.agent.id shouldBe "code-reviewer"
            }
        }

        When("'PR #123 리뷰해줘' 메시지로 라우팅하면") {
            val match = router.route("PR #123 리뷰해줘")

            Then("code-reviewer 에이전트가 선택되어야 한다") {
                match.agent.id shouldBe "code-reviewer"
            }
        }
    }

    Given("버그 수정 관련 메시지가 주어졌을 때") {
        val router = AgentRouter()

        When("'버그 수정해줘' 메시지로 라우팅하면") {
            val match = router.route("버그 수정해줘")

            Then("bug-fixer 에이전트가 선택되어야 한다") {
                match.agent.id shouldBe "bug-fixer"
            }
        }

        When("'에러 발생하는데 고쳐줘' 메시지로 라우팅하면") {
            val match = router.route("에러 발생하는데 고쳐줘")

            Then("bug-fixer 에이전트가 선택되어야 한다") {
                match.agent.id shouldBe "bug-fixer"
            }
        }

        When("'NullPointerException 수정' 메시지로 라우팅하면") {
            val match = router.route("NullPointerException 수정")

            Then("bug-fixer 에이전트가 선택되어야 한다") {
                match.agent.id shouldBe "bug-fixer"
            }
        }
    }

    Given("일반 질문 메시지가 주어졌을 때") {
        val router = AgentRouter()

        When("'이거 어떻게 하는거야?' 메시지로 라우팅하면") {
            val match = router.route("이거 어떻게 하는거야?")

            Then("general 에이전트가 선택되어야 한다") {
                match.agent.id shouldBe "general"
            }
        }

        When("매칭되는 키워드가 없는 메시지로 라우팅하면") {
            val match = router.route("안녕하세요")

            Then("general 에이전트로 폴백되어야 한다") {
                match.agent.id shouldBe "general"
            }

            Then("confidence가 낮아야 한다") {
                match.confidence shouldBe 0.5
            }
        }
    }

    Given("에이전트 CRUD 기능 테스트") {
        val router = AgentRouter()

        When("새 에이전트를 추가하면") {
            val newAgent = Agent(
                id = "test-agent",
                name = "Test Agent",
                description = "Test description",
                keywords = listOf("테스트", "test"),
                systemPrompt = "You are a test agent"
            )
            val result = router.addAgent(newAgent)

            Then("추가가 성공해야 한다") {
                result shouldBe true
            }

            Then("에이전트 목록에 포함되어야 한다") {
                router.listAgents().any { it.id == "test-agent" } shouldBe true
            }

            Then("'테스트 해줘' 메시지로 라우팅되어야 한다") {
                val match = router.route("테스트 해줘")
                match.agent.id shouldBe "test-agent"
            }
        }

        When("기존 에이전트를 업데이트하면") {
            val update = AgentUpdate(
                name = "Updated Test Agent",
                description = "Updated description"
            )
            val result = router.updateAgent("test-agent", update)

            Then("업데이트가 성공해야 한다") {
                result shouldBe true
            }

            Then("에이전트 이름이 변경되어야 한다") {
                router.listAllAgents().find { it.id == "test-agent" }?.name shouldBe "Updated Test Agent"
            }
        }

        When("에이전트를 비활성화하면") {
            router.setAgentEnabled("test-agent", false)

            Then("활성 에이전트 목록에서 제외되어야 한다") {
                router.listAgents().any { it.id == "test-agent" } shouldBe false
            }

            Then("전체 에이전트 목록에는 있어야 한다") {
                router.listAllAgents().any { it.id == "test-agent" } shouldBe true
            }
        }

        When("커스텀 에이전트를 삭제하면") {
            val result = router.removeAgent("test-agent")

            Then("삭제가 성공해야 한다") {
                result shouldBe true
            }

            Then("에이전트 목록에서 제외되어야 한다") {
                router.listAllAgents().any { it.id == "test-agent" } shouldBe false
            }
        }

        When("기본 에이전트를 삭제하려고 하면") {
            val result = router.removeAgent("general")

            Then("삭제가 실패해야 한다") {
                result shouldBe false
            }

            Then("에이전트가 여전히 존재해야 한다") {
                router.listAgents().any { it.id == "general" } shouldBe true
            }
        }
    }
})
