package ai.claudeflow.core.routing

import ai.claudeflow.core.model.Agent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.string.shouldStartWith
import io.mockk.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class SemanticRouterTest : DescribeSpec({

    describe("SemanticRouter") {
        val testEmbedding = List(768) { 0.1 }
        val testAgents = listOf(
            Agent(
                id = "code-reviewer",
                name = "Code Reviewer",
                description = "코드 리뷰 전문가",
                keywords = listOf("리뷰", "review", "PR", "MR"),
                systemPrompt = "You are a code reviewer",
                priority = 10
            ),
            Agent(
                id = "bug-fixer",
                name = "Bug Fixer",
                description = "버그 수정 전문가",
                keywords = listOf("버그", "bug", "fix"),
                systemPrompt = "You are a bug fixer",
                priority = 20
            ),
            Agent(
                id = "general",
                name = "General Assistant",
                description = "일반 어시스턴트",
                keywords = emptyList(),
                systemPrompt = "You are a general assistant",
                priority = 0
            )
        )

        describe("classify") {
            context("정상적인 벡터 검색 결과가 있을 때") {
                it("가장 유사한 에이전트를 반환한다") {
                    // Given
                    val mockHttpClient = mockk<HttpClient>()
                    val mockEmbeddingResponse = mockk<HttpResponse<String>>()
                    val mockSearchResponse = mockk<HttpResponse<String>>()

                    every { mockEmbeddingResponse.statusCode() } returns 200
                    every { mockEmbeddingResponse.body() } returns """
                        {
                            "embedding": [${testEmbedding.joinToString(",")}]
                        }
                    """.trimIndent()

                    every { mockSearchResponse.statusCode() } returns 200
                    every { mockSearchResponse.body() } returns """
                        {
                            "result": [
                                {
                                    "score": 0.85,
                                    "payload": {
                                        "agent_id": "code-reviewer",
                                        "example": "코드 리뷰 해줘"
                                    }
                                }
                            ]
                        }
                    """.trimIndent()

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockEmbeddingResponse

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/points/search") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockSearchResponse

                    val router = SemanticRouter(
                        embeddingServiceUrl = "http://localhost:11434",
                        vectorDbUrl = "http://localhost:6333",
                        minScore = 0.7
                    )

                    // HttpClient를 모킹하기 위해 private 필드에 접근
                    val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                    httpClientField.isAccessible = true
                    httpClientField.set(router, mockHttpClient)

                    // When
                    val result = router.classify("코드 리뷰 해줘", testAgents)

                    // Then
                    result.shouldNotBeNull()
                    result.agent.id shouldBe "code-reviewer"
                    result.confidence shouldBeGreaterThan 0.7
                    result.matchedKeyword shouldStartWith "semantic:"
                }

                it("우선순위 보너스를 적용하여 에이전트를 선택한다") {
                    // Given
                    val mockHttpClient = mockk<HttpClient>()
                    val mockEmbeddingResponse = mockk<HttpResponse<String>>()
                    val mockSearchResponse = mockk<HttpResponse<String>>()

                    every { mockEmbeddingResponse.statusCode() } returns 200
                    every { mockEmbeddingResponse.body() } returns """
                        {
                            "embedding": [${testEmbedding.joinToString(",")}]
                        }
                    """.trimIndent()

                    // 두 개의 비슷한 점수, 하지만 우선순위가 다름
                    every { mockSearchResponse.statusCode() } returns 200
                    every { mockSearchResponse.body() } returns """
                        {
                            "result": [
                                {
                                    "score": 0.80,
                                    "payload": {
                                        "agent_id": "code-reviewer",
                                        "example": "코드 리뷰 해줘"
                                    }
                                },
                                {
                                    "score": 0.79,
                                    "payload": {
                                        "agent_id": "bug-fixer",
                                        "example": "버그 수정 해줘"
                                    }
                                }
                            ]
                        }
                    """.trimIndent()

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockEmbeddingResponse

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/points/search") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockSearchResponse

                    val router = SemanticRouter(
                        embeddingServiceUrl = "http://localhost:11434",
                        vectorDbUrl = "http://localhost:6333",
                        minScore = 0.7
                    )

                    val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                    httpClientField.isAccessible = true
                    httpClientField.set(router, mockHttpClient)

                    // When
                    val result = router.classify("버그 수정 해줘", testAgents)

                    // Then
                    result.shouldNotBeNull()
                    // bug-fixer의 우선순위(20)가 code-reviewer(10)보다 높아서 선택됨
                    // adjusted_score = 0.79 * (1 + 20/1000) = 0.79 * 1.02 = 0.8058
                    // vs 0.80 * (1 + 10/1000) = 0.80 * 1.01 = 0.808
                    // 실제로는 code-reviewer가 선택됨 (점수 차이가 우선순위 보너스보다 큼)
                    result.agent.id shouldBe "code-reviewer"
                }
            }

            context("minScore 필터링") {
                it("minScore보다 낮은 결과는 무시한다") {
                    // Given
                    val mockHttpClient = mockk<HttpClient>()
                    val mockEmbeddingResponse = mockk<HttpResponse<String>>()
                    val mockSearchResponse = mockk<HttpResponse<String>>()

                    every { mockEmbeddingResponse.statusCode() } returns 200
                    every { mockEmbeddingResponse.body() } returns """
                        {
                            "embedding": [${testEmbedding.joinToString(",")}]
                        }
                    """.trimIndent()

                    every { mockSearchResponse.statusCode() } returns 200
                    every { mockSearchResponse.body() } returns """
                        {
                            "result": [
                                {
                                    "score": 0.65,
                                    "payload": {
                                        "agent_id": "general",
                                        "example": "안녕하세요"
                                    }
                                }
                            ]
                        }
                    """.trimIndent()

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockEmbeddingResponse

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/points/search") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockSearchResponse

                    val router = SemanticRouter(
                        embeddingServiceUrl = "http://localhost:11434",
                        vectorDbUrl = "http://localhost:6333",
                        minScore = 0.7  // 0.65보다 높음
                    )

                    val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                    httpClientField.isAccessible = true
                    httpClientField.set(router, mockHttpClient)

                    // When
                    val result = router.classify("안녕하세요", testAgents)

                    // Then
                    result.shouldBeNull()
                }
            }

            context("빈 검색 결과") {
                it("검색 결과가 없으면 null을 반환한다") {
                    // Given
                    val mockHttpClient = mockk<HttpClient>()
                    val mockEmbeddingResponse = mockk<HttpResponse<String>>()
                    val mockSearchResponse = mockk<HttpResponse<String>>()

                    every { mockEmbeddingResponse.statusCode() } returns 200
                    every { mockEmbeddingResponse.body() } returns """
                        {
                            "embedding": [${testEmbedding.joinToString(",")}]
                        }
                    """.trimIndent()

                    every { mockSearchResponse.statusCode() } returns 200
                    every { mockSearchResponse.body() } returns """
                        {
                            "result": []
                        }
                    """.trimIndent()

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockEmbeddingResponse

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/points/search") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockSearchResponse

                    val router = SemanticRouter(
                        embeddingServiceUrl = "http://localhost:11434",
                        vectorDbUrl = "http://localhost:6333"
                    )

                    val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                    httpClientField.isAccessible = true
                    httpClientField.set(router, mockHttpClient)

                    // When
                    val result = router.classify("알 수 없는 메시지", testAgents)

                    // Then
                    result.shouldBeNull()
                }
            }

            context("빈 에이전트 목록") {
                it("에이전트 목록이 비어있으면 null을 반환한다") {
                    // Given
                    val mockHttpClient = mockk<HttpClient>()
                    val mockEmbeddingResponse = mockk<HttpResponse<String>>()
                    val mockSearchResponse = mockk<HttpResponse<String>>()

                    every { mockEmbeddingResponse.statusCode() } returns 200
                    every { mockEmbeddingResponse.body() } returns """
                        {
                            "embedding": [${testEmbedding.joinToString(",")}]
                        }
                    """.trimIndent()

                    every { mockSearchResponse.statusCode() } returns 200
                    every { mockSearchResponse.body() } returns """
                        {
                            "result": [
                                {
                                    "score": 0.85,
                                    "payload": {
                                        "agent_id": "unknown-agent",
                                        "example": "테스트"
                                    }
                                }
                            ]
                        }
                    """.trimIndent()

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockEmbeddingResponse

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/points/search") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockSearchResponse

                    val router = SemanticRouter(
                        embeddingServiceUrl = "http://localhost:11434",
                        vectorDbUrl = "http://localhost:6333"
                    )

                    val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                    httpClientField.isAccessible = true
                    httpClientField.set(router, mockHttpClient)

                    // When
                    val result = router.classify("테스트", emptyList())

                    // Then
                    result.shouldBeNull()
                }
            }

            context("에러 케이스") {
                it("임베딩 요청이 실패하면 null을 반환한다") {
                    // Given
                    val mockHttpClient = mockk<HttpClient>()
                    val mockEmbeddingResponse = mockk<HttpResponse<String>>()

                    every { mockEmbeddingResponse.statusCode() } returns 500
                    every { mockEmbeddingResponse.body() } returns "Internal Server Error"

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockEmbeddingResponse

                    val router = SemanticRouter(
                        embeddingServiceUrl = "http://localhost:11434",
                        vectorDbUrl = "http://localhost:6333"
                    )

                    val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                    httpClientField.isAccessible = true
                    httpClientField.set(router, mockHttpClient)

                    // When
                    val result = router.classify("테스트", testAgents)

                    // Then
                    result.shouldBeNull()
                }

                it("벡터 검색 요청이 실패하면 null을 반환한다") {
                    // Given
                    val mockHttpClient = mockk<HttpClient>()
                    val mockEmbeddingResponse = mockk<HttpResponse<String>>()
                    val mockSearchResponse = mockk<HttpResponse<String>>()

                    every { mockEmbeddingResponse.statusCode() } returns 200
                    every { mockEmbeddingResponse.body() } returns """
                        {
                            "embedding": [${testEmbedding.joinToString(",")}]
                        }
                    """.trimIndent()

                    every { mockSearchResponse.statusCode() } returns 500
                    every { mockSearchResponse.body() } returns "Internal Server Error"

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockEmbeddingResponse

                    every {
                        mockHttpClient.send(
                            match<HttpRequest> { it.uri().path.contains("/points/search") },
                            any<HttpResponse.BodyHandler<String>>()
                        )
                    } returns mockSearchResponse

                    val router = SemanticRouter(
                        embeddingServiceUrl = "http://localhost:11434",
                        vectorDbUrl = "http://localhost:6333"
                    )

                    val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                    httpClientField.isAccessible = true
                    httpClientField.set(router, mockHttpClient)

                    // When
                    val result = router.classify("테스트", testAgents)

                    // Then
                    result.shouldBeNull()
                }

                it("예외 발생 시 null을 반환하고 폴백한다") {
                    // Given
                    val mockHttpClient = mockk<HttpClient>()

                    every {
                        mockHttpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
                    } throws RuntimeException("Network error")

                    val router = SemanticRouter(
                        embeddingServiceUrl = "http://localhost:11434",
                        vectorDbUrl = "http://localhost:6333"
                    )

                    val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                    httpClientField.isAccessible = true
                    httpClientField.set(router, mockHttpClient)

                    // When
                    val result = router.classify("테스트", testAgents)

                    // Then
                    result.shouldBeNull()
                }
            }
        }

        describe("indexAgentExamples") {
            it("에이전트 예제를 벡터 DB에 인덱싱한다") {
                // Given
                val mockHttpClient = mockk<HttpClient>()
                val mockCollectionResponse = mockk<HttpResponse<String>>()
                val mockEmbeddingResponse = mockk<HttpResponse<String>>()
                val mockUpsertResponse = mockk<HttpResponse<String>>()

                every { mockCollectionResponse.statusCode() } returns 200
                every { mockEmbeddingResponse.statusCode() } returns 200
                every { mockEmbeddingResponse.body() } returns """
                    {
                        "embedding": [${testEmbedding.joinToString(",")}]
                    }
                """.trimIndent()
                every { mockUpsertResponse.statusCode() } returns 200

                every {
                    mockHttpClient.send(
                        match<HttpRequest> { it.method() == "PUT" && it.uri().path.endsWith("/collections/claude-flow-agents") },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                } returns mockCollectionResponse

                every {
                    mockHttpClient.send(
                        match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                } returns mockEmbeddingResponse

                every {
                    mockHttpClient.send(
                        match<HttpRequest> { it.uri().path.contains("/points") && it.method() == "PUT" },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                } returns mockUpsertResponse

                val router = SemanticRouter(
                    embeddingServiceUrl = "http://localhost:11434",
                    vectorDbUrl = "http://localhost:6333"
                )

                val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                httpClientField.isAccessible = true
                httpClientField.set(router, mockHttpClient)

                val examples = mapOf(
                    "code-reviewer" to listOf("코드 리뷰 해줘", "PR 봐줘"),
                    "bug-fixer" to listOf("버그 수정해줘", "에러 고쳐줘")
                )

                // When
                router.indexAgentExamples(testAgents.take(2), examples)

                // Then
                verify(exactly = 1) {
                    mockHttpClient.send(
                        match<HttpRequest> { it.method() == "PUT" && it.uri().path.endsWith("/collections/claude-flow-agents") },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                }
                verify(exactly = 4) {  // 2개 에이전트 * 2개 예제
                    mockHttpClient.send(
                        match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                }
                verify(exactly = 4) {  // 2개 에이전트 * 2개 예제
                    mockHttpClient.send(
                        match<HttpRequest> { it.uri().path.contains("/points") && it.method() == "PUT" },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                }
            }

            it("예제가 없는 에이전트는 건너뛴다") {
                // Given
                val mockHttpClient = mockk<HttpClient>()
                val mockCollectionResponse = mockk<HttpResponse<String>>()

                every { mockCollectionResponse.statusCode() } returns 200

                every {
                    mockHttpClient.send(
                        match<HttpRequest> { it.method() == "PUT" && it.uri().path.endsWith("/collections/claude-flow-agents") },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                } returns mockCollectionResponse

                val router = SemanticRouter(
                    embeddingServiceUrl = "http://localhost:11434",
                    vectorDbUrl = "http://localhost:6333"
                )

                val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                httpClientField.isAccessible = true
                httpClientField.set(router, mockHttpClient)

                // When - 예제가 없는 에이전트
                router.indexAgentExamples(testAgents, emptyMap())

                // Then - 컬렉션 생성만 호출되고 임베딩/upsert는 호출되지 않음
                verify(exactly = 1) {
                    mockHttpClient.send(
                        match<HttpRequest> { it.method() == "PUT" && it.uri().path.endsWith("/collections/claude-flow-agents") },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                }
                verify(exactly = 0) {
                    mockHttpClient.send(
                        match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                }
            }
        }

        describe("우선순위 보너스 계산") {
            it("adjusted_score = score * (1.0 + priority/1000.0) 공식을 사용한다") {
                // Given
                val mockHttpClient = mockk<HttpClient>()
                val mockEmbeddingResponse = mockk<HttpResponse<String>>()
                val mockSearchResponse = mockk<HttpResponse<String>>()

                every { mockEmbeddingResponse.statusCode() } returns 200
                every { mockEmbeddingResponse.body() } returns """
                    {
                        "embedding": [${testEmbedding.joinToString(",")}]
                    }
                """.trimIndent()

                // 같은 점수지만 우선순위가 크게 다른 경우
                every { mockSearchResponse.statusCode() } returns 200
                every { mockSearchResponse.body() } returns """
                    {
                        "result": [
                            {
                                "score": 0.75,
                                "payload": {
                                    "agent_id": "bug-fixer",
                                    "example": "버그 수정"
                                }
                            },
                            {
                                "score": 0.75,
                                "payload": {
                                    "agent_id": "general",
                                    "example": "일반 질문"
                                }
                            }
                        ]
                    }
                """.trimIndent()

                every {
                    mockHttpClient.send(
                        match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                } returns mockEmbeddingResponse

                every {
                    mockHttpClient.send(
                        match<HttpRequest> { it.uri().path.contains("/points/search") },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                } returns mockSearchResponse

                val router = SemanticRouter(
                    embeddingServiceUrl = "http://localhost:11434",
                    vectorDbUrl = "http://localhost:6333",
                    minScore = 0.7
                )

                val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                httpClientField.isAccessible = true
                httpClientField.set(router, mockHttpClient)

                // When
                val result = router.classify("버그 관련 질문", testAgents)

                // Then
                result.shouldNotBeNull()
                // bug-fixer: 0.75 * (1 + 20/1000) = 0.75 * 1.02 = 0.765
                // general: 0.75 * (1 + 0/1000) = 0.75 * 1.0 = 0.75
                result.agent.id shouldBe "bug-fixer"
                result.confidence shouldBeGreaterThan 0.75
                result.confidence shouldBeLessThan 0.77
            }

            it("조정된 점수는 최대 1.0으로 제한된다") {
                // Given
                val mockHttpClient = mockk<HttpClient>()
                val mockEmbeddingResponse = mockk<HttpResponse<String>>()
                val mockSearchResponse = mockk<HttpResponse<String>>()

                every { mockEmbeddingResponse.statusCode() } returns 200
                every { mockEmbeddingResponse.body() } returns """
                    {
                        "embedding": [${testEmbedding.joinToString(",")}]
                    }
                """.trimIndent()

                // 높은 점수 + 높은 우선순위 = 1.0 초과 가능
                every { mockSearchResponse.statusCode() } returns 200
                every { mockSearchResponse.body() } returns """
                    {
                        "result": [
                            {
                                "score": 0.99,
                                "payload": {
                                    "agent_id": "bug-fixer",
                                    "example": "버그 수정"
                                }
                            }
                        ]
                    }
                """.trimIndent()

                every {
                    mockHttpClient.send(
                        match<HttpRequest> { it.uri().path.contains("/api/embeddings") },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                } returns mockEmbeddingResponse

                every {
                    mockHttpClient.send(
                        match<HttpRequest> { it.uri().path.contains("/points/search") },
                        any<HttpResponse.BodyHandler<String>>()
                    )
                } returns mockSearchResponse

                val router = SemanticRouter(
                    embeddingServiceUrl = "http://localhost:11434",
                    vectorDbUrl = "http://localhost:6333",
                    minScore = 0.7
                )

                val httpClientField = SemanticRouter::class.java.getDeclaredField("httpClient")
                httpClientField.isAccessible = true
                httpClientField.set(router, mockHttpClient)

                // When
                val result = router.classify("버그 수정", testAgents)

                // Then
                result.shouldNotBeNull()
                // 0.99 * (1 + 20/1000) = 0.99 * 1.02 = 1.0098 → 1.0으로 제한
                result.confidence shouldBe 1.0
            }
        }
    }
})
