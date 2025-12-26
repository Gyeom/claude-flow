package ai.claudeflow.core.routing

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.model.AgentMatch
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * 시맨틱 라우터
 *
 * 벡터 유사도 기반으로 에이전트 라우팅
 * Qdrant 또는 다른 벡터 DB를 사용하여 구현
 */
class SemanticRouter(
    private val embeddingServiceUrl: String = "http://localhost:11434",  // Ollama default
    private val vectorDbUrl: String = "http://localhost:6333",  // Qdrant default
    private val collectionName: String = "claude-flow-agents",
    private val minScore: Double = 0.7
) {
    private val httpClient = HttpClient.newHttpClient()
    private val objectMapper = jacksonObjectMapper()

    companion object {
        /**
         * 임베딩 캐시 - 동일한 텍스트의 임베딩 결과를 30분간 캐싱
         * 성능 최적화: Ollama API 호출 회피 (가장 비싼 연산)
         */
        private val embeddingCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build<String, List<Double>>()

        /**
         * 벡터 검색 캐시 - 검색 결과를 10분간 캐싱
         * 캐시 키: 임베딩 해시 + topK
         */
        private val searchCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build<String, List<SearchResult>>()

        // 캐시 통계
        private val embeddingCacheHits = AtomicLong(0)
        private val embeddingCacheMisses = AtomicLong(0)
        private val searchCacheHits = AtomicLong(0)
        private val searchCacheMisses = AtomicLong(0)

        /**
         * 시맨틱 라우터 캐시 통계 반환
         */
        fun getCacheStats(): Map<String, Any> {
            val embeddingHits = embeddingCacheHits.get()
            val embeddingMisses = embeddingCacheMisses.get()
            val embeddingTotal = embeddingHits + embeddingMisses
            val embeddingHitRate = if (embeddingTotal > 0)
                "%.2f%%".format(embeddingHits.toDouble() / embeddingTotal * 100)
            else "N/A"

            val searchHits = searchCacheHits.get()
            val searchMisses = searchCacheMisses.get()
            val searchTotal = searchHits + searchMisses
            val searchHitRate = if (searchTotal > 0)
                "%.2f%%".format(searchHits.toDouble() / searchTotal * 100)
            else "N/A"

            return mapOf(
                "embedding" to mapOf(
                    "cacheHits" to embeddingHits,
                    "cacheMisses" to embeddingMisses,
                    "cacheSize" to embeddingCache.estimatedSize(),
                    "hitRate" to embeddingHitRate
                ),
                "search" to mapOf(
                    "cacheHits" to searchHits,
                    "cacheMisses" to searchMisses,
                    "cacheSize" to searchCache.estimatedSize(),
                    "hitRate" to searchHitRate
                )
            )
        }

        /**
         * 캐시 초기화
         */
        fun clearCache() {
            embeddingCache.invalidateAll()
            searchCache.invalidateAll()
            embeddingCacheHits.set(0)
            embeddingCacheMisses.set(0)
            searchCacheHits.set(0)
            searchCacheMisses.set(0)
            logger.info { "Semantic router caches cleared" }
        }
    }

    /**
     * 시맨틱 검색으로 에이전트 분류
     *
     * Claude Flow 스타일 우선순위 보너스 적용:
     * adjusted_score = score * (1.0 + priority/1000.0)
     */
    fun classify(message: String, agents: List<Agent>): AgentMatch? {
        return try {
            // 1. 메시지 임베딩 생성
            val embedding = getEmbedding(message) ?: return null

            // 2. 벡터 DB에서 유사한 에이전트 검색 (상위 N개)
            val searchResults = searchSimilarMultiple(embedding, topK = 5)
            if (searchResults.isEmpty()) return null

            // 3. 우선순위 보너스를 적용하여 최적 에이전트 선택
            val scoredMatches = searchResults.mapNotNull { result ->
                val agent = agents.find { it.id == result.agentId }
                if (agent != null && result.score >= minScore) {
                    // Claude Flow 스타일: adjusted_score = score * (1.0 + priority/1000.0)
                    val adjustedScore = result.score * (1.0 + agent.priority / 1000.0)
                    Triple(agent, adjustedScore, result)
                } else null
            }.sortedByDescending { it.second }

            val bestMatch = scoredMatches.firstOrNull()
            if (bestMatch != null) {
                val (agent, adjustedScore, result) = bestMatch
                logger.info {
                    "Semantic match: ${agent.id} (raw: ${result.score}, adjusted: $adjustedScore, priority: ${agent.priority})"
                }
                return AgentMatch(
                    agent = agent,
                    confidence = adjustedScore.coerceAtMost(1.0),  // 최대 1.0으로 제한
                    matchedKeyword = "semantic:${result.example.take(50)}"
                )
            }
            null
        } catch (e: Exception) {
            logger.warn(e) { "Semantic routing failed, falling back" }
            null
        }
    }

    /**
     * 에이전트 예제를 벡터 DB에 인덱싱
     */
    fun indexAgentExamples(agents: List<Agent>, examples: Map<String, List<String>>) {
        try {
            // 컬렉션 생성 (없으면)
            createCollection()

            // 각 에이전트의 예제 인덱싱
            var pointId = 1
            for (agent in agents) {
                val agentExamples = examples[agent.id] ?: continue
                for (example in agentExamples) {
                    val embedding = getEmbedding(example) ?: continue
                    upsertPoint(pointId++, embedding, agent.id, example)
                }
            }
            logger.info { "Indexed ${pointId - 1} examples for ${agents.size} agents" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to index agent examples" }
        }
    }

    /**
     * 임베딩 생성 (캐시 적용)
     * 성능 최적화: 동일한 텍스트는 30분간 캐싱
     */
    private fun getEmbedding(text: String): List<Double>? {
        val cacheKey = text.trim().lowercase()

        // 캐시 확인
        val cached = embeddingCache.getIfPresent(cacheKey)
        if (cached != null) {
            embeddingCacheHits.incrementAndGet()
            logger.debug { "Embedding cache hit: ${cacheKey.take(50)}..." }
            return cached
        }
        embeddingCacheMisses.incrementAndGet()

        return try {
            val requestBody = mapOf(
                "model" to "nomic-embed-text",
                "prompt" to text
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$embeddingServiceUrl/api/embeddings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val result: Map<String, Any> = objectMapper.readValue(response.body())
                @Suppress("UNCHECKED_CAST")
                val embedding = result["embedding"] as? List<Double>
                // 캐시에 저장
                if (embedding != null) {
                    embeddingCache.put(cacheKey, embedding)
                    logger.debug { "Embedding cached: ${cacheKey.take(50)}..." }
                }
                embedding
            } else {
                logger.warn { "Embedding request failed: ${response.statusCode()}" }
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get embedding" }
            null
        }
    }

    /**
     * 단일 검색 결과 반환 (하위 호환)
     */
    private fun searchSimilar(embedding: List<Double>): SearchResult? {
        return searchSimilarMultiple(embedding, 1).firstOrNull()
    }

    /**
     * 상위 N개 검색 결과 반환 (캐시 적용)
     * 성능 최적화: 동일한 임베딩의 검색 결과를 10분간 캐싱
     */
    private fun searchSimilarMultiple(embedding: List<Double>, topK: Int = 5): List<SearchResult> {
        // 캐시 키 생성 (임베딩의 해시 + topK)
        val cacheKey = "${embedding.hashCode()}_$topK"

        // 캐시 확인
        val cached = searchCache.getIfPresent(cacheKey)
        if (cached != null) {
            searchCacheHits.incrementAndGet()
            logger.debug { "Search cache hit" }
            return cached
        }
        searchCacheMisses.incrementAndGet()

        return try {
            val requestBody = mapOf(
                "vector" to embedding,
                "top" to topK,
                "with_payload" to true
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$vectorDbUrl/collections/$collectionName/points/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val result: Map<String, Any> = objectMapper.readValue(response.body())
                @Suppress("UNCHECKED_CAST")
                val hits = result["result"] as? List<Map<String, Any>> ?: emptyList()
                val searchResults = hits.mapNotNull { hit ->
                    val payload = hit["payload"] as? Map<String, Any>
                    val agentId = payload?.get("agent_id") as? String
                    val example = payload?.get("example") as? String
                    val score = (hit["score"] as? Number)?.toDouble()
                    if (agentId != null && example != null && score != null) {
                        SearchResult(agentId, example, score)
                    } else null
                }
                // 캐시에 저장
                if (searchResults.isNotEmpty()) {
                    searchCache.put(cacheKey, searchResults)
                    logger.debug { "Search results cached: ${searchResults.size} results" }
                }
                searchResults
            } else emptyList()
        } catch (e: Exception) {
            logger.warn(e) { "Vector search failed" }
            emptyList()
        }
    }

    private fun createCollection() {
        try {
            val requestBody = mapOf(
                "vectors" to mapOf(
                    "size" to 768,  // nomic-embed-text dimension
                    "distance" to "Cosine"
                )
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$vectorDbUrl/collections/$collectionName"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build()

            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            // Collection might already exist
            logger.debug { "Collection creation: ${e.message}" }
        }
    }

    private fun upsertPoint(id: Int, embedding: List<Double>, agentId: String, example: String) {
        val requestBody = mapOf(
            "points" to listOf(
                mapOf(
                    "id" to id,
                    "vector" to embedding,
                    "payload" to mapOf(
                        "agent_id" to agentId,
                        "example" to example
                    )
                )
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$vectorDbUrl/collections/$collectionName/points"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build()

        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private data class SearchResult(
        val agentId: String,
        val example: String,
        val score: Double
    )
}
