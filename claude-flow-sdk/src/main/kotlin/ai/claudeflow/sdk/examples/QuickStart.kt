package ai.claudeflow.sdk.examples

import ai.claudeflow.sdk.*
import kotlinx.coroutines.*

/**
 * Claude Flow SDK 빠른 시작 예제
 *
 * 이 파일은 SDK의 다양한 사용 방법을 보여줍니다.
 */
object QuickStart {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        // 1. 기본 사용법
        basicUsage()

        // 2. DSL 사용법
        dslUsage()

        // 3. 비동기 실행
        asyncUsage()

        // 4. 배치 처리
        batchUsage()

        // 5. 재시도 로직
        retryUsage()

        // 6. WebSocket 스트리밍
        // webSocketUsage()
    }

    /**
     * 1. 기본 사용법
     */
    private fun basicUsage() {
        println("=== 기본 사용법 ===")

        // 클라이언트 생성
        val client = ClaudeFlowClient.builder()
            .baseUrl("http://localhost:8080")
            .apiKey("your-api-key")
            .defaultModel("sonnet")
            .defaultMaxTurns(10)
            .build()

        try {
            // 동기 실행
            val result = client.execute("Hello, Claude!")
            println("Result: ${result.result}")
            println("Tokens: ${result.inputTokens} in, ${result.outputTokens} out")
            println("Duration: ${result.durationMs}ms")
        } finally {
            client.close()
        }
    }

    /**
     * 2. Kotlin DSL 사용법
     */
    private fun dslUsage() {
        println("\n=== DSL 사용법 ===")

        // DSL로 클라이언트 생성
        val client = claudeFlow {
            baseUrl = "http://localhost:8080"
            apiKey = "your-api-key"
            timeout = 5.minutes
            defaultModel = "sonnet"
        }

        try {
            // DSL로 실행
            val result = client.execute {
                prompt = "코드 리뷰해줘"
                model = "opus"
                maxTurns = 20
                projectId = "my-project"
            }

            // 체이닝 API
            client.executeFlow("분석해줘")
                .onSuccess { println("Success: ${it.result}") }
                .onError { println("Error: ${it.error}") }
        } finally {
            client.close()
        }
    }

    /**
     * 3. 비동기 실행
     */
    private suspend fun asyncUsage() {
        println("\n=== 비동기 실행 ===")

        val client = claudeFlow {
            baseUrl = "http://localhost:8080"
        }

        try {
            // Coroutine 기반 실행
            val result = client.executeSuspend("테스트 작성해줘")
            println("Async result: ${result.result}")

            // Callback 기반 실행
            client.executeAsync("버그 수정해줘") { result ->
                println("Callback result: ${result.result}")
            }

            // 잠시 대기 (callback 완료 대기)
            delay(1000)
        } finally {
            client.close()
        }
    }

    /**
     * 4. 배치 처리
     */
    private suspend fun batchUsage() {
        println("\n=== 배치 처리 ===")

        val client = claudeFlow {
            baseUrl = "http://localhost:8080"
        }

        try {
            val prompts = listOf(
                "파일1 분석해줘",
                "파일2 분석해줘",
                "파일3 분석해줘",
                "파일4 분석해줘",
                "파일5 분석해줘"
            )

            // 동시성 5로 배치 실행
            val results = client.executeBatch(prompts, concurrency = 3)

            results.forEachIndexed { index, result ->
                println("Result $index: ${result.status} - ${result.result?.take(50)}")
            }
        } finally {
            client.close()
        }
    }

    /**
     * 5. 재시도 로직
     */
    private suspend fun retryUsage() {
        println("\n=== 재시도 로직 ===")

        val client = claudeFlow {
            baseUrl = "http://localhost:8080"
            retryOnFailure = true
        }

        try {
            // 최대 3번 재시도, 지수 백오프
            val result = client.executeWithRetry(
                prompt = "불안정한 작업 실행",
                maxRetries = 3,
                delayMs = 1000
            )

            println("Retry result: ${result.status}")
        } finally {
            client.close()
        }
    }

    /**
     * 6. WebSocket 스트리밍
     */
    private suspend fun webSocketUsage() {
        println("\n=== WebSocket 스트리밍 ===")

        val wsClient = ClaudeFlowWebSocket.connect("ws://localhost:8080/ws") {
            apiKey = "your-api-key"
            autoReconnect = true
        }

        try {
            // 연결 상태 모니터링
            coroutineScope {
                launch {
                    wsClient.connectionState.collect { state ->
                        println("Connection state: $state")
                    }
                }

                // 이벤트 구독
                launch {
                    wsClient.events.collect { event ->
                        when (event) {
                            is WebSocketEvent.Connected -> println("Connected!")
                            is WebSocketEvent.Disconnected -> println("Disconnected!")
                            is WebSocketEvent.Error -> println("Error: ${event.message}")
                            is WebSocketEvent.ExecutionStarted -> println("Started: ${event.requestId}")
                            is WebSocketEvent.ExecutionProgress -> println("Progress: ${event.progress}%")
                            is WebSocketEvent.ExecutionCompleted -> println("Completed: ${event.requestId}")
                        }
                    }
                }

                // 스트리밍 실행
                wsClient.executeStreaming("긴 분석 작업 실행해줘").collect { chunk ->
                    print(chunk) // 실시간으로 출력
                }
            }
        } finally {
            wsClient.disconnect()
        }
    }
}

/**
 * 에이전트 관리 예제
 */
object AgentManagement {

    suspend fun example() {
        val client = claudeFlow {
            baseUrl = "http://localhost:8080"
        }

        try {
            // 에이전트 목록 조회
            val agents = client.listAgents()
            println("Agents: ${agents.map { it.name }}")

            // 에이전트 생성 (DSL)
            val newAgent = client.createAgent {
                name = "Security Auditor"
                description = "코드 보안 취약점 분석"
                keywords = listOf("보안", "취약점", "security", "vulnerability")
                patterns = listOf(".*보안.*검사.*", ".*취약점.*분석.*")
                systemPrompt = "당신은 보안 전문가입니다. 코드의 보안 취약점을 분석해주세요."
            }
            println("Created agent: ${newAgent.id}")

            // 라우팅 테스트
            val routing = client.testRouting("이 코드의 보안 취약점을 분석해줘")
            println("Routing: ${routing.agentName} (${routing.confidence})")
        } finally {
            client.close()
        }
    }
}

/**
 * 사용자 컨텍스트 관리 예제
 */
object UserContextManagement {

    suspend fun example() {
        val client = claudeFlow {
            baseUrl = "http://localhost:8080"
        }

        try {
            val userId = "user123"

            // 사용자 컨텍스트 조회
            val context = client.getUserContext(userId)
            println("User summary: ${context.summary}")
            println("User rules: ${context.rules}")

            // 규칙 추가
            val updatedContext = client.addUserRule(userId, "항상 한국어로 응답해주세요")
            println("Updated rules: ${updatedContext.rules}")
        } finally {
            client.close()
        }
    }
}

/**
 * 메트릭스 및 모니터링 예제
 */
object MetricsMonitoring {

    suspend fun example() {
        val client = claudeFlow {
            baseUrl = "http://localhost:8080"
        }

        try {
            // 시스템 메트릭스
            val metrics = client.getMetrics()
            println("Total executions: ${metrics.totalExecutions}")
            println("Success rate: ${metrics.successfulExecutions.toDouble() / metrics.totalExecutions * 100}%")
            println("P95 latency: ${metrics.p95DurationMs}ms")
            println("Cache hit rate: ${metrics.cacheHitRate}%")

            // 캐시 통계
            val cacheStats = client.getCacheStats()
            println("L1 hit rate: ${cacheStats.l1HitRate}%")

            // 헬스 체크
            val health = client.healthDetailed()
            println("Health: ${health.status}")
            health.components.forEach { (name, status) ->
                println("  $name: ${status.status}")
            }
        } finally {
            client.close()
        }
    }
}
