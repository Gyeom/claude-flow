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
    private val embeddingService: EmbeddingService?
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

        logger.info { "Fetching URL: ${request.url}" }

        val document = KnowledgeDocument(
            id = docId,
            title = request.title ?: extractTitleFromUrl(request.url),
            content = "",
            source = request.sourceType,
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
                val content = fetchAndParseUrl(request.url)

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
                    val content = fetchAndParseUrl(document.sourceUrl)
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
