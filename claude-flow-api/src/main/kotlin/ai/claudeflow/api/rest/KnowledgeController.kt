package ai.claudeflow.api.rest

import ai.claudeflow.core.knowledge.*
import ai.claudeflow.core.rag.EmbeddingService
import ai.claudeflow.core.rag.KnowledgeVectorService
import ai.claudeflow.core.rag.KnowledgeResult
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.File
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Knowledge Base REST API
 *
 * 문서 업로드, URL 수집, 이미지 분석, 검색 등을 제공합니다.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
class KnowledgeController(
    private val knowledgeService: KnowledgeService,
    private val knowledgeVectorService: KnowledgeVectorService?,
    private val imageAnalysisService: ImageAnalysisService?,
    private val figmaApiSpecService: FigmaApiSpecService?
) {
    private val uploadDir = File(System.getProperty("java.io.tmpdir"), "claude-flow-uploads")
        .also { it.mkdirs() }
    private val objectMapper = ObjectMapper()

    /**
     * 문서 목록 조회
     */
    @GetMapping("/documents")
    fun listDocuments(
        @RequestParam(required = false) projectId: String?
    ): ResponseEntity<List<KnowledgeDocumentDto>> {
        val documents = knowledgeService.listDocuments(projectId)
        return ResponseEntity.ok(documents.map { it.toDto() })
    }

    /**
     * 문서 상세 조회
     */
    @GetMapping("/documents/{id}")
    fun getDocument(@PathVariable id: String): ResponseEntity<KnowledgeDocumentDto> {
        val document = knowledgeService.getDocument(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(document.toDto())
    }

    /**
     * 파일 업로드
     */
    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadFile(
        @RequestPart("file") filePart: FilePart,
        @RequestPart(name = "title", required = false) title: String?,
        @RequestPart(name = "projectId", required = false) projectId: String?
    ): Mono<ResponseEntity<KnowledgeDocumentDto>> {
        val tempFile = File(uploadDir, "${UUID.randomUUID()}_${filePart.filename()}")

        return filePart.transferTo(tempFile)
            .then(Mono.fromCallable {
                runBlocking {
                    val document = knowledgeService.uploadFile(
                        file = tempFile,
                        request = DocumentUploadRequest(
                            title = title,
                            projectId = projectId
                        )
                    )
                    ResponseEntity.status(HttpStatus.CREATED).body(document.toDto())
                }
            })
            .doFinally {
                // 처리 완료 후 임시 파일 정리는 KnowledgeService에서 관리
            }
    }

    /**
     * URL 수집
     */
    @PostMapping("/url")
    fun fetchUrl(@RequestBody request: UrlFetchRequestDto): ResponseEntity<KnowledgeDocumentDto> {
        return runBlocking {
            try {
                val document = knowledgeService.fetchUrl(
                    UrlFetchRequest(
                        url = request.url,
                        title = request.title,
                        sourceType = request.sourceType?.let { SourceType.valueOf(it) } ?: SourceType.URL,
                        projectId = request.projectId,
                        autoSync = request.autoSync ?: false,
                        syncIntervalHours = request.syncIntervalHours ?: 24
                    )
                )
                ResponseEntity.status(HttpStatus.CREATED).body(document.toDto())
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch URL: ${request.url}" }
                ResponseEntity.badRequest().build()
            }
        }
    }

    /**
     * 문서 재인덱싱
     */
    @PostMapping("/documents/{id}/reindex")
    fun reindexDocument(@PathVariable id: String): ResponseEntity<Map<String, Any>> {
        return runBlocking {
            val success = knowledgeService.reindexDocument(id)
            if (success) {
                ResponseEntity.ok(mapOf("success" to true, "message" to "Re-indexing started"))
            } else {
                ResponseEntity.notFound().build()
            }
        }
    }

    /**
     * 문서 삭제
     */
    @DeleteMapping("/documents/{id}")
    fun deleteDocument(@PathVariable id: String): ResponseEntity<Void> {
        return runBlocking {
            val success = knowledgeService.deleteDocument(id)
            if (success) {
                ResponseEntity.noContent().build()
            } else {
                ResponseEntity.notFound().build()
            }
        }
    }

    /**
     * 지식 검색
     */
    @GetMapping("/search")
    fun search(
        @RequestParam query: String,
        @RequestParam(required = false) projectId: String?,
        @RequestParam(defaultValue = "5") topK: Int
    ): ResponseEntity<List<SearchResultDto>> {
        return runBlocking {
            val results = knowledgeService.search(query, projectId, topK)
            ResponseEntity.ok(results.map { it.toDto() })
        }
    }

    /**
     * 통계 조회
     */
    @GetMapping("/stats")
    fun getStats(
        @RequestParam(required = false) projectId: String?
    ): ResponseEntity<KnowledgeStatsDto> {
        val stats = knowledgeService.getStats(projectId)
        return ResponseEntity.ok(stats.toDto())
    }

    /**
     * 동기화 트리거
     */
    @PostMapping("/sync")
    fun triggerSync(): ResponseEntity<Map<String, Any>> {
        return runBlocking {
            val synced = knowledgeService.syncOutdatedDocuments()
            ResponseEntity.ok(mapOf(
                "success" to true,
                "syncedCount" to synced
            ))
        }
    }

    // ==================== Vector Store API ====================

    /**
     * 벡터 스토어 전체 데이터 조회 (source별 그룹화)
     */
    @GetMapping("/vectors")
    fun getVectorData(): ResponseEntity<VectorDataResponse> {
        if (knowledgeVectorService == null) {
            return ResponseEntity.ok(VectorDataResponse(
                system = emptyList(),
                user = emptyList(),
                stats = VectorStatsDto(emptyMap(), emptyMap(), 0)
            ))
        }

        val dataBySource = knowledgeVectorService.getAllBySource()
        val statsBySource = knowledgeVectorService.getStatsBySource()

        val systemData = dataBySource["system"]?.map { it.toVectorItemDto() } ?: emptyList()
        val userData = dataBySource["user"]?.map { it.toVectorItemDto() } ?: emptyList()

        return ResponseEntity.ok(VectorDataResponse(
            system = systemData,
            user = userData,
            stats = VectorStatsDto(
                system = statsBySource["system"] ?: emptyMap(),
                user = statsBySource["user"] ?: emptyMap(),
                total = systemData.size + userData.size
            )
        ))
    }

    /**
     * 벡터 스토어 통계 조회
     */
    @GetMapping("/vectors/stats")
    fun getVectorStats(): ResponseEntity<VectorStatsDto> {
        if (knowledgeVectorService == null) {
            return ResponseEntity.ok(VectorStatsDto(emptyMap(), emptyMap(), 0))
        }

        val statsBySource = knowledgeVectorService.getStatsBySource()
        val total = statsBySource.values.sumOf { it.values.sum() }

        return ResponseEntity.ok(VectorStatsDto(
            system = statsBySource["system"] ?: emptyMap(),
            user = statsBySource["user"] ?: emptyMap(),
            total = total
        ))
    }

    /**
     * 이미지 분석 (별도 엔드포인트)
     */
    @PostMapping("/analyze-image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun analyzeImage(
        @RequestPart("file") filePart: FilePart
    ): Mono<ResponseEntity<ImageAnalysisResultDto>> {
        if (imageAnalysisService == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build())
        }

        val tempFile = File(uploadDir, "${UUID.randomUUID()}_${filePart.filename()}")

        return filePart.transferTo(tempFile)
            .then(Mono.fromCallable {
                runBlocking {
                    try {
                        val result = imageAnalysisService.analyzeImage(tempFile)
                        ResponseEntity.ok(result.toDto())
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to analyze image" }
                        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build<ImageAnalysisResultDto>()
                    } finally {
                        tempFile.delete()
                    }
                }
            })
    }

    // ==================== Figma API Spec (Design-Aware Code Review) ====================

    /**
     * Figma 분석 Job 시작 (비동기)
     *
     * 전체 프레임을 백그라운드에서 배치 분석합니다.
     * Job ID를 즉시 반환하고, 진행 상황은 별도 API로 조회합니다.
     */
    @PostMapping("/figma/extract-api-specs")
    fun startFigmaAnalysisJob(
        @RequestBody request: FigmaApiSpecRequestDto
    ): ResponseEntity<FigmaJobDto> {
        if (figmaApiSpecService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(null)
        }

        return try {
            val job = figmaApiSpecService.startAnalysisJob(
                figmaUrl = request.figmaUrl,
                title = request.title,
                projectId = request.projectId,
                indexToKnowledgeBase = request.indexToKnowledgeBase ?: true,
                description = request.description
            )

            ResponseEntity.accepted().body(job.toDto())
        } catch (e: IllegalArgumentException) {
            logger.warn { "Invalid Figma URL: ${request.figmaUrl}" }
            ResponseEntity.badRequest().build()
        } catch (e: Exception) {
            logger.error(e) { "Failed to start Figma analysis job: ${request.figmaUrl}" }
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Figma 분석 Job 상태 조회
     */
    @GetMapping("/figma/jobs/{jobId}")
    fun getFigmaJob(@PathVariable jobId: String): ResponseEntity<FigmaJobDto> {
        if (figmaApiSpecService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }

        val job = figmaApiSpecService.getJob(jobId)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(job.toDto())
    }

    /**
     * Figma 분석 Job 목록 조회
     */
    @GetMapping("/figma/jobs")
    fun listFigmaJobs(
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<List<FigmaJobDto>> {
        if (figmaApiSpecService == null) {
            return ResponseEntity.ok(emptyList())
        }

        val jobs = figmaApiSpecService.listJobs(limit)
        return ResponseEntity.ok(jobs.map { it.toDto() })
    }

    /**
     * Figma 분석 Job 삭제
     */
    @DeleteMapping("/figma/jobs/{jobId}")
    fun deleteFigmaJob(@PathVariable jobId: String): ResponseEntity<Void> {
        if (figmaApiSpecService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }

        return if (figmaApiSpecService.deleteJob(jobId)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Figma 분석 Job 진행 상황 스트리밍 (SSE)
     *
     * EventSource를 통해 실시간 진행 상황을 받습니다.
     * Job이 완료되거나 실패하면 스트림이 종료됩니다.
     */
    @GetMapping("/figma/jobs/{jobId}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamFigmaJobProgress(@PathVariable jobId: String): Flux<ServerSentEvent<String>> {
        if (figmaApiSpecService == null) {
            return Flux.just(
                ServerSentEvent.builder<String>()
                    .event("error")
                    .data("""{"message": "Service unavailable"}""")
                    .build()
            )
        }

        val job = figmaApiSpecService.getJob(jobId)
        if (job == null) {
            return Flux.just(
                ServerSentEvent.builder<String>()
                    .event("error")
                    .data("""{"message": "Job not found"}""")
                    .build()
            )
        }

        // Job이 이미 완료/실패 상태면 즉시 반환
        if (job.status in listOf(FigmaJobStatus.COMPLETED, FigmaJobStatus.FAILED)) {
            return Flux.just(
                ServerSentEvent.builder<String>()
                    .event("job")
                    .data(objectMapper.writeValueAsString(job.toDto()))
                    .build()
            )
        }

        // Flow를 Flux로 변환하여 SSE 스트리밍
        return Flux.from(
            figmaApiSpecService.getJobUpdates(jobId)
                .map { updatedJob ->
                    ServerSentEvent.builder<String>()
                        .event("job")
                        .data(objectMapper.writeValueAsString(updatedJob.toDto()))
                        .build()
                }
                .asPublisher()
        ).doOnCancel {
            logger.info { "SSE stream cancelled for job: $jobId" }
        }.doOnComplete {
            logger.info { "SSE stream completed for job: $jobId" }
        }
    }

    /**
     * API 스펙 검색 (MR 리뷰용)
     *
     * 코드 변경과 관련된 기획서 스펙을 검색합니다.
     */
    @GetMapping("/figma/search-api-specs")
    fun searchApiSpecs(
        @RequestParam query: String,
        @RequestParam(required = false) projectId: String?,
        @RequestParam(defaultValue = "5") topK: Int
    ): ResponseEntity<List<ScreenApiSpecDto>> {
        if (figmaApiSpecService == null) {
            return ResponseEntity.ok(emptyList())
        }

        return runBlocking {
            try {
                val results = figmaApiSpecService.searchApiSpecs(
                    query = query,
                    projectId = projectId,
                    topK = topK
                )
                ResponseEntity.ok(results.map { it.toDto() })
            } catch (e: Exception) {
                logger.error(e) { "Failed to search API specs: $query" }
                ResponseEntity.ok(emptyList())
            }
        }
    }
}

// ==================== DTOs ====================

data class KnowledgeDocumentDto(
    val id: String,
    val title: String,
    val source: String,
    val sourceUrl: String?,
    val mimeType: String?,
    val status: String,
    val chunkCount: Int,
    val errorMessage: String?,
    val projectId: String?,
    val createdAt: String,
    val updatedAt: String,
    val lastIndexedAt: String?,
    val lastSyncedAt: String?
)

data class UrlFetchRequestDto(
    val url: String,
    val title: String? = null,
    val sourceType: String? = null,
    val projectId: String? = null,
    val autoSync: Boolean? = null,
    val syncIntervalHours: Int? = null
)

data class SearchResultDto(
    val documentId: String,
    val documentTitle: String,
    val content: String,
    val score: Float,
    val metadata: Map<String, Any>
)

data class KnowledgeStatsDto(
    val totalDocuments: Int,
    val totalChunks: Int,
    val bySource: Map<String, Int>,
    val byStatus: Map<String, Int>,
    val recentQueries: Int,
    val lastUpdated: String?
)

data class ImageAnalysisResultDto(
    val description: String,
    val extractedText: String?,
    val uiComponents: List<UIComponentDto>,
    val designSpecs: DesignSpecsDto?,
    val functionalSpecs: List<String>
)

data class UIComponentDto(
    val name: String,
    val type: String,
    val description: String?,
    val properties: Map<String, String>
)

data class DesignSpecsDto(
    val colors: List<String>,
    val fonts: List<String>,
    val spacing: List<String>,
    val layout: String?
)

// ==================== Extensions ====================

private fun KnowledgeDocument.toDto() = KnowledgeDocumentDto(
    id = id,
    title = title,
    source = source.name,
    sourceUrl = sourceUrl,
    mimeType = mimeType,
    status = status.name,
    chunkCount = chunkCount,
    errorMessage = errorMessage,
    projectId = projectId,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    lastIndexedAt = lastIndexedAt?.toString(),
    lastSyncedAt = lastSyncedAt?.toString()
)

private fun SearchResult.toDto() = SearchResultDto(
    documentId = documentId,
    documentTitle = documentTitle,
    content = content,
    score = score,
    metadata = metadata
)

private fun KnowledgeStats.toDto() = KnowledgeStatsDto(
    totalDocuments = totalDocuments,
    totalChunks = totalChunks,
    bySource = bySource.mapKeys { it.key.name },
    byStatus = byStatus.mapKeys { it.key.name },
    recentQueries = recentQueries,
    lastUpdated = lastUpdated?.toString()
)

private fun ImageAnalysisResult.toDto() = ImageAnalysisResultDto(
    description = description,
    extractedText = extractedText,
    uiComponents = uiComponents.map { it.toDto() },
    designSpecs = designSpecs?.toDto(),
    functionalSpecs = functionalSpecs
)

private fun UIComponent.toDto() = UIComponentDto(
    name = name,
    type = type,
    description = description,
    properties = properties
)

private fun DesignSpecs.toDto() = DesignSpecsDto(
    colors = colors,
    fonts = fonts,
    spacing = spacing,
    layout = layout
)

private fun KnowledgeResult.toVectorItemDto() = VectorItemDto(
    type = type,
    docId = docId,
    content = content,
    name = metadata["name"] as? String,
    description = metadata["description"] as? String,
    updatedAt = metadata["updated_at"] as? String,
    metadata = metadata.filterKeys { it !in listOf("type", "doc_id", "content", "source") }
)

// ==================== Vector Store DTOs ====================

data class VectorDataResponse(
    val system: List<VectorItemDto>,
    val user: List<VectorItemDto>,
    val stats: VectorStatsDto
)

data class VectorItemDto(
    val type: String,
    val docId: String,
    val content: String,
    val name: String?,
    val description: String?,
    val updatedAt: String?,
    val metadata: Map<String, Any>
)

data class VectorStatsDto(
    val system: Map<String, Int>,
    val user: Map<String, Int>,
    val total: Int
)

// ==================== Figma API Spec DTOs ====================

data class FigmaApiSpecRequestDto(
    val figmaUrl: String,
    val title: String? = null,        // 문서 제목 (e.g., "CCDC Figma 기획 문서 (v0.6)")
    val projectId: String? = null,
    val indexToKnowledgeBase: Boolean? = true,
    val description: String? = null   // Figma 파일 설명 - AI 분석 컨텍스트로 활용 (e.g., "차량 관리, 진단 시나리오, 데이터 수집 정책 관련 화면")
)

data class FigmaJobDto(
    val id: String,
    val figmaUrl: String,
    val figmaFileKey: String,
    val fileName: String,
    val title: String?,               // 사용자 지정 제목
    val projectId: String?,
    val status: String,
    val progress: JobProgressDto,
    val result: EnhancedFigmaAnalysisResultDto?,
    val errorMessage: String?,
    val createdAt: String,
    val startedAt: String?,
    val completedAt: String?
)

data class JobProgressDto(
    val totalFrames: Int,
    val analyzedFrames: Int,
    val currentFrame: String?,
    val percentage: Int
)

data class EnhancedFigmaAnalysisResultDto(
    val fileName: String,
    val fileKey: String,
    val lastModified: String,
    val totalFrames: Int,
    val screenSpecs: List<ScreenApiSpecDto>,
    val comments: List<String>,
    val stats: FigmaAnalysisStatsDto,
    val processingTimeMs: Long
)

data class FigmaAnalysisStatsDto(
    val totalApis: Int,
    val totalValidations: Int,
    val totalBusinessRules: Int,
    val analyzedFrames: Int,
    val skippedFrames: Int
)

data class ScreenApiSpecDto(
    val screenId: String,
    val screenName: String,
    val imageUrl: String?,
    val figmaFileKey: String,
    val projectId: String?,
    val apis: List<ApiEndpointSpecDto>,
    val businessRules: List<String>,
    val validations: List<ValidationRuleDto>,
    val uiStates: List<String>,
    val relatedScreens: List<String>,
    val analyzedAt: String
)

data class ApiEndpointSpecDto(
    val method: String,
    val path: String,
    val description: String,
    val requestFields: List<FieldSpecDto>,
    val responseFields: List<FieldSpecDto>,
    val errorCases: List<ErrorCaseDto>,
    val authRequired: Boolean,
    val notes: List<String>
)

data class FieldSpecDto(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String?,
    val validations: List<String>,
    val example: String?
)

data class ValidationRuleDto(
    val field: String,
    val rules: List<String>,
    val errorMessage: String?
)

data class ErrorCaseDto(
    val code: Int,
    val errorCode: String?,
    val condition: String,
    val message: String?
)

// ==================== Figma Extensions ====================

private fun EnhancedFigmaAnalysisResult.toDto() = EnhancedFigmaAnalysisResultDto(
    fileName = fileName,
    fileKey = fileKey,
    lastModified = lastModified,
    totalFrames = totalFrames,
    screenSpecs = screenSpecs.map { it.toDto() },
    comments = comments,
    stats = FigmaAnalysisStatsDto(
        totalApis = totalApis,
        totalValidations = totalValidations,
        totalBusinessRules = totalBusinessRules,
        analyzedFrames = analyzedFrames,
        skippedFrames = skippedFrames
    ),
    processingTimeMs = processingTimeMs
)

private fun ScreenApiSpec.toDto() = ScreenApiSpecDto(
    screenId = screenId,
    screenName = screenName,
    imageUrl = imageUrl,
    figmaFileKey = figmaFileKey,
    projectId = projectId,
    apis = apis.map { it.toDto() },
    businessRules = businessRules,
    validations = validations.map { it.toDto() },
    uiStates = uiStates,
    relatedScreens = relatedScreens,
    analyzedAt = analyzedAt.toString()
)

private fun ApiEndpointSpec.toDto() = ApiEndpointSpecDto(
    method = method,
    path = path,
    description = description,
    requestFields = requestFields.map { it.toDto() },
    responseFields = responseFields.map { it.toDto() },
    errorCases = errorCases.map { it.toDto() },
    authRequired = authRequired,
    notes = notes
)

private fun FieldSpec.toDto() = FieldSpecDto(
    name = name,
    type = type,
    required = required,
    description = description,
    validations = validations,
    example = example
)

private fun ValidationRule.toDto() = ValidationRuleDto(
    field = field,
    rules = rules,
    errorMessage = errorMessage
)

private fun ErrorCase.toDto() = ErrorCaseDto(
    code = code,
    errorCode = errorCode,
    condition = condition,
    message = message
)

private fun FigmaAnalysisJob.toDto() = FigmaJobDto(
    id = id,
    figmaUrl = figmaUrl,
    figmaFileKey = figmaFileKey,
    fileName = fileName,
    title = title,
    projectId = projectId,
    status = status.name,
    progress = progress.toDto(),
    result = result?.toDto(),
    errorMessage = errorMessage,
    createdAt = createdAt.toString(),
    startedAt = startedAt?.toString(),
    completedAt = completedAt?.toString()
)

private fun JobProgress.toDto() = JobProgressDto(
    totalFrames = totalFrames,
    analyzedFrames = analyzedFrames,
    currentFrame = currentFrame,
    percentage = percentage
)

