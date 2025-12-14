package ai.claudeflow.sdk

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Duration

/**
 * Claude Flow SDK - Kotlin DSL Extensions
 *
 * Kotlin 스타일의 간결한 API를 제공
 *
 * 사용법:
 * ```kotlin
 * // DSL 빌더
 * val client = claudeFlow {
 *     baseUrl = "http://localhost:8080"
 *     apiKey = "your-api-key"
 *     timeout = 5.minutes
 * }
 *
 * // 실행 DSL
 * val result = client.execute {
 *     prompt = "코드 리뷰해줘"
 *     model = "opus"
 *     maxTurns = 20
 * }
 *
 * // Flow 연산자
 * client.executeFlow("분석해줘")
 *     .onSuccess { println(it.result) }
 *     .onError { println(it.error) }
 * ```
 */

// ==================== Client Builder DSL ====================

/**
 * Claude Flow 클라이언트 DSL 빌더
 */
fun claudeFlow(block: ClaudeFlowDsl.() -> Unit): ClaudeFlowClient {
    return ClaudeFlowDsl().apply(block).build()
}

class ClaudeFlowDsl {
    var baseUrl: String = "http://localhost:8080"
    var apiKey: String? = null
    var timeout: Duration = Duration.ofMinutes(5)
    var connectTimeout: Duration = Duration.ofSeconds(30)
    var defaultModel: String = "sonnet"
    var defaultMaxTurns: Int = 10
    var retryOnFailure: Boolean = true

    internal fun build(): ClaudeFlowClient {
        return ClaudeFlowClient.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .timeout(timeout)
            .connectTimeout(connectTimeout)
            .defaultModel(defaultModel)
            .defaultMaxTurns(defaultMaxTurns)
            .retryOnFailure(retryOnFailure)
            .build()
    }
}

// ==================== Execute DSL ====================

/**
 * 실행 옵션 DSL
 */
class ExecuteOptionsDsl {
    var prompt: String = ""
    var model: String? = null
    var maxTurns: Int? = null
    var projectId: String? = null
    var agentId: String? = null
    var userId: String? = null
    var threadTs: String? = null
    var workingDirectory: String? = null
    var systemPrompt: String? = null
    var allowedTools: List<String>? = null
    var deniedTools: List<String>? = null

    internal fun toOptions(): ExecuteOptions = ExecuteOptions(
        model = model,
        maxTurns = maxTurns,
        projectId = projectId,
        agentId = agentId,
        userId = userId,
        threadTs = threadTs,
        workingDirectory = workingDirectory,
        systemPrompt = systemPrompt,
        allowedTools = allowedTools,
        deniedTools = deniedTools
    )
}

/**
 * DSL 기반 실행
 */
fun ClaudeFlowClient.execute(block: ExecuteOptionsDsl.() -> Unit): ExecuteResult {
    val dsl = ExecuteOptionsDsl().apply(block)
    return execute(dsl.prompt, dsl.toOptions())
}

/**
 * DSL 기반 비동기 실행
 */
suspend fun ClaudeFlowClient.executeSuspending(block: ExecuteOptionsDsl.() -> Unit): ExecuteResult {
    val dsl = ExecuteOptionsDsl().apply(block)
    return executeSuspend(dsl.prompt, dsl.toOptions())
}

// ==================== Result Extensions ====================

/**
 * 실행 결과 체이닝
 */
class ExecuteResultChain(private val result: ExecuteResult) {
    fun onSuccess(block: (ExecuteResult) -> Unit): ExecuteResultChain {
        if (result.status == "SUCCESS") {
            block(result)
        }
        return this
    }

    fun onError(block: (ExecuteResult) -> Unit): ExecuteResultChain {
        if (result.status != "SUCCESS") {
            block(result)
        }
        return this
    }

    fun get(): ExecuteResult = result
}

/**
 * Flow 스타일 실행
 */
fun ClaudeFlowClient.executeFlow(prompt: String, options: ExecuteOptions = ExecuteOptions()): ExecuteResultChain {
    val result = execute(prompt, options)
    return ExecuteResultChain(result)
}

// ==================== Agent DSL ====================

/**
 * 에이전트 생성 DSL
 */
class AgentCreateDsl {
    var name: String = ""
    var description: String = ""
    var projectId: String? = null
    var model: String? = null
    var systemPrompt: String? = null
    var keywords: List<String> = emptyList()
    var patterns: List<String> = emptyList()

    internal fun toRequest(): AgentCreateRequest = AgentCreateRequest(
        name = name,
        description = description,
        projectId = projectId,
        model = model,
        systemPrompt = systemPrompt,
        keywords = keywords,
        patterns = patterns
    )
}

/**
 * DSL 기반 에이전트 생성
 */
suspend fun ClaudeFlowClient.createAgent(block: AgentCreateDsl.() -> Unit): AgentInfo {
    val dsl = AgentCreateDsl().apply(block)
    return createAgent(dsl.toRequest())
}

// ==================== Duration Extensions ====================

val Int.seconds: Duration get() = Duration.ofSeconds(this.toLong())
val Int.minutes: Duration get() = Duration.ofMinutes(this.toLong())
val Int.hours: Duration get() = Duration.ofHours(this.toLong())

val Long.seconds: Duration get() = Duration.ofSeconds(this)
val Long.minutes: Duration get() = Duration.ofMinutes(this)
val Long.hours: Duration get() = Duration.ofHours(this)

// ==================== Result Extensions ====================

/**
 * 결과가 성공인지 확인
 */
val ExecuteResult.isSuccess: Boolean get() = status == "SUCCESS"

/**
 * 결과가 실패인지 확인
 */
val ExecuteResult.isError: Boolean get() = status != "SUCCESS"

/**
 * 총 토큰 수
 */
val ExecuteResult.totalTokens: Int get() = inputTokens + outputTokens

/**
 * 결과 텍스트 (안전하게 가져오기)
 */
fun ExecuteResult.resultOrDefault(default: String = ""): String = result ?: default

/**
 * 에러 메시지 (안전하게 가져오기)
 */
fun ExecuteResult.errorOrDefault(default: String = "Unknown error"): String = error ?: default

// ==================== Utility Extensions ====================

/**
 * 클라이언트 사용 후 자동 정리
 */
inline fun <T> ClaudeFlowClient.use(block: (ClaudeFlowClient) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}

/**
 * 재시도 가능한 실행
 */
suspend fun ClaudeFlowClient.executeWithRetry(
    prompt: String,
    options: ExecuteOptions = ExecuteOptions(),
    maxRetries: Int = 3,
    delayMs: Long = 1000
): ExecuteResult {
    var lastResult: ExecuteResult? = null
    repeat(maxRetries) { attempt ->
        val result = executeSuspend(prompt, options)
        if (result.isSuccess) {
            return result
        }
        lastResult = result
        if (attempt < maxRetries - 1) {
            kotlinx.coroutines.delay(delayMs * (attempt + 1))
        }
    }
    return lastResult ?: ExecuteResult(
        requestId = "",
        status = "ERROR",
        error = "All retries failed"
    )
}

/**
 * 배치 실행
 */
suspend fun ClaudeFlowClient.executeBatch(
    prompts: List<String>,
    options: ExecuteOptions = ExecuteOptions(),
    concurrency: Int = 5
): List<ExecuteResult> {
    return coroutineScope {
        prompts.chunked(concurrency).flatMap { chunk ->
            chunk.map { prompt: String ->
                async {
                    executeSuspend(prompt, options)
                }
            }.map { deferred: Deferred<ExecuteResult> -> deferred.await() }
        }
    }
}
