package ai.claudeflow.sdk

import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import mu.KotlinLogging
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Claude Flow SDK Client
 *
 * 개발자가 Claude Flow를 쉽게 통합할 수 있는 공식 SDK
 *
 * 사용법:
 * ```kotlin
 * val client = ClaudeFlowClient.builder()
 *     .baseUrl("http://localhost:8080")
 *     .apiKey("your-api-key")
 *     .timeout(Duration.ofMinutes(5))
 *     .build()
 *
 * // 동기 실행
 * val result = client.execute("코드 리뷰해줘")
 *
 * // 비동기 실행
 * client.executeAsync("테스트 작성해줘") { result ->
 *     println(result.result)
 * }
 *
 * // Coroutine 지원
 * launch {
 *     val result = client.executeSuspend("버그 수정해줘")
 * }
 * ```
 */
class ClaudeFlowClient private constructor(
    private val baseUrl: String,
    private val apiKey: String?,
    private val defaultModel: String,
    private val defaultMaxTurns: Int,
    private val httpClient: OkHttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        fun builder() = Builder()

        /**
         * 빠른 생성
         */
        fun create(baseUrl: String, apiKey: String? = null): ClaudeFlowClient {
            return builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build()
        }
    }

    /**
     * Builder Pattern
     */
    class Builder {
        private var baseUrl: String = "http://localhost:8080"
        private var apiKey: String? = null
        private var timeout: Duration = Duration.ofMinutes(5)
        private var defaultModel: String = "sonnet"
        private var defaultMaxTurns: Int = 10
        private var connectTimeout: Duration = Duration.ofSeconds(30)
        private var retryOnFailure: Boolean = true

        fun baseUrl(url: String) = apply { this.baseUrl = url.trimEnd('/') }
        fun apiKey(key: String?) = apply { this.apiKey = key }
        fun timeout(duration: Duration) = apply { this.timeout = duration }
        fun defaultModel(model: String) = apply { this.defaultModel = model }
        fun defaultMaxTurns(turns: Int) = apply { this.defaultMaxTurns = turns }
        fun connectTimeout(duration: Duration) = apply { this.connectTimeout = duration }
        fun retryOnFailure(retry: Boolean) = apply { this.retryOnFailure = retry }

        fun build(): ClaudeFlowClient {
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(retryOnFailure)
                .build()

            return ClaudeFlowClient(
                baseUrl = baseUrl,
                apiKey = apiKey,
                defaultModel = defaultModel,
                defaultMaxTurns = defaultMaxTurns,
                httpClient = httpClient
            )
        }
    }

    // ==================== Execute API ====================

    /**
     * 동기 실행
     */
    fun execute(prompt: String, options: ExecuteOptions = ExecuteOptions()): ExecuteResult {
        return runBlocking {
            executeSuspend(prompt, options)
        }
    }

    /**
     * Coroutine 기반 실행
     */
    suspend fun executeSuspend(prompt: String, options: ExecuteOptions = ExecuteOptions()): ExecuteResult {
        val request = ExecuteRequest(
            prompt = prompt,
            model = options.model ?: defaultModel,
            maxTurns = options.maxTurns ?: defaultMaxTurns,
            projectId = options.projectId,
            agentId = options.agentId,
            userId = options.userId,
            threadTs = options.threadTs,
            workingDirectory = options.workingDirectory,
            systemPrompt = options.systemPrompt,
            allowedTools = options.allowedTools,
            deniedTools = options.deniedTools
        )

        return post("/api/execute", request)
    }

    /**
     * Callback 기반 비동기 실행
     */
    fun executeAsync(
        prompt: String,
        options: ExecuteOptions = ExecuteOptions(),
        callback: (ExecuteResult) -> Unit
    ) {
        scope.launch {
            try {
                val result = executeSuspend(prompt, options)
                callback(result)
            } catch (e: Exception) {
                callback(ExecuteResult(
                    requestId = "",
                    status = "ERROR",
                    error = e.message
                ))
            }
        }
    }

    // ==================== Agent API ====================

    /**
     * 에이전트 목록 조회
     */
    suspend fun listAgents(projectId: String? = null): List<AgentInfo> {
        val path = if (projectId != null) "/api/agents?projectId=$projectId" else "/api/agents"
        return get(path)
    }

    /**
     * 에이전트 생성
     */
    suspend fun createAgent(agent: AgentCreateRequest): AgentInfo {
        return post("/api/agents", agent)
    }

    /**
     * 에이전트 조회
     */
    suspend fun getAgent(agentId: String): AgentInfo {
        return get("/api/agents/$agentId")
    }

    /**
     * 에이전트 삭제
     */
    suspend fun deleteAgent(agentId: String): Boolean {
        return delete("/api/agents/$agentId")
    }

    /**
     * 라우팅 테스트
     */
    suspend fun testRouting(prompt: String, projectId: String? = null): RoutingResult {
        val request = RoutingTestRequest(prompt = prompt, projectId = projectId)
        return post("/api/agents/route/test", request)
    }

    // ==================== User Context API ====================

    /**
     * 사용자 컨텍스트 조회
     */
    suspend fun getUserContext(userId: String): UserContext {
        return get("/api/users/$userId/context")
    }

    /**
     * 사용자 규칙 추가
     */
    suspend fun addUserRule(userId: String, rule: String): UserContext {
        val request = AddRuleRequest(rule = rule)
        return post("/api/users/$userId/rules", request)
    }

    /**
     * 사용자 규칙 삭제
     */
    suspend fun deleteUserRule(userId: String, ruleIndex: Int): UserContext {
        return delete("/api/users/$userId/rules/$ruleIndex")
    }

    // ==================== Metrics API ====================

    /**
     * 시스템 메트릭스 조회
     */
    suspend fun getMetrics(): SystemMetrics {
        return get("/api/metrics")
    }

    /**
     * 사용자별 메트릭스 조회
     */
    suspend fun getUserMetrics(userId: String): UserMetrics {
        return get("/api/metrics/users/$userId")
    }

    /**
     * 에이전트별 메트릭스 조회
     */
    suspend fun getAgentMetrics(agentId: String): AgentMetrics {
        return get("/api/metrics/agents/$agentId")
    }

    // ==================== Health API ====================

    /**
     * 헬스 체크
     */
    suspend fun health(): HealthStatus {
        return get("/health")
    }

    /**
     * 상세 헬스 체크
     */
    suspend fun healthDetailed(): DetailedHealthStatus {
        return get("/health/detailed")
    }

    // ==================== Session API ====================

    /**
     * 세션 정보 조회
     */
    suspend fun getSession(sessionId: String): SessionInfo {
        return get("/api/sessions/$sessionId")
    }

    /**
     * 세션 목록 조회
     */
    suspend fun listSessions(userId: String? = null): List<SessionInfo> {
        val path = if (userId != null) "/api/sessions?userId=$userId" else "/api/sessions"
        return get(path)
    }

    // ==================== Cache API ====================

    /**
     * 캐시 통계 조회
     */
    suspend fun getCacheStats(): CacheStats {
        return get("/api/cache/stats")
    }

    /**
     * 캐시 초기화
     */
    suspend fun clearCache(type: String? = null): Boolean {
        val path = if (type != null) "/api/cache/clear?type=$type" else "/api/cache/clear"
        return post(path, EmptyRequest())
    }

    // ==================== HTTP Helpers ====================

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .get()
            .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        executeRequest(request)
    }

    private suspend inline fun <reified R, reified T> post(path: String, body: R): T = withContext(Dispatchers.IO) {
        val jsonBody = json.encodeToString(body)
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        executeRequest(request)
    }

    private suspend inline fun <reified T> delete(path: String): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .delete()
            .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        executeRequest(request)
    }

    private inline fun <reified T> executeRequest(request: Request): T {
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            logger.error { "API request failed: ${response.code} - $errorBody" }
            throw ClaudeFlowException(
                code = response.code,
                message = "API request failed: ${response.code}",
                details = errorBody
            )
        }

        val body = response.body?.string() ?: throw ClaudeFlowException(
            code = 500,
            message = "Empty response body"
        )

        return json.decodeFromString(body)
    }

    /**
     * 리소스 정리
     */
    fun close() {
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}

/**
 * Claude Flow SDK Exception
 */
class ClaudeFlowException(
    val code: Int,
    override val message: String,
    val details: String? = null
) : Exception(message)

// ==================== Request/Response DTOs ====================

@Serializable
data class ExecuteRequest(
    val prompt: String,
    val model: String? = null,
    val maxTurns: Int? = null,
    val projectId: String? = null,
    val agentId: String? = null,
    val userId: String? = null,
    val threadTs: String? = null,
    val workingDirectory: String? = null,
    val systemPrompt: String? = null,
    val allowedTools: List<String>? = null,
    val deniedTools: List<String>? = null
)

@Serializable
data class ExecuteResult(
    val requestId: String,
    val status: String,
    val result: String? = null,
    val error: String? = null,
    val sessionId: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cost: Double? = null,
    val durationMs: Long = 0,
    val agentId: String? = null,
    val routingMethod: String? = null
)

data class ExecuteOptions(
    val model: String? = null,
    val maxTurns: Int? = null,
    val projectId: String? = null,
    val agentId: String? = null,
    val userId: String? = null,
    val threadTs: String? = null,
    val workingDirectory: String? = null,
    val systemPrompt: String? = null,
    val allowedTools: List<String>? = null,
    val deniedTools: List<String>? = null
)

@Serializable
data class AgentInfo(
    val id: String,
    val name: String,
    val description: String,
    val projectId: String? = null,
    val enabled: Boolean = true,
    val model: String? = null,
    val systemPrompt: String? = null,
    val keywords: List<String> = emptyList(),
    val patterns: List<String> = emptyList()
)

@Serializable
data class AgentCreateRequest(
    val name: String,
    val description: String,
    val projectId: String? = null,
    val model: String? = null,
    val systemPrompt: String? = null,
    val keywords: List<String> = emptyList(),
    val patterns: List<String> = emptyList()
)

@Serializable
data class RoutingTestRequest(
    val prompt: String,
    val projectId: String? = null
)

@Serializable
data class RoutingResult(
    val agentId: String,
    val agentName: String,
    val method: String,
    val confidence: Double,
    val reasoning: String? = null
)

@Serializable
data class UserContext(
    val userId: String,
    val summary: String? = null,
    val rules: List<String> = emptyList(),
    val lastUpdated: String? = null
)

@Serializable
data class AddRuleRequest(
    val rule: String
)

@Serializable
data class SystemMetrics(
    val totalExecutions: Long,
    val successfulExecutions: Long,
    val failedExecutions: Long,
    val averageDurationMs: Double,
    val p50DurationMs: Long,
    val p90DurationMs: Long,
    val p95DurationMs: Long,
    val p99DurationMs: Long,
    val totalTokensUsed: Long,
    val totalCost: Double,
    val cacheHitRate: Double,
    val activeAgents: Int
)

@Serializable
data class UserMetrics(
    val userId: String,
    val totalExecutions: Long,
    val successRate: Double,
    val averageDurationMs: Double,
    val totalTokensUsed: Long,
    val totalCost: Double,
    val favoriteAgents: List<String>
)

@Serializable
data class AgentMetrics(
    val agentId: String,
    val totalExecutions: Long,
    val successRate: Double,
    val averageDurationMs: Double,
    val totalTokensUsed: Long
)

@Serializable
data class HealthStatus(
    val status: String,
    val timestamp: String
)

@Serializable
data class DetailedHealthStatus(
    val status: String,
    val timestamp: String,
    val components: Map<String, ComponentHealth>
)

@Serializable
data class ComponentHealth(
    val status: String,
    val message: String? = null
)

@Serializable
data class SessionInfo(
    val sessionId: String,
    val userId: String? = null,
    val createdAt: String,
    val lastUsedAt: String,
    val messageCount: Int
)

@Serializable
data class CacheStats(
    val l1HitCount: Long,
    val l1MissCount: Long,
    val l1HitRate: Double,
    val l1Size: Long,
    val classificationCacheHitRate: Double
)

@Serializable
private class EmptyRequest
