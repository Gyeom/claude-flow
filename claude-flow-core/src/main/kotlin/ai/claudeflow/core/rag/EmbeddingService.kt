package ai.claudeflow.core.rag

import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * 임베딩 서비스
 *
 * Ollama nomic-embed-text 모델을 사용하여 텍스트를 768차원 벡터로 변환
 * 한국어/영어 모두 지원
 */
class EmbeddingService(
    private val ollamaUrl: String = "http://localhost:11434",
    private val model: String = "nomic-embed-text",
    private val cache: EmbeddingCache? = null,
    private val timeoutSeconds: Long = 30
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val objectMapper = jacksonObjectMapper()

    companion object {
        const val EMBEDDING_DIMENSION = 768
    }

    /**
     * 단일 텍스트 임베딩
     *
     * @param text 임베딩할 텍스트
     * @return 768차원 벡터
     */
    fun embed(text: String): FloatArray? {
        // 캐시 확인
        cache?.get(text)?.let { cached ->
            logger.debug { "Embedding cache hit for text: ${text.take(50)}..." }
            return cached
        }

        return try {
            val embedding = requestEmbedding(text)
            embedding?.let {
                cache?.put(text, it)
            }
            embedding
        } catch (e: Exception) {
            logger.error(e) { "Failed to get embedding for text: ${text.take(50)}..." }
            null
        }
    }

    /**
     * 배치 임베딩
     *
     * @param texts 임베딩할 텍스트 목록
     * @return 텍스트별 벡터 목록
     */
    fun embedBatch(texts: List<String>): List<FloatArray?> {
        return texts.map { embed(it) }
    }

    /**
     * 두 벡터 간 코사인 유사도 계산
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must have same dimension" }

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        return if (normA > 0 && normB > 0) {
            dotProduct / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
        } else {
            0f
        }
    }

    /**
     * 서비스 상태 확인
     */
    fun isAvailable(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$ollamaUrl/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            logger.warn { "Ollama service not available: ${e.message}" }
            false
        }
    }

    private fun requestEmbedding(text: String): FloatArray? {
        val requestBody = mapOf(
            "model" to model,
            "prompt" to text
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$ollamaUrl/api/embeddings"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            logger.warn { "Embedding request failed with status: ${response.statusCode()}" }
            return null
        }

        val result: Map<String, Any> = objectMapper.readValue(response.body())
        @Suppress("UNCHECKED_CAST")
        val embedding = result["embedding"] as? List<Number>

        return embedding?.map { it.toFloat() }?.toFloatArray()
    }
}
