package ai.claudeflow.sdk

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class ClaudeFlowClientTest : DescribeSpec({

    describe("ClaudeFlowClient") {
        val mockServer = MockWebServer()

        beforeSpec {
            mockServer.start()
        }

        afterSpec {
            mockServer.shutdown()
        }

        describe("Builder") {
            it("should create client with default values") {
                val client = ClaudeFlowClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .build()

                client shouldNotBe null
                client.close()
            }

            it("should create client with custom values") {
                val client = ClaudeFlowClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .apiKey("test-api-key")
                    .defaultModel("opus")
                    .defaultMaxTurns(20)
                    .build()

                client shouldNotBe null
                client.close()
            }
        }

        describe("execute") {
            it("should execute prompt successfully") {
                mockServer.enqueue(MockResponse()
                    .setResponseCode(200)
                    .setBody("""
                        {
                            "requestId": "test-123",
                            "status": "SUCCESS",
                            "result": "Hello, World!",
                            "inputTokens": 100,
                            "outputTokens": 50,
                            "durationMs": 1000
                        }
                    """.trimIndent())
                    .addHeader("Content-Type", "application/json"))

                val client = ClaudeFlowClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .build()

                val result = client.execute("Hello")

                result.status shouldBe "SUCCESS"
                result.result shouldBe "Hello, World!"
                result.inputTokens shouldBe 100
                result.outputTokens shouldBe 50

                client.close()
            }

            it("should handle error response") {
                mockServer.enqueue(MockResponse()
                    .setResponseCode(200)
                    .setBody("""
                        {
                            "requestId": "test-456",
                            "status": "ERROR",
                            "error": "Something went wrong"
                        }
                    """.trimIndent())
                    .addHeader("Content-Type", "application/json"))

                val client = ClaudeFlowClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .build()

                val result = client.execute("Hello")

                result.status shouldBe "ERROR"
                result.error shouldBe "Something went wrong"

                client.close()
            }
        }

        describe("executeSuspend") {
            it("should execute async successfully") {
                mockServer.enqueue(MockResponse()
                    .setResponseCode(200)
                    .setBody("""
                        {
                            "requestId": "test-789",
                            "status": "SUCCESS",
                            "result": "Async result"
                        }
                    """.trimIndent())
                    .addHeader("Content-Type", "application/json"))

                val client = ClaudeFlowClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .build()

                runBlocking {
                    val result = client.executeSuspend("Hello async")
                    result.status shouldBe "SUCCESS"
                    result.result shouldBe "Async result"
                }

                client.close()
            }
        }

        describe("health") {
            it("should check health status") {
                mockServer.enqueue(MockResponse()
                    .setResponseCode(200)
                    .setBody("""
                        {
                            "status": "UP",
                            "timestamp": "2024-01-01T00:00:00Z"
                        }
                    """.trimIndent())
                    .addHeader("Content-Type", "application/json"))

                val client = ClaudeFlowClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .build()

                runBlocking {
                    val health = client.health()
                    health.status shouldBe "UP"
                }

                client.close()
            }
        }

        describe("agents") {
            it("should list agents") {
                mockServer.enqueue(MockResponse()
                    .setResponseCode(200)
                    .setBody("""
                        [
                            {
                                "id": "agent-1",
                                "name": "Code Reviewer",
                                "description": "Reviews code"
                            },
                            {
                                "id": "agent-2",
                                "name": "Test Writer",
                                "description": "Writes tests"
                            }
                        ]
                    """.trimIndent())
                    .addHeader("Content-Type", "application/json"))

                val client = ClaudeFlowClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .build()

                runBlocking {
                    val agents = client.listAgents()
                    agents.size shouldBe 2
                    agents[0].name shouldBe "Code Reviewer"
                    agents[1].name shouldBe "Test Writer"
                }

                client.close()
            }

            it("should test routing") {
                mockServer.enqueue(MockResponse()
                    .setResponseCode(200)
                    .setBody("""
                        {
                            "agentId": "code-reviewer",
                            "agentName": "Code Reviewer",
                            "method": "keyword",
                            "confidence": 0.95,
                            "reasoning": "Matched keyword: review"
                        }
                    """.trimIndent())
                    .addHeader("Content-Type", "application/json"))

                val client = ClaudeFlowClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .build()

                runBlocking {
                    val result = client.testRouting("Please review my code")
                    result.agentId shouldBe "code-reviewer"
                    result.method shouldBe "keyword"
                    result.confidence shouldBe 0.95
                }

                client.close()
            }
        }
    }

    describe("ExecuteResult extensions") {
        it("isSuccess should return true for SUCCESS status") {
            val result = ExecuteResult(
                requestId = "test",
                status = "SUCCESS",
                result = "Hello"
            )
            result.isSuccess shouldBe true
            result.isError shouldBe false
        }

        it("isError should return true for non-SUCCESS status") {
            val result = ExecuteResult(
                requestId = "test",
                status = "ERROR",
                error = "Failed"
            )
            result.isSuccess shouldBe false
            result.isError shouldBe true
        }

        it("totalTokens should sum input and output") {
            val result = ExecuteResult(
                requestId = "test",
                status = "SUCCESS",
                inputTokens = 100,
                outputTokens = 50
            )
            result.totalTokens shouldBe 150
        }
    }
})
