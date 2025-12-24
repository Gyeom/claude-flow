package ai.claudeflow.core.knowledge

import ai.claudeflow.core.rag.EmbeddingService
import ai.claudeflow.core.rag.KnowledgeVectorService
import ai.claudeflow.core.rag.KnowledgeVectorService.Companion.SOURCE_USER
import ai.claudeflow.core.rag.KnowledgeVectorService.Companion.TYPE_DOCUMENT
import ai.claudeflow.core.rag.KnowledgeVectorService.Companion.TYPE_URL
import ai.claudeflow.core.rag.KnowledgeVectorService.Companion.TYPE_IMAGE
import ai.claudeflow.core.storage.repository.KnowledgeRepository
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.jsoup.Jsoup
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Knowledge Base 서비스
 *
 * 문서 업로드, URL 수집, 이미지 분석, 벡터화 등을 관리합니다.
 */
class KnowledgeService(
    private val knowledgeRepository: KnowledgeRepository,
    private val knowledgeVectorService: KnowledgeVectorService?,
    private val imageAnalysisService: ImageAnalysisService?,
    private val embeddingService: EmbeddingService?,
    private val figmaAccessToken: String? = null
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        // 청킹 설정
        private const val CHUNK_SIZE = 1000
        private const val CHUNK_OVERLAP = 200

        // Figma API
        private const val FIGMA_API_BASE = "https://api.figma.com/v1"
        private val FIGMA_URL_PATTERN = Regex("""figma\.com/(file|design)/([a-zA-Z0-9]+)""")

        // 지원 파일 타입
        val TEXT_TYPES = setOf("text/plain", "text/markdown", "text/html", "text/csv")
        val DOC_TYPES = setOf("application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        val EXCEL_TYPES = setOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",  // .xlsx
            "application/vnd.ms-excel"  // .xls
        )
    }

    /**
     * 파일 업로드 처리
     */
    suspend fun uploadFile(
        file: File,
        request: DocumentUploadRequest
    ): KnowledgeDocument {
        val mimeType = detectMimeType(file)
        val isImage = mimeType.startsWith("image/")

        val docId = UUID.randomUUID().toString()
        val title = request.title ?: file.nameWithoutExtension

        logger.info { "Uploading file: ${file.name} (type: $mimeType, isImage: $isImage)" }

        // 문서 생성
        val document = KnowledgeDocument(
            id = docId,
            title = title,
            content = "",  // 나중에 채워짐
            source = if (isImage) SourceType.IMAGE else SourceType.UPLOAD,
            sourceFile = file.absolutePath,
            mimeType = mimeType,
            status = IndexStatus.PROCESSING,
            projectId = request.projectId,
            metadata = request.metadata
        )

        knowledgeRepository.save(document)

        // 비동기로 처리
        scope.launch {
            try {
                val content = if (isImage) {
                    processImage(file)
                } else {
                    extractTextContent(file, mimeType)
                }

                val updatedDoc = document.copy(
                    content = content,
                    updatedAt = Instant.now()
                )
                knowledgeRepository.save(updatedDoc)

                // 벡터화
                indexDocument(updatedDoc)

            } catch (e: Exception) {
                logger.error(e) { "Failed to process file: ${file.name}" }
                knowledgeRepository.updateStatus(docId, IndexStatus.ERROR, e.message)
            }
        }

        return document
    }

    /**
     * URL 수집 처리
     */
    suspend fun fetchUrl(request: UrlFetchRequest): KnowledgeDocument {
        val docId = UUID.randomUUID().toString()

        // Figma URL 감지
        val isFigmaUrl = isFigmaUrl(request.url)
        val sourceType = if (isFigmaUrl) SourceType.FIGMA else request.sourceType

        logger.info { "Fetching URL: ${request.url} (type: $sourceType)" }

        val document = KnowledgeDocument(
            id = docId,
            title = request.title ?: extractTitleFromUrl(request.url),
            content = "",
            source = sourceType,
            sourceUrl = request.url,
            status = IndexStatus.PROCESSING,
            projectId = request.projectId,
            metadata = mapOf(
                "autoSync" to request.autoSync,
                "syncIntervalHours" to request.syncIntervalHours
            )
        )

        knowledgeRepository.save(document)

        // 비동기로 처리
        scope.launch {
            try {
                val content = if (isFigmaUrl) {
                    fetchFigmaContent(request.url)
                } else {
                    fetchAndParseUrl(request.url)
                }

                val updatedDoc = document.copy(
                    content = content,
                    lastSyncedAt = Instant.now(),
                    updatedAt = Instant.now()
                )
                knowledgeRepository.save(updatedDoc)

                // 벡터화
                indexDocument(updatedDoc)

            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch URL: ${request.url}" }
                knowledgeRepository.updateStatus(docId, IndexStatus.ERROR, e.message)
            }
        }

        return document
    }

    /**
     * 문서 재인덱싱
     */
    suspend fun reindexDocument(documentId: String): Boolean {
        val document = knowledgeRepository.findById(documentId) ?: return false

        logger.info { "Re-indexing document: ${document.title}" }

        knowledgeRepository.updateStatus(documentId, IndexStatus.PROCESSING)

        scope.launch {
            try {
                // URL 소스면 다시 가져오기
                if (document.sourceUrl != null) {
                    // Figma URL은 fetchFigmaContent 사용
                    val content = if (isFigmaUrl(document.sourceUrl)) {
                        fetchFigmaContent(document.sourceUrl)
                    } else {
                        fetchAndParseUrl(document.sourceUrl)
                    }
                    val updatedDoc = document.copy(
                        content = content,
                        lastSyncedAt = Instant.now(),
                        updatedAt = Instant.now()
                    )
                    knowledgeRepository.save(updatedDoc)
                    indexDocument(updatedDoc)
                } else {
                    indexDocument(document)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to re-index document: ${document.title}" }
                knowledgeRepository.updateStatus(documentId, IndexStatus.ERROR, e.message)
            }
        }

        return true
    }

    /**
     * 문서 삭제
     */
    suspend fun deleteDocument(documentId: String): Boolean {
        val document = knowledgeRepository.findById(documentId) ?: return false

        logger.info { "Deleting document: ${document.title}" }

        // 벡터 DB에서 삭제
        knowledgeVectorService?.deleteByDocumentId(documentId)

        // DB에서 삭제
        return knowledgeRepository.delete(documentId)
    }

    /**
     * 문서 목록 조회
     */
    fun listDocuments(projectId: String? = null): List<KnowledgeDocument> {
        return knowledgeRepository.findByProject(projectId)
    }

    /**
     * 문서 조회
     */
    fun getDocument(documentId: String): KnowledgeDocument? {
        return knowledgeRepository.findById(documentId)
    }

    /**
     * 통계 조회
     */
    fun getStats(projectId: String? = null): KnowledgeStats {
        return knowledgeRepository.getStats(projectId)
    }

    /**
     * 동기화 필요한 문서 처리
     */
    suspend fun syncOutdatedDocuments(): Int {
        val documents = knowledgeRepository.findNeedingSync()
        logger.info { "Found ${documents.size} documents needing sync" }

        var synced = 0
        documents.forEach { doc ->
            try {
                if (doc.sourceUrl != null) {
                    val content = fetchAndParseUrl(doc.sourceUrl)

                    // 내용이 변경되었는지 확인
                    if (content != doc.content) {
                        val updatedDoc = doc.copy(
                            content = content,
                            status = IndexStatus.OUTDATED,
                            lastSyncedAt = Instant.now(),
                            updatedAt = Instant.now()
                        )
                        knowledgeRepository.save(updatedDoc)
                        indexDocument(updatedDoc)
                        synced++
                    } else {
                        knowledgeRepository.markSynced(doc.id)
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to sync document: ${doc.title}" }
            }
        }

        return synced
    }

    /**
     * 지식 검색
     */
    suspend fun search(
        query: String,
        projectId: String? = null,
        topK: Int = 5
    ): List<SearchResult> {
        if (knowledgeVectorService == null || embeddingService == null) {
            logger.warn { "Vector search not available" }
            return emptyList()
        }

        val embedding = embeddingService.embed(query) ?: run {
            logger.warn { "Failed to generate embedding for query" }
            return emptyList()
        }
        return knowledgeVectorService.searchKnowledge(
            embedding = embedding,
            topK = topK,
            filter = projectId?.let { mapOf("projectId" to it) }
        )
    }

    // ==================== Private Methods ====================

    private suspend fun processImage(file: File): String {
        if (imageAnalysisService == null) {
            throw IllegalStateException("Image analysis service not configured")
        }

        val result = imageAnalysisService.analyzeImage(file)
        return imageAnalysisService.toSearchableText(result)
    }

    private fun extractTextContent(file: File, mimeType: String): String {
        return when {
            mimeType == "text/plain" || mimeType == "text/markdown" || mimeType == "text/csv" -> {
                file.readText()
            }
            mimeType == "text/html" -> {
                Jsoup.parse(file, "UTF-8").text()
            }
            mimeType == "application/pdf" -> {
                // TODO: PDF 파싱 (Apache PDFBox 등)
                "[PDF 파싱 미구현]"
            }
            mimeType in EXCEL_TYPES -> {
                extractExcelContent(file)
            }
            else -> {
                file.readText()
            }
        }
    }

    /**
     * Excel 파일에서 텍스트 추출
     * 모든 시트의 내용을 읽어서 텍스트로 변환
     * 메모리 효율을 위해 최대 크기 제한 (500KB)
     */
    private fun extractExcelContent(file: File): String {
        val content = StringBuilder()
        val dataFormatter = DataFormatter()
        val maxContentSize = 500_000  // 500KB 제한

        FileInputStream(file).use { fis ->
            XSSFWorkbook(fis).use { workbook ->
                val sheetCount = workbook.numberOfSheets
                logger.info { "Excel file has $sheetCount sheets" }

                sheetLoop@ for (sheetIndex in 0 until sheetCount) {
                    if (content.length >= maxContentSize) {
                        logger.warn { "Content size limit reached at sheet $sheetIndex, truncating..." }
                        content.append("\n\n[... 이하 생략 - 크기 제한 초과 ...]")
                        break@sheetLoop
                    }

                    val sheet = workbook.getSheetAt(sheetIndex)
                    val sheetName = sheet.sheetName

                    content.append("\n\n=== Sheet: $sheetName ===\n\n")

                    for (row in sheet) {
                        if (content.length >= maxContentSize) break

                        val rowContent = StringBuilder()
                        var hasContent = false

                        for (cell in row) {
                            val cellValue = try {
                                when (cell.cellType) {
                                    CellType.STRING -> cell.stringCellValue.take(500)  // 셀당 최대 500자
                                    CellType.NUMERIC -> dataFormatter.formatCellValue(cell)
                                    CellType.BOOLEAN -> cell.booleanCellValue.toString()
                                    CellType.FORMULA -> {
                                        try {
                                            dataFormatter.formatCellValue(cell)
                                        } catch (e: Exception) {
                                            cell.cellFormula
                                        }
                                    }
                                    else -> ""
                                }.trim()
                            } catch (e: Exception) {
                                ""
                            }

                            if (cellValue.isNotEmpty()) {
                                if (hasContent) rowContent.append(" | ")
                                rowContent.append(cellValue)
                                hasContent = true
                            }
                        }

                        if (hasContent) {
                            content.append(rowContent.toString()).append("\n")
                        }
                    }
                }

                logger.info { "Extracted ${content.length} characters from Excel file" }
            }
        }

        return content.toString().trim()
    }

    private suspend fun fetchAndParseUrl(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "Mozilla/5.0 (compatible; ClaudeFlow/1.0)")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("HTTP error: ${response.statusCode()}")
        }

        val contentType = response.headers().firstValue("Content-Type").orElse("")

        return if (contentType.contains("html")) {
            val doc = Jsoup.parse(response.body())
            // 메인 콘텐츠 추출 시도
            val mainContent = doc.select("article, main, .content, #content").firstOrNull()
                ?: doc.body()
            mainContent.text()
        } else {
            response.body()
        }
    }

    /**
     * Figma URL인지 확인
     */
    private fun isFigmaUrl(url: String): Boolean {
        return FIGMA_URL_PATTERN.containsMatchIn(url)
    }

    /**
     * Figma URL에서 파일 키 추출
     */
    private fun extractFigmaFileKey(url: String): String? {
        return FIGMA_URL_PATTERN.find(url)?.groupValues?.getOrNull(2)
    }

    /**
     * Figma 파일 콘텐츠 가져오기 (Enhanced with Vision AI)
     *
     * Best Practice 구현:
     * 1. Figma API로 주요 Frame들을 이미지로 export
     * 2. Claude Vision으로 각 Frame 분석 (UI 컴포넌트, 기능 명세 추출)
     * 3. 모든 TEXT 노드 재귀 추출 (검색 가능성 확보)
     * 4. 텍스트 + 시각적 컨텍스트를 결합한 rich knowledge 생성
     *
     * @see https://developers.figma.com/docs/rest-api/file-endpoints/
     */
    private suspend fun fetchFigmaContent(url: String): String {
        if (figmaAccessToken.isNullOrBlank()) {
            throw IllegalStateException("Figma API token not configured. Set FIGMA_ACCESS_TOKEN environment variable.")
        }

        val fileKey = extractFigmaFileKey(url)
            ?: throw IllegalArgumentException("Invalid Figma URL: $url")

        logger.info { "Fetching Figma file with Vision AI analysis: $fileKey" }

        // 1. 파일 메타데이터 및 구조 가져오기 (depth=3으로 더 깊은 텍스트 노드까지)
        val fileData = fetchFigmaApi("/files/$fileKey?depth=3")
        val fileName = fileData.optString("name", "Untitled")
        val lastModified = fileData.optString("lastModified", "")

        // 2. 주요 Frame ID들 추출 (depth=1 레벨의 FRAME만)
        val document = fileData.optJSONObject("document")
        val topLevelFrames = extractTopLevelFrames(document)
        logger.info { "Found ${topLevelFrames.size} top-level frames" }

        // 2-1. 모든 TEXT 노드에서 텍스트 추출 (검색용, 별도 섹션으로 저장)
        val allTextContent = StringBuilder()
        if (document != null) {
            extractFigmaNodes(document, allTextContent, 0, maxDepth = 15)
        }
        logger.info { "Extracted ${allTextContent.length} characters from all text nodes" }

        // 3. 모든 Frame 이미지 export (배치 처리, Figma API 제한 고려)
        // Figma Images API는 한 번에 최대 500개 노드 지원
        val frameImages = mutableMapOf<String, String>()
        val batchSize = 50  // 안정적인 배치 크기
        val frameBatches = topLevelFrames.chunked(batchSize)

        logger.info { "Exporting ${topLevelFrames.size} frames in ${frameBatches.size} batches..." }

        frameBatches.forEachIndexed { batchIdx, batch ->
            try {
                val batchImages = exportFrameImages(fileKey, batch.map { it.first })
                frameImages.putAll(batchImages)
                logger.info { "Batch ${batchIdx + 1}/${frameBatches.size}: Exported ${batchImages.size} images" }
            } catch (e: Exception) {
                logger.warn { "Batch ${batchIdx + 1} failed: ${e.message}" }
            }
        }
        logger.info { "Total exported: ${frameImages.size}/${topLevelFrames.size} frame images" }

        // 4. Frame 정보 수집 (Vision AI 분석 제거 - 불안정하고 느림)
        val analyzedFrames = topLevelFrames.map { (nodeId, nodeName) ->
            FigmaFrame(
                id = nodeId,
                name = nodeName,
                imageUrl = frameImages[nodeId],
                description = null,
                textContent = null
            )
        }

        // 5. 코멘트 가져오기
        val comments = mutableListOf<String>()
        try {
            val commentsData = fetchFigmaApi("/files/$fileKey/comments")
            val commentsArray = commentsData.optJSONArray("comments")
            if (commentsArray != null) {
                for (i in 0 until minOf(commentsArray.length(), 50)) {
                    val comment = commentsArray.getJSONObject(i)
                    val user = comment.optJSONObject("user")?.optString("handle", "Unknown")
                    val message = comment.optString("message", "")
                    if (message.isNotBlank()) {
                        comments.add("@$user: $message")
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to fetch Figma comments: ${e.message}" }
        }

        // 6. Rich Content 생성 (Frame 이미지 + Vision 분석 + 모든 텍스트)
        val result = FigmaAnalysisResult(
            fileName = fileName,
            lastModified = lastModified,
            frames = analyzedFrames,
            comments = comments,
            allTextContent = allTextContent.toString()
        )

        return formatFigmaContent(result)
    }

    /**
     * 최상위 Frame들 추출 (Canvas > Frame)
     */
    private fun extractTopLevelFrames(document: org.json.JSONObject?): List<Pair<String, String>> {
        if (document == null) return emptyList()

        val frames = mutableListOf<Pair<String, String>>()

        // Document > Canvas (pages) > Frames
        val pages = document.optJSONArray("children") ?: return emptyList()

        for (i in 0 until pages.length()) {
            val page = pages.getJSONObject(i)
            val pageChildren = page.optJSONArray("children") ?: continue

            for (j in 0 until pageChildren.length()) {
                val node = pageChildren.getJSONObject(j)
                val nodeType = node.optString("type")
                val nodeName = node.optString("name", "Untitled")
                val nodeId = node.optString("id")

                // FRAME, COMPONENT_SET, COMPONENT만 추출
                if (nodeType in listOf("FRAME", "COMPONENT_SET", "COMPONENT") && nodeId.isNotBlank()) {
                    frames.add(nodeId to nodeName)
                }
            }
        }

        return frames
    }

    /**
     * Figma Frame들을 이미지로 export
     *
     * @see https://developers.figma.com/docs/rest-api/file-endpoints/#get-images-endpoint
     */
    private fun exportFrameImages(fileKey: String, nodeIds: List<String>): Map<String, String> {
        if (nodeIds.isEmpty()) return emptyMap()

        val idsParam = nodeIds.joinToString(",")
        logger.info { "Exporting ${nodeIds.size} frames as images..." }

        val response = fetchFigmaApi("/images/$fileKey?ids=$idsParam&format=png&scale=1")
        val images = response.optJSONObject("images") ?: return emptyMap()

        val result = mutableMapOf<String, String>()
        for (nodeId in nodeIds) {
            val imageUrl = images.optString(nodeId, null)
            if (!imageUrl.isNullOrBlank() && imageUrl != "null") {
                result[nodeId] = imageUrl
            }
        }

        return result
    }

    /**
     * Figma 분석 결과를 검색 가능한 텍스트로 포맷
     *
     * 구조 최적화:
     * - 각 Frame은 간결하게 (이름 + 이미지 URL만)
     * - 텍스트 콘텐츠는 검색용으로 맨 뒤에
     * - 청크당 3-5개 Frame이 들어가도록 간결하게
     */
    private fun formatFigmaContent(result: FigmaAnalysisResult): String {
        return buildString {
            appendLine("# ${result.fileName}")
            appendLine("Last Modified: ${result.lastModified}")
            appendLine("Total Frames: ${result.frames.size}")
            appendLine()

            // Frame 목록 (간결하게 - 각 Frame이 청크에 잘 분배되도록)
            result.frames.forEach { frame ->
                appendLine("## ${frame.name}")
                appendLine("Frame ID: ${frame.id}")
                frame.imageUrl?.let { appendLine("Image: $it") }
                appendLine()
            }

            // 검색용 텍스트 (모든 TEXT 노드에서 추출)
            if (result.allTextContent.isNotBlank()) {
                appendLine("---")
                appendLine()
                appendLine("# 텍스트 콘텐츠 (검색용)")
                appendLine()
                appendLine(result.allTextContent)
            }

            // 코멘트
            if (result.comments.isNotEmpty()) {
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("# 코멘트 (${result.comments.size})")
                result.comments.forEach { comment ->
                    appendLine("- $comment")
                }
            }
        }
    }

    /**
     * Figma API 호출
     *
     * 주의: Figma Rate Limit은 파일이 속한 플랜 기준으로 적용됨
     * - Starter/Free: 6개/월 (Tier 1)
     * - Professional: 15개/분
     * - Enterprise: 20개/분
     */
    private fun fetchFigmaApi(path: String): org.json.JSONObject {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$FIGMA_API_BASE$path"))
            .header("X-Figma-Token", figmaAccessToken)
            .header("Content-Type", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(180))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 429) {
            val retryAfter = response.headers().firstValue("Retry-After").orElse("unknown")
            throw RuntimeException("Figma rate limit exceeded (Retry-After: ${retryAfter}s). 파일이 Starter 플랜에 있다면 월 6회 제한입니다.")
        }

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Figma API error: ${response.statusCode()} - ${response.body().take(200)}")
        }

        return org.json.JSONObject(response.body())
    }

    /**
     * Figma 노드에서 텍스트 및 구조 추출 (재귀)
     */
    private fun extractFigmaNodes(
        node: org.json.JSONObject,
        content: StringBuilder,
        depth: Int,
        maxDepth: Int = 10
    ) {
        if (depth > maxDepth) return

        val indent = "  ".repeat(depth)
        val name = node.optString("name", "")
        val type = node.optString("type", "")

        // TEXT 노드에서 실제 텍스트 추출
        if (type == "TEXT") {
            val characters = node.optString("characters", "")
            if (characters.isNotBlank()) {
                content.append("$indent[Text] $name: $characters\n")
            }
        } else if (type == "FRAME" || type == "COMPONENT" || type == "COMPONENT_SET" || type == "INSTANCE") {
            // 주요 컴포넌트/프레임 이름 기록
            content.append("$indent[$type] $name\n")
        }

        // 자식 노드 순회
        val children = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                val child = children.getJSONObject(i)
                extractFigmaNodes(child, content, depth + 1, maxDepth)
            }
        }
    }

    private suspend fun indexDocument(document: KnowledgeDocument) {
        if (knowledgeVectorService == null || embeddingService == null) {
            logger.warn { "Vector service not configured, skipping indexing" }
            knowledgeRepository.updateStatus(document.id, IndexStatus.INDEXED)
            return
        }

        logger.info { "Indexing document: ${document.title}" }

        // 청킹
        val chunks = chunkText(document.content)

        // source/type 결정
        val vectorType = when (document.source) {
            SourceType.UPLOAD -> TYPE_DOCUMENT
            SourceType.URL, SourceType.CONFLUENCE, SourceType.NOTION -> TYPE_URL
            SourceType.FIGMA -> "figma"
            SourceType.IMAGE -> TYPE_IMAGE
        }

        // 배치 임베딩 (49개 청크 → 1번 API 호출)
        logger.info { "Generating embeddings for ${chunks.size} chunks using batch API..." }
        val embeddings = embeddingService.embedBatchNative(chunks)

        // 벡터 저장
        var indexed = 0
        chunks.forEachIndexed { index, chunkText ->
            val embedding = embeddings.getOrNull(index)
            if (embedding == null) {
                logger.warn { "No embedding for chunk $index of ${document.title}" }
                return@forEachIndexed
            }

            try {
                knowledgeVectorService.indexKnowledge(
                    id = "${document.id}#$index",
                    content = chunkText,
                    embedding = embedding,
                    metadata = mapOf(
                        "documentId" to document.id,
                        "documentTitle" to document.title,
                        "sourceType" to document.source.name,
                        "projectId" to (document.projectId ?: ""),
                        "chunkIndex" to index
                    ),
                    source = SOURCE_USER,
                    type = vectorType
                )
                indexed++
            } catch (e: Exception) {
                logger.warn(e) { "Failed to store chunk $index of ${document.title}" }
            }
        }

        knowledgeRepository.markIndexed(document.id, indexed)
        logger.info { "Indexed $indexed chunks for document: ${document.title}" }
    }

    private fun chunkText(text: String): List<String> {
        if (text.length <= CHUNK_SIZE) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var start = 0
        val maxChunks = 100  // 최대 청크 수 제한
        val minAdvance = CHUNK_SIZE - CHUNK_OVERLAP  // 최소 800자 전진

        while (start < text.length && chunks.size < maxChunks) {
            val end = minOf(start + CHUNK_SIZE, text.length)
            chunks.add(text.substring(start, end).trim())

            // 다음 시작점: 최소 minAdvance(800자) 전진 보장
            start += minAdvance
        }

        logger.info { "Created ${chunks.size} chunks from ${text.length} characters" }

        if (chunks.size >= maxChunks) {
            logger.warn { "Reached max chunk limit ($maxChunks), text truncated" }
        }

        return chunks.filter { it.isNotBlank() }
    }

    private fun extractTitleFromUrl(url: String): String {
        return try {
            URI.create(url).path
                .split("/")
                .lastOrNull { it.isNotBlank() }
                ?.replace("-", " ")
                ?.replace("_", " ")
                ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun detectMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "txt" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "html", "htm" -> "text/html"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "csv" -> "text/csv"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}

/**
 * 검색 결과
 */
data class SearchResult(
    val documentId: String,
    val documentTitle: String,
    val content: String,
    val score: Float,
    val metadata: Map<String, Any>
)
