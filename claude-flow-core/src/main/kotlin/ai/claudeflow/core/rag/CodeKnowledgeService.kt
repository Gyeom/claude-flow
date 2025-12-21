package ai.claudeflow.core.rag

import mu.KotlinLogging
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * 코드베이스 지식 검색 서비스
 *
 * GitLab/GitHub 프로젝트 코드를 인덱싱하고
 * MR 리뷰 시 관련 코드를 자동으로 참조
 */
class CodeKnowledgeService(
    private val embeddingService: EmbeddingService,
    private val codeChunker: CodeChunker = CodeChunker(),
    private val qdrantUrl: String = "http://localhost:6333",
    private val collectionName: String = "claude-flow-knowledge"
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
        val SUPPORTED_EXTENSIONS = setOf(
            "kt", "kts", "java", "ts", "tsx", "js", "jsx", "mjs",
            "py", "go", "rs", "rb", "php", "cs", "cpp", "c", "h",
            "yaml", "yml", "json", "toml", "xml", "properties",
            "md", "txt", "sql", "sh", "bash"
        )
        val IGNORED_DIRS = setOf(
            "node_modules", ".git", "build", "dist", "target",
            ".gradle", ".idea", ".vscode", "__pycache__", ".pytest_cache",
            "vendor", "venv", ".env"
        )
    }

    /**
     * 컬렉션 초기화
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
                logger.info { "Knowledge collection $collectionName already exists" }
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
            logger.info { "Creating knowledge collection $collectionName with dimension $vectorDimension" }
            val requestBody = mapOf(
                "vectors" to mapOf(
                    "size" to vectorDimension,
                    "distance" to "Cosine"
                ),
                "optimizers_config" to mapOf(
                    "default_segment_number" to 4
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
                logger.info { "Created knowledge collection $collectionName" }
                createIndexes()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create knowledge collection" }
            false
        }
    }

    private fun createIndexes() {
        listOf(
            "project_id" to "keyword",
            "file_path" to "text",
            "language" to "keyword",
            "chunk_type" to "keyword"
        ).forEach { (field, schema) ->
            try {
                val requestBody = mapOf(
                    "field_name" to field,
                    "field_schema" to schema
                )

                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$qdrantUrl/collections/$collectionName/index"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .timeout(Duration.ofSeconds(10))
                    .build()

                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                logger.warn { "Failed to create index for $field" }
            }
        }
    }

    /**
     * 로컬 디렉토리 인덱싱
     */
    fun indexLocalDirectory(
        projectId: String,
        directory: String,
        filePatterns: List<String> = listOf("**/*")
    ): IndexingResult {
        val startTime = System.currentTimeMillis()
        val dir = File(directory)

        if (!dir.exists() || !dir.isDirectory) {
            return IndexingResult(projectId, 0, 0, 0, "디렉토리가 존재하지 않습니다: $directory")
        }

        var filesIndexed = 0
        var chunksCreated = 0
        var pointId = generateBasePointId(projectId)

        val files = dir.walkTopDown()
            .filter { file ->
                file.isFile &&
                SUPPORTED_EXTENSIONS.contains(file.extension.lowercase()) &&
                !file.path.split(File.separator).any { it in IGNORED_DIRS }
            }
            .toList()

        logger.info { "Found ${files.size} files to index in $directory" }

        for (file in files) {
            try {
                val content = file.readText()
                if (content.isBlank()) continue

                val relativePath = file.relativeTo(dir).path
                val chunks = codeChunker.chunkFile(content, relativePath)

                for (chunk in chunks) {
                    if (indexChunk(projectId, chunk, pointId++)) {
                        chunksCreated++
                    }
                }
                filesIndexed++

                if (filesIndexed % 50 == 0) {
                    logger.info { "Indexed $filesIndexed files, $chunksCreated chunks..." }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to index file ${file.path}: ${e.message}" }
            }
        }

        val durationMs = System.currentTimeMillis() - startTime
        logger.info { "Indexing complete: $filesIndexed files, $chunksCreated chunks in ${durationMs}ms" }

        return IndexingResult(
            projectId = projectId,
            filesIndexed = filesIndexed,
            chunksCreated = chunksCreated,
            durationMs = durationMs
        )
    }

    /**
     * 단일 청크 인덱싱
     */
    private fun indexChunk(projectId: String, chunk: CodeChunk, pointId: Long): Boolean {
        return try {
            // 임베딩용 텍스트 생성 (파일 경로 + 내용)
            val textToEmbed = """
                File: ${chunk.filePath}
                Type: ${chunk.chunkType}
                ${chunk.content}
            """.trimIndent()

            val embedding = embeddingService.embed(textToEmbed) ?: return false

            val payload = mapOf(
                "project_id" to projectId,
                "file_path" to chunk.filePath,
                "start_line" to chunk.startLine,
                "end_line" to chunk.endLine,
                "language" to chunk.language,
                "chunk_type" to chunk.chunkType,
                "content_preview" to chunk.contentPreview,
                "indexed_at" to Instant.now().toString()
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
            response.statusCode() in 200..299
        } catch (e: Exception) {
            logger.debug { "Failed to index chunk: ${e.message}" }
            false
        }
    }

    /**
     * 관련 코드 검색
     */
    fun findRelevantCode(
        query: String,
        projectId: String? = null,
        filePatterns: List<String> = emptyList(),
        topK: Int = 5,
        minScore: Float = 0.6f
    ): List<CodeChunk> {
        return try {
            val queryEmbedding = embeddingService.embed(query) ?: return emptyList()

            val filter = buildFilter(projectId, filePatterns)

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
                parseCodeSearchResults(response.body())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to search relevant code" }
            emptyList()
        }
    }

    /**
     * 파일 변경사항과 관련된 리뷰 가이드라인 검색
     */
    fun findReviewGuidelines(
        diffContent: String,
        projectId: String
    ): List<ReviewGuideline> {
        // Diff에서 키워드 추출
        val keywords = extractDiffKeywords(diffContent)

        // 관련 코드 검색
        val relatedCode = findRelevantCode(
            query = keywords.joinToString(" "),
            projectId = projectId,
            topK = 10
        )

        // 가이드라인 생성 (휴리스틱 기반)
        return generateGuidelines(diffContent, relatedCode)
    }

    /**
     * 프로젝트 통계
     */
    fun getProjectStats(projectId: String): KnowledgeStats {
        return try {
            val requestBody = mapOf(
                "filter" to mapOf(
                    "must" to listOf(
                        mapOf("key" to "project_id", "match" to mapOf("value" to projectId))
                    )
                ),
                "limit" to 0,
                "exact" to true
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$qdrantUrl/collections/$collectionName/points/count"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val result: Map<String, Any> = objectMapper.readValue(response.body())
                @Suppress("UNCHECKED_CAST")
                val count = (result["result"] as? Map<String, Any>)?.get("count") as? Number
                KnowledgeStats(
                    projectId = projectId,
                    totalChunks = count?.toLong() ?: 0,
                    lastUpdated = Instant.now().toString()
                )
            } else {
                KnowledgeStats(projectId, 0, null)
            }
        } catch (e: Exception) {
            KnowledgeStats(projectId, 0, null)
        }
    }

    /**
     * 원격 파일 내용 직접 인덱싱 (GitLab/GitHub API용)
     *
     * @param projectId 프로젝트 ID
     * @param filePath 파일 경로
     * @param content 파일 내용
     * @return 생성된 청크 수
     */
    fun indexRemoteFile(projectId: String, filePath: String, content: String): Int {
        if (content.isBlank()) return 0

        try {
            val chunks = codeChunker.chunkFile(content, filePath)
            var indexed = 0
            var pointId = generateBasePointId("$projectId:$filePath")

            for (chunk in chunks) {
                if (indexChunk(projectId, chunk, pointId++)) {
                    indexed++
                }
            }

            logger.debug { "Indexed remote file $filePath: $indexed chunks" }
            return indexed
        } catch (e: Exception) {
            logger.warn { "Failed to index remote file $filePath: ${e.message}" }
            return 0
        }
    }

    /**
     * 배치 인덱싱 (여러 파일 한번에)
     */
    fun indexRemoteFiles(projectId: String, files: Map<String, String>): Int {
        var totalChunks = 0
        for ((path, content) in files) {
            totalChunks += indexRemoteFile(projectId, path, content)
        }
        logger.info { "Batch indexed ${files.size} files, $totalChunks total chunks for $projectId" }
        return totalChunks
    }

    /**
     * 프로젝트 데이터 삭제
     */
    fun deleteProject(projectId: String): Boolean {
        return try {
            val requestBody = mapOf(
                "filter" to mapOf(
                    "must" to listOf(
                        mapOf("key" to "project_id", "match" to mapOf("value" to projectId))
                    )
                )
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$qdrantUrl/collections/$collectionName/points/delete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .timeout(Duration.ofSeconds(30))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() in 200..299
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete project $projectId" }
            false
        }
    }

    private fun buildFilter(projectId: String?, filePatterns: List<String>): Map<String, Any>? {
        val conditions = mutableListOf<Map<String, Any>>()

        projectId?.let {
            conditions.add(mapOf("key" to "project_id", "match" to mapOf("value" to it)))
        }

        // 파일 패턴 필터 (간단한 확장자 매칭)
        if (filePatterns.isNotEmpty()) {
            val extensions = filePatterns
                .filter { it.startsWith("*.") }
                .map { it.removePrefix("*.") }

            if (extensions.isNotEmpty()) {
                conditions.add(mapOf(
                    "should" to extensions.map { ext ->
                        mapOf("key" to "language", "match" to mapOf("value" to ext))
                    }
                ))
            }
        }

        return if (conditions.isNotEmpty()) {
            mapOf("must" to conditions)
        } else null
    }

    private fun parseCodeSearchResults(responseBody: String): List<CodeChunk> {
        val result: Map<String, Any> = objectMapper.readValue(responseBody)
        @Suppress("UNCHECKED_CAST")
        val hits = result["result"] as? List<Map<String, Any>> ?: return emptyList()

        return hits.mapNotNull { hit ->
            @Suppress("UNCHECKED_CAST")
            val payload = hit["payload"] as? Map<String, Any>
            val score = (hit["score"] as? Number)?.toFloat() ?: return@mapNotNull null

            CodeChunk(
                filePath = payload?.get("file_path") as? String ?: "",
                content = "", // 전체 내용은 별도 조회 필요
                startLine = (payload?.get("start_line") as? Number)?.toInt() ?: 0,
                endLine = (payload?.get("end_line") as? Number)?.toInt() ?: 0,
                language = payload?.get("language") as? String ?: "",
                chunkType = payload?.get("chunk_type") as? String ?: "",
                contentPreview = payload?.get("content_preview") as? String ?: "",
                score = score
            )
        }
    }

    private fun extractDiffKeywords(diff: String): List<String> {
        // Diff에서 추가/삭제된 라인의 키워드 추출
        return diff.lines()
            .filter { it.startsWith("+") || it.startsWith("-") }
            .filter { !it.startsWith("+++") && !it.startsWith("---") }
            .flatMap { line ->
                line.drop(1)
                    .split(Regex("[\\s,.!?;:()\\[\\]{}\"'=><]+"))
                    .filter { it.length >= 3 }
            }
            .groupingBy { it.lowercase() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key }
    }

    private fun generateGuidelines(diff: String, relatedCode: List<CodeChunk>): List<ReviewGuideline> {
        val guidelines = mutableListOf<ReviewGuideline>()

        // 보안 관련 패턴 체크
        val securityPatterns = listOf(
            "password" to "하드코딩된 비밀번호 주의",
            "secret" to "비밀 정보 노출 주의",
            "token" to "토큰 노출 주의",
            "api.key" to "API 키 노출 주의",
            "eval(" to "eval 사용 지양",
            "exec(" to "exec 사용 주의"
        )

        for ((pattern, message) in securityPatterns) {
            if (diff.lowercase().contains(pattern)) {
                guidelines.add(ReviewGuideline(
                    rule = message,
                    category = "security",
                    severity = "error",
                    applicablePatterns = listOf(pattern)
                ))
            }
        }

        // 성능 관련 패턴
        if (diff.contains("for (") || diff.contains("while (")) {
            guidelines.add(ReviewGuideline(
                rule = "루프 내 불필요한 연산 확인",
                category = "performance",
                severity = "warning",
                applicablePatterns = listOf("loop")
            ))
        }

        // 코드 스타일
        if (diff.contains("TODO") || diff.contains("FIXME")) {
            guidelines.add(ReviewGuideline(
                rule = "TODO/FIXME 주석 확인",
                category = "style",
                severity = "info",
                applicablePatterns = listOf("TODO", "FIXME")
            ))
        }

        return guidelines
    }

    private fun generateBasePointId(projectId: String): Long {
        // 프로젝트별 고유 시작 ID
        return (projectId.hashCode().toLong() and 0x7FFFFFFF) * 1_000_000
    }
}

/**
 * 인덱싱 결과
 */
data class IndexingResult(
    val projectId: String,
    val filesIndexed: Int,
    val chunksCreated: Int,
    val durationMs: Long,
    val error: String? = null
)

/**
 * 리뷰 가이드라인
 */
data class ReviewGuideline(
    val rule: String,
    val category: String,   // security, performance, style, etc.
    val severity: String,   // error, warning, info
    val applicablePatterns: List<String>
)

/**
 * 지식 베이스 통계
 */
data class KnowledgeStats(
    val projectId: String,
    val totalChunks: Long,
    val lastUpdated: String?
)
