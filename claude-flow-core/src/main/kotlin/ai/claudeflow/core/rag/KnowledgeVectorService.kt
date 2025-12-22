package ai.claudeflow.core.rag

import ai.claudeflow.core.model.Project
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 지식 벡터 서비스
 *
 * 프로젝트, 에이전트, 도메인 지식 등을 벡터화하여 저장/검색
 * 기존 대화 기반 인덱싱과 별개로 정적 지식 관리
 */
class KnowledgeVectorService(
    private val embeddingService: EmbeddingService,
    private val qdrantUrl: String = "http://localhost:6333",
    private val collectionName: String = "claude-flow-knowledge"
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val objectMapper = jacksonObjectMapper()

    val vectorDimension: Int
        get() = embeddingService.dimension

    companion object {
        const val DEFAULT_MIN_SCORE = 0.5f
        const val DEFAULT_TOP_K = 5

        // 문서 타입
        const val TYPE_PROJECT = "project"
        const val TYPE_PROJECT_LIST = "project-list"
        const val TYPE_AGENT = "agent"
        const val TYPE_DOMAIN = "domain"
    }

    /**
     * 컬렉션 초기화 (없으면 생성)
     */
    fun initCollection(): Boolean {
        return try {
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
            logger.error(e) { "Failed to initialize knowledge collection" }
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
        createFieldIndex("type", "keyword")
        createFieldIndex("doc_id", "keyword")
        createFieldIndex("updated_at", "datetime")
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
     * 프로젝트 인덱싱
     */
    fun indexProject(project: Project): Boolean {
        return try {
            // 프로젝트 정보를 검색 가능한 텍스트로 변환
            val content = buildProjectContent(project)
            val embedding = embeddingService.embed(content)
                ?: return false.also { logger.warn { "Failed to get embedding for project ${project.id}" } }

            val pointId = generatePointId("${TYPE_PROJECT}:${project.id}")
            val payload = mapOf(
                "type" to TYPE_PROJECT,
                "doc_id" to project.id,
                "name" to project.name,
                "description" to (project.description ?: ""),
                "working_directory" to project.workingDirectory,
                "git_remote" to (project.gitRemote ?: ""),
                "default_branch" to project.defaultBranch,
                "is_default" to project.isDefault,
                "content" to content,
                "updated_at" to Instant.now().toString()
            )

            upsertPoint(pointId, embedding, payload)
        } catch (e: Exception) {
            logger.error(e) { "Failed to index project ${project.id}" }
            false
        }
    }

    /**
     * 전체 프로젝트 목록 인덱싱 (메타 쿼리 대응)
     */
    fun indexProjectList(projects: List<Project>): Boolean {
        return try {
            val content = buildProjectListContent(projects)
            val embedding = embeddingService.embed(content)
                ?: return false.also { logger.warn { "Failed to get embedding for project list" } }

            val pointId = generatePointId(TYPE_PROJECT_LIST)
            val payload = mapOf(
                "type" to TYPE_PROJECT_LIST,
                "doc_id" to "project-list",
                "project_count" to projects.size,
                "project_ids" to projects.map { it.id },
                "project_names" to projects.map { it.name },
                "content" to content,
                "updated_at" to Instant.now().toString()
            )

            upsertPoint(pointId, embedding, payload)
        } catch (e: Exception) {
            logger.error(e) { "Failed to index project list" }
            false
        }
    }

    /**
     * 모든 프로젝트 인덱싱 (일괄)
     */
    fun indexAllProjects(projects: List<Project>): IndexResult {
        var successCount = 0
        var failCount = 0

        // 개별 프로젝트 인덱싱
        for (project in projects) {
            if (indexProject(project)) {
                successCount++
            } else {
                failCount++
            }
        }

        // 프로젝트 목록 인덱싱
        if (indexProjectList(projects)) {
            successCount++
        } else {
            failCount++
        }

        logger.info { "Indexed $successCount/${projects.size + 1} project documents ($failCount failed)" }
        return IndexResult(successCount, failCount)
    }

    /**
     * 프로젝트 삭제
     */
    fun deleteProject(projectId: String): Boolean {
        return try {
            val pointId = generatePointId("${TYPE_PROJECT}:$projectId")
            deletePoint(pointId)
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete project $projectId from index" }
            false
        }
    }

    /**
     * 지식 검색
     *
     * @param query 검색 쿼리
     * @param types 필터할 문서 타입 (null이면 모두)
     * @param topK 반환할 최대 개수
     * @param minScore 최소 유사도 점수
     */
    fun search(
        query: String,
        types: List<String>? = null,
        topK: Int = DEFAULT_TOP_K,
        minScore: Float = DEFAULT_MIN_SCORE
    ): List<KnowledgeResult> {
        return try {
            val queryEmbedding = embeddingService.embed(query)
                ?: return emptyList()

            val filter = buildFilter(types)

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
                logger.warn { "Knowledge search failed: ${response.body()}" }
                emptyList()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to search knowledge" }
            emptyList()
        }
    }

    /**
     * 프로젝트 검색 (프로젝트 타입만 필터)
     */
    fun searchProjects(query: String, topK: Int = 3, minScore: Float = 0.4f): List<KnowledgeResult> {
        return search(query, listOf(TYPE_PROJECT, TYPE_PROJECT_LIST), topK, minScore)
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

    private fun buildProjectContent(project: Project): String {
        return buildString {
            appendLine("프로젝트: ${project.name}")
            appendLine("프로젝트 ID: ${project.id}")
            project.description?.let { appendLine("설명: $it") }
            appendLine("작업 디렉토리: ${project.workingDirectory}")
            project.gitRemote?.let { appendLine("Git 리포지토리: $it") }
            appendLine("기본 브랜치: ${project.defaultBranch}")
            if (project.isDefault) appendLine("기본 프로젝트입니다.")
        }
    }

    private fun buildProjectListContent(projects: List<Project>): String {
        return buildString {
            appendLine("현재 관리 중인 프로젝트 목록 (총 ${projects.size}개):")
            appendLine()
            projects.forEachIndexed { index, project ->
                append("${index + 1}. ${project.name}")
                if (project.id != project.name) append(" (${project.id})")
                if (project.isDefault) append(" [기본]")
                appendLine()
                project.description?.let { appendLine("   - $it") }
            }
            appendLine()
            appendLine("프로젝트 관련 질문: 어떤 프로젝트, 프로젝트 목록, 관리하는 프로젝트, 등록된 프로젝트")
        }
    }

    private fun buildFilter(types: List<String>?): Map<String, Any>? {
        if (types.isNullOrEmpty()) return null

        return mapOf(
            "should" to types.map { type ->
                mapOf("key" to "type", "match" to mapOf("value" to type))
            }
        )
    }

    private fun upsertPoint(pointId: Long, embedding: FloatArray, payload: Map<String, Any>): Boolean {
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

        return if (response.statusCode() in 200..299) {
            logger.debug { "Indexed document with point ID: $pointId" }
            true
        } else {
            logger.warn { "Failed to index document: ${response.body()}" }
            false
        }
    }

    private fun deletePoint(pointId: Long): Boolean {
        val requestBody = mapOf("points" to listOf(pointId))

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$qdrantUrl/collections/$collectionName/points/delete"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() in 200..299
    }

    private fun parseSearchResults(responseBody: String): List<KnowledgeResult> {
        val result: Map<String, Any> = objectMapper.readValue(responseBody)
        @Suppress("UNCHECKED_CAST")
        val hits = result["result"] as? List<Map<String, Any>> ?: return emptyList()

        return hits.mapNotNull { hit ->
            @Suppress("UNCHECKED_CAST")
            val payload = hit["payload"] as? Map<String, Any>
            val score = (hit["score"] as? Number)?.toFloat() ?: return@mapNotNull null

            KnowledgeResult(
                type = payload?.get("type") as? String ?: "",
                docId = payload?.get("doc_id") as? String ?: "",
                content = payload?.get("content") as? String ?: "",
                metadata = payload?.filterKeys { it !in listOf("type", "doc_id", "content") } ?: emptyMap(),
                score = score
            )
        }
    }

    private fun generatePointId(key: String): Long {
        return key.hashCode().toLong() and 0x7FFFFFFF
    }
}

/**
 * 지식 검색 결과
 */
data class KnowledgeResult(
    val type: String,
    val docId: String,
    val content: String,
    val metadata: Map<String, Any>,
    val score: Float
)

/**
 * 인덱싱 결과
 */
data class IndexResult(
    val successCount: Int,
    val failCount: Int
)
