package ai.claudeflow.core.rag

import ai.claudeflow.core.storage.ExecutionRecord
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 대화 벡터화 서비스
 *
 * 실행 기록을 Qdrant 벡터 DB에 인덱싱하고 유사 대화 검색
 */
class ConversationVectorService(
    private val embeddingService: EmbeddingService,
    private val qdrantUrl: String = "http://localhost:6333",
    private val collectionName: String = "claude-flow-conversations"
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val objectMapper = jacksonObjectMapper()

    /**
     * 임베딩 서비스에서 동적으로 차원 가져옴
     */
    val vectorDimension: Int
        get() = embeddingService.dimension

    companion object {
        const val DEFAULT_MIN_SCORE = 0.6f
        const val DEFAULT_TOP_K = 5
    }

    /**
     * 컬렉션 초기화 (없으면 생성)
     */
    fun initCollection(): Boolean {
        return try {
            // 컬렉션 존재 여부 확인
            val checkRequest = HttpRequest.newBuilder()
                .uri(URI.create("$qdrantUrl/collections/$collectionName"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()

            val checkResponse = httpClient.send(checkRequest, HttpResponse.BodyHandlers.ofString())

            if (checkResponse.statusCode() == 200) {
                logger.info { "Collection $collectionName already exists" }
                true
            } else {
                createCollection()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize collection" }
            false
        }
    }

    private fun createCollection(): Boolean {
        return try {
            logger.info { "Creating collection $collectionName with dimension $vectorDimension" }
            val requestBody = mapOf(
                "vectors" to mapOf(
                    "size" to vectorDimension,
                    "distance" to "Cosine"
                ),
                "optimizers_config" to mapOf(
                    "default_segment_number" to 2
                )
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$qdrantUrl/collections/$collectionName"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() in 200..299) {
                logger.info { "Created collection $collectionName" }
                createIndexes()
                true
            } else {
                logger.warn { "Failed to create collection: ${response.body()}" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create collection" }
            false
        }
    }

    private fun createIndexes() {
        // user_id 인덱스
        createFieldIndex("user_id", "keyword")
        // agent_id 인덱스
        createFieldIndex("agent_id", "keyword")
        // created_at 인덱스
        createFieldIndex("created_at", "datetime")
    }

    private fun createFieldIndex(fieldName: String, fieldSchema: String) {
        try {
            val requestBody = mapOf(
                "field_name" to fieldName,
                "field_schema" to fieldSchema
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$qdrantUrl/collections/$collectionName/index"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .timeout(Duration.ofSeconds(10))
                .build()

            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            logger.debug { "Created index for field: $fieldName" }
        } catch (e: Exception) {
            logger.warn { "Failed to create index for $fieldName: ${e.message}" }
        }
    }

    /**
     * 실행 기록을 벡터화하여 저장
     */
    fun indexExecution(execution: ExecutionRecord): Boolean {
        return try {
            // 프롬프트 + 응답 결합하여 임베딩 생성
            val textToEmbed = buildEmbeddingText(execution)
            val embedding = embeddingService.embed(textToEmbed)
                ?: return false.also { logger.warn { "Failed to get embedding for execution ${execution.id}" } }

            // Qdrant에 저장
            val pointId = generatePointId(execution.id)
            val payload = mapOf(
                "execution_id" to execution.id,
                "user_id" to (execution.userId ?: "anonymous"),
                "agent_id" to (execution.agentId ?: "unknown"),
                "prompt_summary" to execution.prompt.take(500),
                "result_summary" to (execution.result?.take(500) ?: ""),
                "feedback_score" to 0.0,  // 나중에 업데이트
                "created_at" to execution.createdAt.toString(),
                "channel" to execution.channel,
                "project_id" to execution.projectId
            )

            val requestBody = mapOf(
                "points" to listOf(
                    mapOf(
                        "id" to pointId,
                        "vector" to embedding.toList(),
                        "payload" to payload
                    )
                )
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$qdrantUrl/collections/$collectionName/points"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() in 200..299) {
                logger.debug { "Indexed execution ${execution.id}" }
                true
            } else {
                logger.warn { "Failed to index execution: ${response.body()}" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to index execution ${execution.id}" }
            false
        }
    }

    /**
     * 배치 인덱싱
     */
    fun indexExecutions(executions: List<ExecutionRecord>): Int {
        var successCount = 0
        for (execution in executions) {
            if (indexExecution(execution)) {
                successCount++
            }
        }
        logger.info { "Indexed $successCount/${executions.size} executions" }
        return successCount
    }

    /**
     * 유사 대화 검색
     *
     * @param query 검색 쿼리
     * @param userId 사용자 ID (선택적 필터)
     * @param topK 반환할 최대 개수
     * @param minScore 최소 유사도 점수 (0.0 ~ 1.0)
     */
    fun findSimilarConversations(
        query: String,
        userId: String? = null,
        topK: Int = DEFAULT_TOP_K,
        minScore: Float = DEFAULT_MIN_SCORE
    ): List<SimilarConversation> {
        return try {
            val queryEmbedding = embeddingService.embed(query)
                ?: return emptyList()

            val filter = buildFilter(userId)

            val requestBody = buildMap {
                put("vector", queryEmbedding.toList())
                put("limit", topK)
                put("score_threshold", minScore)
                put("with_payload", true)
                filter?.let { put("filter", it) }
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$qdrantUrl/collections/$collectionName/points/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                parseSearchResults(response.body())
            } else {
                logger.warn { "Search failed: ${response.body()}" }
                emptyList()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to search similar conversations" }
            emptyList()
        }
    }

    /**
     * 사용자의 최근 대화 패턴 기반 선호 에이전트 검색
     */
    fun findUserPreferredAgents(userId: String, topK: Int = 5): List<AgentPreference> {
        return try {
            val requestBody = mapOf(
                "filter" to mapOf(
                    "must" to listOf(
                        mapOf("key" to "user_id", "match" to mapOf("value" to userId))
                    )
                ),
                "limit" to 100,
                "with_payload" to true
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$qdrantUrl/collections/$collectionName/points/scroll"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val result: Map<String, Any> = objectMapper.readValue(response.body())
                @Suppress("UNCHECKED_CAST")
                val points = result["result"] as? Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val pointsList = points?.get("points") as? List<Map<String, Any>> ?: emptyList()

                // 에이전트별 사용 횟수 집계
                val agentCounts = pointsList
                    .mapNotNull { point ->
                        @Suppress("UNCHECKED_CAST")
                        val payload = point["payload"] as? Map<String, Any>
                        payload?.get("agent_id") as? String
                    }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(topK)

                val total = agentCounts.sumOf { it.value }
                agentCounts.map { (agentId, count) ->
                    AgentPreference(
                        agentId = agentId,
                        usageCount = count,
                        usageRate = count.toDouble() / total
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to find user preferred agents" }
            emptyList()
        }
    }

    /**
     * 피드백 점수 업데이트
     */
    fun updateFeedbackScore(executionId: String, score: Double): Boolean {
        return try {
            val pointId = generatePointId(executionId)

            val requestBody = mapOf(
                "points" to listOf(pointId),
                "payload" to mapOf("feedback_score" to score)
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$qdrantUrl/collections/$collectionName/points/payload"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() in 200..299
        } catch (e: Exception) {
            logger.error(e) { "Failed to update feedback score for $executionId" }
            false
        }
    }

    /**
     * 컬렉션 통계
     */
    fun getStats(): VectorCollectionStats {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$qdrantUrl/collections/$collectionName"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val result: Map<String, Any> = objectMapper.readValue(response.body())
                @Suppress("UNCHECKED_CAST")
                val collectionResult = result["result"] as? Map<String, Any>
                val pointsCount = (collectionResult?.get("points_count") as? Number)?.toLong() ?: 0

                VectorCollectionStats(
                    totalVectors = pointsCount,
                    collectionName = collectionName,
                    lastIndexedAt = Instant.now().toString()
                )
            } else {
                VectorCollectionStats(0, collectionName, null)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get collection stats" }
            VectorCollectionStats(0, collectionName, null)
        }
    }

    /**
     * 서비스 상태 확인
     */
    fun isAvailable(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$qdrantUrl/collections"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200 && embeddingService.isAvailable()
        } catch (e: Exception) {
            false
        }
    }

    private fun buildEmbeddingText(execution: ExecutionRecord): String {
        return buildString {
            append("질문: ${execution.prompt}\n")
            execution.result?.let { result ->
                append("답변: ${result.take(1000)}")
            }
        }
    }

    private fun buildFilter(userId: String?): Map<String, Any>? {
        if (userId == null) return null

        return mapOf(
            "must" to listOf(
                mapOf("key" to "user_id", "match" to mapOf("value" to userId))
            )
        )
    }

    private fun parseSearchResults(responseBody: String): List<SimilarConversation> {
        val result: Map<String, Any> = objectMapper.readValue(responseBody)
        @Suppress("UNCHECKED_CAST")
        val hits = result["result"] as? List<Map<String, Any>> ?: return emptyList()

        return hits.mapNotNull { hit ->
            @Suppress("UNCHECKED_CAST")
            val payload = hit["payload"] as? Map<String, Any>
            val score = (hit["score"] as? Number)?.toFloat() ?: return@mapNotNull null

            SimilarConversation(
                executionId = payload?.get("execution_id") as? String ?: "",
                prompt = payload?.get("prompt_summary") as? String ?: "",
                result = payload?.get("result_summary") as? String ?: "",
                agentId = payload?.get("agent_id") as? String ?: "",
                score = score,
                createdAt = payload?.get("created_at") as? String ?: ""
            )
        }
    }

    private fun generatePointId(executionId: String): Long {
        // 해시 기반 ID 생성 (음수 방지)
        return executionId.hashCode().toLong() and 0x7FFFFFFF
    }
}

/**
 * 유사 대화 검색 결과
 */
data class SimilarConversation(
    val executionId: String,
    val prompt: String,
    val result: String,
    val agentId: String,
    val score: Float,
    val createdAt: String
)

/**
 * 벡터 컬렉션 통계
 */
data class VectorCollectionStats(
    val totalVectors: Long,
    val collectionName: String,
    val lastIndexedAt: String?
)

/**
 * 에이전트 선호도
 */
data class AgentPreference(
    val agentId: String,
    val usageCount: Int,
    val usageRate: Double
)
