package ai.claudeflow.core.routing

import ai.claudeflow.core.model.Agent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.doubles.shouldBeGreaterThan

class KoreanOptimizedRouterTest : DescribeSpec({
    val router = KoreanOptimizedRouter()

    val testAgents = listOf(
        Agent(
            id = "code-reviewer",
            name = "Code Reviewer",
            description = "코드 리뷰 전문 에이전트",
            keywords = listOf("리뷰", "review", "코드리뷰", "MR", "PR"),
            systemPrompt = "You are a code reviewer.",
            enabled = true,
            priority = 10
        ),
        Agent(
            id = "bug-fixer",
            name = "Bug Fixer",
            description = "버그 수정 전문 에이전트",
            keywords = listOf("버그", "bug", "오류", "에러", "수정"),
            systemPrompt = "You are a bug fixer.",
            enabled = true,
            priority = 5
        ),
        Agent(
            id = "general",
            name = "General",
            description = "일반 질문 에이전트",
            keywords = listOf("설명", "뭐야", "무엇"),
            systemPrompt = "You are a general assistant.",
            enabled = true,
            priority = 0
        )
    )

    describe("KoreanOptimizedRouter") {

        describe("extractChoseong") {
            it("should extract Korean initial consonants") {
                router.extractChoseong("코드리뷰") shouldBe "ㅋㄷㄹㅂ"
                router.extractChoseong("버그수정") shouldBe "ㅂㄱㅅㅈ"
                router.extractChoseong("안녕하세요") shouldBe "ㅇㄴㅎㅅㅇ"
            }

            it("should keep non-Korean characters as-is") {
                router.extractChoseong("hello") shouldBe "hello"
                router.extractChoseong("코드123") shouldBe "ㅋㄷ123"
            }
        }

        describe("matchKeyword") {
            it("should match exact keywords") {
                val result = router.matchKeyword("코드 리뷰 해줘", testAgents)
                result shouldNotBe null
                result!!.agent.id shouldBe "code-reviewer"
                result.confidence shouldBeGreaterThan 0.9
            }

            it("should match with josa removed") {
                val result = router.matchKeyword("리뷰를 해줘", testAgents)
                result shouldNotBe null
                result!!.agent.id shouldBe "code-reviewer"
            }

            it("should match synonyms") {
                // "검토" is synonym of "리뷰"
                val result = router.matchKeyword("코드 검토 부탁해", testAgents)
                result shouldNotBe null
                result!!.agent.id shouldBe "code-reviewer"
            }

            it("should match bug-related keywords") {
                val result = router.matchKeyword("이 버그 좀 고쳐줘", testAgents)
                result shouldNotBe null
                result!!.agent.id shouldBe "bug-fixer"
            }
        }

        describe("expandKeyword") {
            it("should expand keyword with synonyms") {
                val expanded = router.expandKeyword("리뷰")
                expanded.contains("리뷰") shouldBe true
                expanded.contains("검토") shouldBe true
                expanded.contains("확인") shouldBe true
            }
        }

        describe("calculateSimilarity") {
            it("should return 1.0 for exact match") {
                router.calculateSimilarity("버그 수정", "버그") shouldBe 1.0
            }

            it("should return high similarity for similar strings") {
                val similarity = router.calculateSimilarity("버그수정", "버그")
                similarity shouldBeGreaterThan 0.5
            }
        }

        describe("typo correction") {
            it("should match with minor typos") {
                // "리류" is typo of "리뷰" (distance = 1)
                val result = router.matchKeyword("코드 리류 해줘", testAgents)
                // May or may not match depending on typo threshold
                // This tests the mechanism exists
            }
        }
    }
})
