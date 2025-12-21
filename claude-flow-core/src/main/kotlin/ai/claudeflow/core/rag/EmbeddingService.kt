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
 * Ollama 기반 텍스트 임베딩 생성
 *
 * 지원 모델:
 * - qwen3-embedding:0.6b (1024차원, 추천) - MTEB Multilingual 1위, Code 1위
 * - nomic-embed-text (768차원) - 경량, 빠름
 * - bge-m3 (1024차원) - 다국어 우수
 *
 * 100+ 언어 지원 (한국어 포함)
 */
class EmbeddingService(
    private val ollamaUrl: String = "http://localhost:11434",
    private val model: String = "qwen3-embedding:0.6b",
    private val cache: EmbeddingCache? = null,
    private val timeoutSeconds: Long = 60
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val objectMapper = jacksonObjectMapper()

    companion object {
        // 모델별 차원
        val MODEL_DIMENSIONS = mapOf(
            "qwen3-embedding:0.6b" to 1024,
            "qwen3-embedding:8b" to 1024,
            "nomic-embed-text" to 768,
            "bge-m3" to 1024,
            "mxbai-embed-large" to 1024
        )

        // 기본 차원 (qwen3-embedding 기준)
        const val DEFAULT_DIMENSION = 1024
    }

    /**
     * 현재 모델의 임베딩 차원
     */
    val dimension: Int
        get() = MODEL_DIMENSIONS[model] ?: DEFAULT_DIMENSION

    /**
     * 단일 텍스트 임베딩
     *
     * @param text 임베딩할 텍스트
     * @return 벡터 (모델에 따라 768~1024차원)
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
