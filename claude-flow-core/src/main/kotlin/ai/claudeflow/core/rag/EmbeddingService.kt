package ai.claudeflow.core.rag

import com.github.michaelbull.retry.policy.binaryExponentialBackoff
import com.github.michaelbull.retry.policy.plus
import com.github.michaelbull.retry.policy.stopAtAttempts
import com.github.michaelbull.retry.retry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Duration
import kotlin.random.Random

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
 *
 * 배치 처리 권장 설정 (하드웨어별):
 * - RTX 4090: batchSize=64~128
 * - Apple M2 Max: batchSize=32~64
 * - Apple M2 Pro: batchSize=16~32
 * - Apple M1/M2: batchSize=8~16
 * - CPU-only: batchSize=4~8
 *
 * ⚠️ 대용량 청크(1000자+)는 배치 크기를 줄여야 안정적
 *
 * @see <a href="https://docs.ollama.com/capabilities/embeddings">Ollama Embeddings</a>
 */
class EmbeddingService(
    private val ollamaUrl: String = "http://localhost:11434",
    private val model: String = "qwen3-embedding:0.6b",
    private val cache: EmbeddingCache? = null,
    private val timeoutSeconds: Long = 300,  // 대용량 배치용 5분 타임아웃
    private val defaultBatchSize: Int = 16   // 안정성 우선 (64→16, 대용량 청크 대응)
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))   // 연결 타임아웃 증가
        .version(HttpClient.Version.HTTP_1_1)     // HTTP/2 문제 방지
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

        // Retry 설정
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val BACKOFF_BASE_MS = 500L
        private const val BACKOFF_MAX_MS = 10_000L
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
     * 단일 텍스트 임베딩 (suspend 버전)
     *
     * coroutine 컨텍스트에서 사용할 때 권장
     */
    suspend fun embedAsync(text: String): FloatArray? = withContext(Dispatchers.IO) {
        embed(text)
    }

    /**
     * 배치 임베딩 (병렬 처리)
     *
     * 여러 텍스트를 동시에 임베딩하여 처리 속도 향상
     *
     * @param texts 임베딩할 텍스트 목록
     * @param maxConcurrency 최대 동시 요청 수 (기본값: 5, Ollama 부하 고려)
     * @return 텍스트별 벡터 목록
     */
    suspend fun embedBatchParallel(
        texts: List<String>,
        maxConcurrency: Int = 5
    ): List<FloatArray?> = coroutineScope {
        if (texts.isEmpty()) return@coroutineScope emptyList()

        // 동시성 제한을 위해 청크로 분할
        texts.chunked(maxConcurrency).flatMap { chunk ->
            chunk.map { text ->
                async(Dispatchers.IO) {
                    embed(text)
                }
            }.awaitAll()
        }
    }

    /**
     * 네이티브 배치 임베딩 (Ollama /api/embed 사용) - Coroutine 버전
     *
     * Best Practices 적용:
     * 1. Exponential Backoff with Jitter (Thundering Herd 방지)
     * 2. Non-blocking delay (coroutine delay 사용)
     * 3. 하드웨어별 배치 크기 최적화
     * 4. keepAlive로 모델 언로딩 방지
     *
     * @param texts 임베딩할 텍스트 목록
     * @param batchSize 한 번에 처리할 텍스트 수 (기본값: 64, M2 Pro 기준)
     * @param onProgress 진행률 콜백 (완료된 수, 전체 수)
     * @return 텍스트별 벡터 목록
     */
    suspend fun embedBatchNative(
        texts: List<String>,
        batchSize: Int = defaultBatchSize,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<FloatArray?> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<FloatArray?>()
        val batches = texts.chunked(batchSize)
        val totalTexts = texts.size
        var completedTexts = 0

        logger.info { "Starting batch embedding: $totalTexts texts in ${batches.size} batches (batchSize=$batchSize)" }

        batches.forEachIndexed { batchIndex, batch ->
            val batchNum = batchIndex + 1
            val startTime = System.currentTimeMillis()

            try {
                logger.info { "Batch $batchNum/${batches.size}: Processing ${batch.size} texts..." }

                // Retry with exponential backoff + jitter
                val batchEmbeddings = retryWithBackoff("Batch $batchNum") {
                    requestBatchEmbedding(batch)
                }

                results.addAll(batchEmbeddings)
                completedTexts += batch.size

                val elapsed = System.currentTimeMillis() - startTime
                val avgPerText = elapsed / batch.size
                logger.info { "Batch $batchNum/${batches.size}: Completed in ${elapsed}ms (${avgPerText}ms/text)" }

                onProgress?.invoke(completedTexts, totalTexts)

            } catch (e: Exception) {
                logger.warn { "Batch $batchNum failed after retries: ${e.message}, falling back to smaller batches..." }

                // 점진적 폴백: batchSize/2 → batchSize/4 → 개별 처리
                val smallerBatchSize = maxOf(1, batchSize / 2)
                val smallerBatches = batch.chunked(smallerBatchSize)
                logger.info { "Falling back to batch size $smallerBatchSize (${smallerBatches.size} sub-batches)" }

                smallerBatches.forEachIndexed { idx, smallBatch ->
                    try {
                        if (idx > 0) {
                            delay(delayWithJitter(200L))
                        }

                        val smallBatchEmbeddings = retryWithBackoff("SmallBatch ${idx + 1}") {
                            requestBatchEmbedding(smallBatch)
                        }
                        results.addAll(smallBatchEmbeddings)
                        completedTexts += smallBatch.size
                        onProgress?.invoke(completedTexts, totalTexts)

                    } catch (retryError: Exception) {
                        logger.error { "Small batch ${idx + 1} failed: ${retryError.message}, falling back to individual requests" }

                        // 최종 폴백: 개별 처리
                        smallBatch.forEach { text ->
                            delay(delayWithJitter(100L))
                            results.add(embed(text))
                            completedTexts++
                            onProgress?.invoke(completedTexts, totalTexts)
                        }
                    }
                }
            }
        }

        val successCount = results.count { it != null }
        logger.info { "Batch embedding completed: $successCount/$totalTexts successful" }

        results
    }

    /**
     * Exponential Backoff with Jitter를 적용한 재시도
     *
     * AWS Architecture Blog 권장 패턴 적용:
     * - Binary Exponential Backoff: 500ms → 1s → 2s → 4s ...
     * - Jitter: 라이브러리 내장 (Thundering Herd 방지)
     */
    private suspend fun <T> retryWithBackoff(
        operationName: String,
        block: suspend () -> T
    ): T {
        var attempt = 0
        return retry(
            stopAtAttempts<Throwable>(MAX_RETRY_ATTEMPTS) + binaryExponentialBackoff(
                min = BACKOFF_BASE_MS,
                max = BACKOFF_MAX_MS
            )
        ) {
            attempt++
            try {
                block()
            } catch (e: Exception) {
                logger.warn { "$operationName failed (attempt $attempt/$MAX_RETRY_ATTEMPTS): ${e.message}" }
                throw e
            }
        }
    }

    /**
     * Jitter가 적용된 delay 값 계산
     * ±25% 범위의 랜덤 값 추가
     */
    private fun delayWithJitter(baseMs: Long): Long {
        val jitter = (baseMs * 0.25 * (Random.nextDouble() * 2 - 1)).toLong()
        return baseMs + jitter
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

    /**
     * Ollama /api/embed 배치 요청
     *
     * 타임아웃은 배치 크기에 따라 동적 조절:
     * - 기본: timeoutSeconds (5분)
     * - 텍스트당 추가: 10초
     * - 최대: 10분
     */
    private fun requestBatchEmbedding(texts: List<String>): List<FloatArray?> {
        val requestBody = mapOf(
            "model" to model,
            "input" to texts,
            "keep_alive" to "5m",  // 모델 언로딩 방지 (5분)
            "truncate" to true     // 컨텍스트 초과 시 자동 자르기
        )

        // 배치 크기에 비례한 동적 타임아웃 (최소 60초, 최대 10분)
        val dynamicTimeout = minOf(600L, maxOf(60L, timeoutSeconds / 5 + texts.size * 10L))

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$ollamaUrl/api/embed"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .timeout(Duration.ofSeconds(dynamicTimeout))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            val errorBody = response.body().take(200)
            logger.warn { "Batch embedding failed: status=${response.statusCode()}, body=$errorBody" }
            throw RuntimeException("Batch embedding failed: ${response.statusCode()}")
        }

        val result: Map<String, Any> = objectMapper.readValue(response.body())
        @Suppress("UNCHECKED_CAST")
        val embeddings = result["embeddings"] as? List<List<Number>>

        return embeddings?.map { embedding ->
            embedding.map { it.toFloat() }.toFloatArray()
        } ?: texts.map { null }
    }

    private fun requestEmbedding(text: String): FloatArray? {
        val requestBody = mapOf(
            "model" to model,
            "prompt" to text,
            "keep_alive" to "5m"  // 모델 언로딩 방지
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
