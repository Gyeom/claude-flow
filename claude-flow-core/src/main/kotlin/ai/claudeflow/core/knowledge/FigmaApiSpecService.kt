package ai.claudeflow.core.knowledge

import ai.claudeflow.core.rag.EmbeddingService
import ai.claudeflow.core.rag.KnowledgeVectorService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import mu.KotlinLogging
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Figma 기획서에서 백엔드 API 스펙을 추출하는 서비스
 *
 * Vision AI를 사용하여 각 화면을 분석하고,
 * 서버 개발에 필요한 API 스펙을 구조화된 형태로 추출합니다.
 *
 * 주요 기능:
 * - Figma Frame 이미지 분석 (Vision AI)
 * - API 엔드포인트 추출
 * - Validation 규칙 추출
 * - 비즈니스 로직 추출
 * - 벡터 인덱싱 (검색용)
 */
class FigmaApiSpecService(
    private val figmaAccessToken: String,
    private val imageAnalysisService: ImageAnalysisService?,
    private val knowledgeVectorService: KnowledgeVectorService?,
    private val embeddingService: EmbeddingService?
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    private val objectMapper = jacksonObjectMapper()

    // Job 저장소 (In-memory, 프로덕션에서는 DB 사용 권장)
    private val jobs = mutableMapOf<String, FigmaAnalysisJob>()
    private val jobScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Job 업데이트 스트림 (SSE용)
    private val _jobUpdates = MutableSharedFlow<FigmaAnalysisJob>(replay = 0, extraBufferCapacity = 100)
    val jobUpdates: SharedFlow<FigmaAnalysisJob> = _jobUpdates.asSharedFlow()

    companion object {
        private const val FIGMA_API_BASE = "https://api.figma.com/v1"
        private const val MAX_CONCURRENT_ANALYSIS = 3  // 동시 분석 수 제한
        private const val ANALYSIS_TIMEOUT_SECONDS = 120L
        private const val MAX_JOBS = 100  // 최대 저장 Job 수
    }

    /**
     * 서버 개발자 관점 Vision AI 분석 프롬프트
     */
    private val serverDevAnalysisPrompt = """
이 Figma 화면을 백엔드 서버 개발자 관점에서 분석해주세요.

## 분석 관점
- 이 화면을 구현하기 위해 필요한 API 엔드포인트
- 각 입력 필드의 Validation 규칙
- 비즈니스 로직 및 제약사항
- 에러 케이스 및 예외 처리

## 출력 형식 (JSON)

```json
{
  "screen_name": "화면 이름 (한글)",
  "description": "화면 설명 (1-2문장)",

  "apis": [
    {
      "method": "POST",
      "path": "/api/v1/auth/login",
      "description": "로그인 API",
      "auth_required": false,
      "request_fields": [
        {"name": "email", "type": "string", "required": true, "validations": ["email_format"], "description": "사용자 이메일"},
        {"name": "password", "type": "string", "required": true, "validations": ["min:8"], "description": "비밀번호"}
      ],
      "response_fields": [
        {"name": "accessToken", "type": "string", "description": "JWT 액세스 토큰"},
        {"name": "refreshToken", "type": "string", "description": "리프레시 토큰"},
        {"name": "user", "type": "object", "description": "사용자 정보"}
      ],
      "errors": [
        {"code": 400, "condition": "잘못된 이메일 형식"},
        {"code": 401, "condition": "잘못된 비밀번호"},
        {"code": 423, "condition": "계정 잠금 (5회 실패)"}
      ],
      "notes": ["로그인 실패 시 실패 횟수 증가"]
    }
  ],

  "business_rules": [
    "로그인 실패 5회 시 30분 계정 잠금",
    "비밀번호는 90일마다 변경 필요"
  ],

  "validations": [
    {"field": "email", "rules": ["required", "email_format"], "error_message": "올바른 이메일을 입력하세요"},
    {"field": "password", "rules": ["required", "min:8", "contains_special"], "error_message": "8자 이상, 특수문자 포함"}
  ],

  "ui_states": ["default", "loading", "error", "success"],

  "related_screens": ["회원가입", "비밀번호 찾기", "2FA 인증"]
}
```

화면에 보이는 UI 요소를 기반으로 필요한 API를 추론하세요.
버튼, 폼, 입력 필드 등에서 서버 API 요구사항을 도출하세요.
JSON만 출력하세요. 추가 설명은 불필요합니다.
""".trimIndent()

    // ===== Async Job Methods =====

    /**
     * 비동기 분석 Job 시작
     *
     * @param figmaUrl Figma 파일 URL
     * @param title 문서 제목 (null이면 Figma에서 조회한 파일명 사용)
     * @param projectId 프로젝트 ID
     * @param indexToKnowledgeBase 완료 시 Knowledge Base에 인덱싱
     * @param description Figma 파일 설명 (AI 분석 컨텍스트로 활용)
     * @return Job ID
     */
    fun startAnalysisJob(
        figmaUrl: String,
        title: String? = null,
        projectId: String? = null,
        indexToKnowledgeBase: Boolean = true,
        description: String? = null
    ): FigmaAnalysisJob {
        // Figma 파일 키 추출
        val fileKey = extractFigmaFileKey(figmaUrl)
            ?: throw IllegalArgumentException("Invalid Figma URL: $figmaUrl")

        val jobId = UUID.randomUUID().toString()

        // Job 생성
        val job = FigmaAnalysisJob(
            id = jobId,
            figmaUrl = figmaUrl,
            figmaFileKey = fileKey,
            fileName = "Loading...",
            title = title,  // 사용자 지정 제목
            projectId = projectId,
            status = FigmaJobStatus.PENDING,
            progress = JobProgress(totalFrames = 0, analyzedFrames = 0),
            createdAt = Instant.now()
        )

        // 저장 (오래된 Job 정리)
        cleanupOldJobs()
        jobs[jobId] = job

        // 백그라운드에서 분석 시작
        jobScope.launch {
            runAnalysisJob(jobId, indexToKnowledgeBase, description)
        }

        logger.info { "Started Figma analysis job: $jobId for $figmaUrl" +
            (description?.let { ", description: $it" } ?: "") }
        return job
    }

    /**
     * Job 상태 조회
     */
    fun getJob(jobId: String): FigmaAnalysisJob? = jobs[jobId]

    /**
     * 전체 Job 목록 조회
     */
    fun listJobs(limit: Int = 20): List<FigmaAnalysisJob> {
        return jobs.values
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    /**
     * Job 삭제
     */
    fun deleteJob(jobId: String): Boolean {
        return jobs.remove(jobId) != null
    }

    /**
     * 특정 Job의 업데이트 스트림 (SSE용)
     * Job이 완료되거나 실패하면 스트림 종료
     */
    fun getJobUpdates(jobId: String): Flow<FigmaAnalysisJob> {
        return jobUpdates
            .filter { it.id == jobId }
            .takeWhile { it.status !in listOf(FigmaJobStatus.COMPLETED, FigmaJobStatus.FAILED) }
            .onStart {
                // 현재 상태를 먼저 emit
                jobs[jobId]?.let { emit(it) }
            }
            .onCompletion {
                // 완료 시 최종 상태 emit
                jobs[jobId]?.let { emit(it) }
            }
    }

    /**
     * Job 상태 업데이트 및 이벤트 발행
     */
    private suspend fun updateJob(jobId: String, updater: (FigmaAnalysisJob) -> FigmaAnalysisJob) {
        jobs[jobId]?.let { currentJob ->
            val updatedJob = updater(currentJob)
            jobs[jobId] = updatedJob
            _jobUpdates.emit(updatedJob)
        }
    }

    /**
     * 백그라운드에서 실제 분석 수행
     */
    private suspend fun runAnalysisJob(jobId: String, indexToKnowledgeBase: Boolean, description: String? = null) {
        val job = jobs[jobId] ?: return

        try {
            // 상태 업데이트: PROCESSING
            updateJob(jobId) { it.copy(
                status = FigmaJobStatus.PROCESSING,
                startedAt = Instant.now()
            )}

            // 분석 실행 (진행 콜백 포함)
            val result = extractApiSpecs(
                figmaUrl = job.figmaUrl,
                options = FigmaAnalysisOptions(),
                projectId = job.projectId,
                description = description,
                onProgress = { current, total, frameName ->
                    // 진행 상황을 SSE로 스트리밍
                    jobScope.launch {
                        updateJob(jobId) { currentJob ->
                            currentJob.copy(
                                fileName = if (currentJob.fileName == "Loading...") frameName else currentJob.fileName,
                                progress = JobProgress(
                                    totalFrames = total,
                                    analyzedFrames = current,
                                    currentFrame = frameName
                                )
                            )
                        }
                    }
                }
            )

            // Knowledge Base 인덱싱
            if (indexToKnowledgeBase) {
                indexApiSpecs(result, jobId)
            }

            // 상태 업데이트: COMPLETED
            updateJob(jobId) { it.copy(
                status = FigmaJobStatus.COMPLETED,
                fileName = result.fileName,
                result = result,
                progress = JobProgress(
                    totalFrames = result.totalFrames,
                    analyzedFrames = result.analyzedFrames
                ),
                completedAt = Instant.now()
            )}

            logger.info { "Completed Figma analysis job: $jobId (${result.analyzedFrames} frames)" }

        } catch (e: Exception) {
            logger.error(e) { "Failed Figma analysis job: $jobId" }

            // 상태 업데이트: FAILED
            updateJob(jobId) { it.copy(
                status = FigmaJobStatus.FAILED,
                errorMessage = e.message ?: "Unknown error",
                completedAt = Instant.now()
            )}
        }
    }

    /**
     * 오래된 Job 정리
     */
    private fun cleanupOldJobs() {
        if (jobs.size >= MAX_JOBS) {
            val completed = jobs.values
                .filter { it.status in listOf(FigmaJobStatus.COMPLETED, FigmaJobStatus.FAILED) }
                .sortedBy { it.createdAt }
                .take(jobs.size - MAX_JOBS + 10)

            completed.forEach { jobs.remove(it.id) }
            logger.info { "Cleaned up ${completed.size} old jobs" }
        }
    }

    // ===== Sync API Spec Extraction =====

    /**
     * Figma 파일에서 API 스펙 추출
     *
     * @param figmaUrl Figma 파일 URL
     * @param options 분석 옵션
     * @param projectId 프로젝트 ID (검색 필터용)
     * @param description Figma 파일 설명 (AI 분석 컨텍스트로 활용)
     * @param onProgress 진행률 콜백
     * @return 분석 결과
     */
    suspend fun extractApiSpecs(
        figmaUrl: String,
        options: FigmaAnalysisOptions = FigmaAnalysisOptions(),
        projectId: String? = null,
        description: String? = null,
        onProgress: ((current: Int, total: Int, frameName: String) -> Unit)? = null
    ): EnhancedFigmaAnalysisResult = coroutineScope {
        val startTime = System.currentTimeMillis()

        // 1. Figma 파일 키 및 노드 ID 추출
        val fileKey = extractFigmaFileKey(figmaUrl)
            ?: throw IllegalArgumentException("Invalid Figma URL: $figmaUrl")
        val targetNodeId = extractFigmaNodeId(figmaUrl)

        logger.info { "Starting API spec extraction for Figma file: $fileKey" +
            (targetNodeId?.let { ", target node: $it" } ?: " (full file)") }

        // 2. 파일 메타데이터 가져오기
        val fileData = fetchFigmaApi("/files/$fileKey?depth=2")
        val fileName = fileData.optString("name", "Untitled")
        val lastModified = fileData.optString("lastModified", "")

        // 3. Frame 추출 (node-id가 있으면 해당 노드 하위만, 없으면 전체)
        val document = fileData.optJSONObject("document")
        val frames = if (targetNodeId != null) {
            // 특정 노드 하위의 Frame만 추출
            logger.info { "Fetching frames under node: $targetNodeId" }
            val nodeData = fetchFigmaApi("/files/$fileKey/nodes?ids=$targetNodeId&depth=2")
            val nodes = nodeData.optJSONObject("nodes")
            val targetNode = nodes?.optJSONObject(targetNodeId)?.optJSONObject("document")
            extractFramesFromNode(targetNode)
        } else {
            // 전체 파일에서 Frame 추출
            extractTopLevelFrames(document)
        }

        logger.info { "Found ${frames.size} frames" +
            (description?.let { " (context: $it)" } ?: "") }

        // 4. Frame 이미지 Export (배치 처리)
        val frameImages = exportFrameImages(fileKey, frames.map { it.first })

        // 5. Vision AI로 각 프레임 분석 (병렬 처리, 동시성 제한)
        val screenSpecs = mutableListOf<ScreenApiSpec>()
        var analyzedCount = 0
        var skippedCount = 0
        frames.chunked(MAX_CONCURRENT_ANALYSIS).forEach { chunk ->
            val results = chunk.map { (frameId, frameName) ->
                async(Dispatchers.IO) {
                    val imageUrl = frameImages[frameId]
                    if (imageUrl != null && options.analyzeWithVision && imageAnalysisService != null) {
                        try {
                            onProgress?.invoke(analyzedCount + 1, frames.size, frameName)
                            val spec = analyzeFrameWithVision(
                                frameId = frameId,
                                frameName = frameName,
                                imageUrl = imageUrl,
                                fileKey = fileKey,
                                projectId = projectId,
                                includeRaw = options.includeRawAnalysis,
                                description = description
                            )
                            spec
                        } catch (e: Exception) {
                            logger.warn { "Failed to analyze frame $frameName: ${e.message}" }
                            null
                        }
                    } else {
                        null
                    }
                }
            }.awaitAll()

            results.filterNotNull().forEach { spec ->
                screenSpecs.add(spec)
                analyzedCount++
            }
            skippedCount += results.count { it == null }
        }

        // 6. 텍스트 콘텐츠 추출 (기존 호환성)
        val allTextContent = StringBuilder()
        if (document != null) {
            extractFigmaTextNodes(document, allTextContent, 0, maxDepth = 10)
        }

        // 7. 코멘트 수집
        val comments = fetchFigmaComments(fileKey)

        // 8. 통계 계산
        val totalApis = screenSpecs.sumOf { it.apis.size }
        val totalValidations = screenSpecs.sumOf { it.validations.size }
        val totalBusinessRules = screenSpecs.sumOf { it.businessRules.size }

        val result = EnhancedFigmaAnalysisResult(
            fileName = fileName,
            fileKey = fileKey,
            lastModified = lastModified,
            totalFrames = frames.size,
            screenSpecs = screenSpecs,
            allTextContent = allTextContent.toString(),
            comments = comments,
            totalApis = totalApis,
            totalValidations = totalValidations,
            totalBusinessRules = totalBusinessRules,
            analyzedFrames = analyzedCount,
            skippedFrames = skippedCount,
            processingTimeMs = System.currentTimeMillis() - startTime
        )

        logger.info {
            "Figma analysis completed: $analyzedCount frames, $totalApis APIs, " +
            "$totalValidations validations, $totalBusinessRules rules (${result.processingTimeMs}ms)"
        }

        result
    }

    /**
     * Vision AI로 단일 프레임 분석
     *
     * @param description Figma 파일 설명 (AI 분석 컨텍스트)
     */
    private suspend fun analyzeFrameWithVision(
        frameId: String,
        frameName: String,
        imageUrl: String,
        fileKey: String,
        projectId: String?,
        includeRaw: Boolean,
        description: String? = null
    ): ScreenApiSpec = withContext(Dispatchers.IO) {
        logger.debug { "Analyzing frame: $frameName ($frameId)" }

        // Figma 프레임 이름과 컨텍스트를 프롬프트에 명시하여 hallucination 방지
        val contextSection = if (description != null) {
            """
## 도메인 컨텍스트
이 Figma 파일은 다음과 관련된 화면들입니다: **$description**
분석 시 이 컨텍스트를 고려하여 관련 도메인 용어와 기능을 사용하세요.

"""
        } else ""

        val promptWithFrameName = """
$contextSection## 화면 정보
- **Figma 프레임 이름**: $frameName
- 아래 분석 결과의 screen_name은 반드시 "$frameName"을 사용하세요.

$serverDevAnalysisPrompt
""".trimIndent()

        // Vision AI로 이미지 분석
        val analysisResult = imageAnalysisService!!.analyzeImageUrl(imageUrl, promptWithFrameName)

        // JSON 파싱 시도
        val parsed = parseApiSpecJson(analysisResult.rawAnalysis, frameName)

        ScreenApiSpec(
            screenId = frameId,
            screenName = parsed.screenName,
            imageUrl = imageUrl,
            figmaFileKey = fileKey,
            projectId = projectId,
            apis = parsed.apis,
            businessRules = parsed.businessRules,
            validations = parsed.validations,
            uiStates = parsed.uiStates,
            relatedScreens = parsed.relatedScreens,
            analyzedAt = Instant.now(),
            rawAnalysis = if (includeRaw) analysisResult.rawAnalysis else null
        )
    }

    /**
     * Vision AI 응답에서 API 스펙 JSON 파싱
     */
    private fun parseApiSpecJson(rawResponse: String, fallbackName: String): ParsedApiSpec {
        return try {
            // JSON 블록 추출 (```json ... ``` 또는 { ... })
            val jsonContent = extractJsonFromResponse(rawResponse)
            val json = JSONObject(jsonContent)

            val screenName = json.optString("screen_name", fallbackName)

            // APIs 파싱
            val apis = mutableListOf<ApiEndpointSpec>()
            json.optJSONArray("apis")?.let { apisArray ->
                for (i in 0 until apisArray.length()) {
                    val apiJson = apisArray.getJSONObject(i)
                    apis.add(parseApiEndpoint(apiJson))
                }
            }

            // Business Rules 파싱
            val businessRules = mutableListOf<String>()
            json.optJSONArray("business_rules")?.let { rulesArray ->
                for (i in 0 until rulesArray.length()) {
                    businessRules.add(rulesArray.getString(i))
                }
            }

            // Validations 파싱
            val validations = mutableListOf<ValidationRule>()
            json.optJSONArray("validations")?.let { validationsArray ->
                for (i in 0 until validationsArray.length()) {
                    val vJson = validationsArray.getJSONObject(i)
                    validations.add(parseValidation(vJson))
                }
            }

            // UI States 파싱
            val uiStates = mutableListOf<String>()
            json.optJSONArray("ui_states")?.let { statesArray ->
                for (i in 0 until statesArray.length()) {
                    uiStates.add(statesArray.getString(i))
                }
            }

            // Related Screens 파싱
            val relatedScreens = mutableListOf<String>()
            json.optJSONArray("related_screens")?.let { screensArray ->
                for (i in 0 until screensArray.length()) {
                    relatedScreens.add(screensArray.getString(i))
                }
            }

            ParsedApiSpec(screenName, apis, businessRules, validations, uiStates, relatedScreens)

        } catch (e: Exception) {
            logger.warn { "Failed to parse API spec JSON: ${e.message}" }
            // 파싱 실패 시 기본값 반환
            ParsedApiSpec(
                screenName = fallbackName,
                apis = emptyList(),
                businessRules = emptyList(),
                validations = emptyList(),
                uiStates = emptyList(),
                relatedScreens = emptyList()
            )
        }
    }

    private fun extractJsonFromResponse(response: String): String {
        // ```json ... ``` 블록 추출
        val jsonBlockRegex = """```json\s*([\s\S]*?)\s*```""".toRegex()
        jsonBlockRegex.find(response)?.let {
            return it.groupValues[1].trim()
        }

        // { ... } 블록 추출
        val startIdx = response.indexOf('{')
        val endIdx = response.lastIndexOf('}')
        if (startIdx >= 0 && endIdx > startIdx) {
            return response.substring(startIdx, endIdx + 1)
        }

        return response
    }

    private fun parseApiEndpoint(json: JSONObject): ApiEndpointSpec {
        val requestFields = mutableListOf<FieldSpec>()
        json.optJSONArray("request_fields")?.let { fields ->
            for (i in 0 until fields.length()) {
                requestFields.add(parseFieldSpec(fields.getJSONObject(i)))
            }
        }

        val responseFields = mutableListOf<FieldSpec>()
        json.optJSONArray("response_fields")?.let { fields ->
            for (i in 0 until fields.length()) {
                responseFields.add(parseFieldSpec(fields.getJSONObject(i)))
            }
        }

        val errors = mutableListOf<ErrorCase>()
        json.optJSONArray("errors")?.let { errorsArray ->
            for (i in 0 until errorsArray.length()) {
                val errJson = errorsArray.getJSONObject(i)
                errors.add(ErrorCase(
                    code = errJson.optInt("code", 500),
                    errorCode = errJson.optString("error_code", null),
                    condition = errJson.optString("condition", "Unknown"),
                    message = errJson.optString("message", null)
                ))
            }
        }

        val notes = mutableListOf<String>()
        json.optJSONArray("notes")?.let { notesArray ->
            for (i in 0 until notesArray.length()) {
                notes.add(notesArray.getString(i))
            }
        }

        return ApiEndpointSpec(
            method = json.optString("method", "GET"),
            path = json.optString("path", "/unknown"),
            description = json.optString("description", ""),
            requestFields = requestFields,
            responseFields = responseFields,
            errorCases = errors,
            authRequired = json.optBoolean("auth_required", true),
            notes = notes
        )
    }

    private fun parseFieldSpec(json: JSONObject): FieldSpec {
        val validations = mutableListOf<String>()
        json.optJSONArray("validations")?.let { vArray ->
            for (i in 0 until vArray.length()) {
                validations.add(vArray.getString(i))
            }
        }

        return FieldSpec(
            name = json.optString("name", "unknown"),
            type = json.optString("type", "string"),
            required = json.optBoolean("required", false),
            description = json.optString("description", null),
            validations = validations,
            example = json.optString("example", null)
        )
    }

    private fun parseValidation(json: JSONObject): ValidationRule {
        val rules = mutableListOf<String>()
        json.optJSONArray("rules")?.let { rulesArray ->
            for (i in 0 until rulesArray.length()) {
                rules.add(rulesArray.getString(i))
            }
        }

        return ValidationRule(
            field = json.optString("field", "unknown"),
            rules = rules,
            errorMessage = json.optString("error_message", null)
        )
    }

    /**
     * API 스펙을 벡터 인덱싱
     */
    suspend fun indexApiSpecs(
        result: EnhancedFigmaAnalysisResult,
        documentId: String
    ): Int = coroutineScope {
        if (knowledgeVectorService == null || embeddingService == null) {
            logger.warn { "Vector service not available, skipping indexing" }
            return@coroutineScope 0
        }

        var indexedCount = 0

        result.screenSpecs.forEach { spec ->
            try {
                // Vision AI 분석 결과가 없으면 스킵 (의미없는 청크 방지)
                if (spec.apis.isEmpty() && spec.businessRules.isEmpty() && spec.validations.isEmpty()) {
                    logger.debug { "Skipping ${spec.screenName}: no API specs extracted" }
                    return@forEach
                }

                // 검색 가능한 텍스트로 변환
                val searchableText = buildSearchableText(spec)

                // 최소 길이 검증 (제목만 있는 경우 스킵)
                if (searchableText.length < 100) {
                    logger.debug { "Skipping ${spec.screenName}: content too short (${searchableText.length} chars)" }
                    return@forEach
                }

                // 임베딩 생성
                val embedding = embeddingService.embed(searchableText)
                if (embedding != null) {
                    // 벡터 DB에 저장
                    knowledgeVectorService.indexKnowledge(
                        id = UUID.randomUUID().toString(),
                        content = searchableText,
                        embedding = embedding,
                        metadata = mapOf(
                            "type" to "API_SPEC",
                            "documentId" to documentId,
                            "screenId" to spec.screenId,
                            "screenName" to spec.screenName,
                            "figmaFileKey" to spec.figmaFileKey,
                            "projectId" to (spec.projectId ?: ""),
                            "apiCount" to spec.apis.size,
                            "imageUrl" to (spec.imageUrl ?: "")
                        )
                    )
                    indexedCount++
                }
            } catch (e: Exception) {
                logger.warn { "Failed to index spec for ${spec.screenName}: ${e.message}" }
            }
        }

        logger.info { "Indexed $indexedCount API specs to vector DB" }
        indexedCount
    }

    /**
     * API 스펙을 검색 가능한 텍스트로 변환
     */
    private fun buildSearchableText(spec: ScreenApiSpec): String {
        return buildString {
            appendLine("# ${spec.screenName}")
            appendLine()

            // APIs
            if (spec.apis.isNotEmpty()) {
                appendLine("## APIs")
                spec.apis.forEach { api ->
                    appendLine("- ${api.method} ${api.path}: ${api.description}")
                    if (api.requestFields.isNotEmpty()) {
                        appendLine("  Request: ${api.requestFields.joinToString(", ") { "${it.name}(${it.type})" }}")
                    }
                    if (api.responseFields.isNotEmpty()) {
                        appendLine("  Response: ${api.responseFields.joinToString(", ") { "${it.name}(${it.type})" }}")
                    }
                    if (api.errorCases.isNotEmpty()) {
                        appendLine("  Errors: ${api.errorCases.joinToString(", ") { "${it.code}:${it.condition}" }}")
                    }
                }
                appendLine()
            }

            // Business Rules
            if (spec.businessRules.isNotEmpty()) {
                appendLine("## 비즈니스 규칙")
                spec.businessRules.forEach { rule ->
                    appendLine("- $rule")
                }
                appendLine()
            }

            // Validations
            if (spec.validations.isNotEmpty()) {
                appendLine("## Validation")
                spec.validations.forEach { v ->
                    appendLine("- ${v.field}: ${v.rules.joinToString(", ")}")
                }
            }
        }
    }

    /**
     * API 스펙 검색
     */
    suspend fun searchApiSpecs(
        query: String,
        projectId: String? = null,
        topK: Int = 5
    ): List<ScreenApiSpec> {
        if (knowledgeVectorService == null || embeddingService == null) {
            return emptyList()
        }

        val embedding = embeddingService.embed(query) ?: return emptyList()

        val filter = mutableMapOf<String, Any>("type" to "API_SPEC")
        if (projectId != null) {
            filter["projectId"] = projectId
        }

        return knowledgeVectorService.searchKnowledge(
            embedding = embedding,
            topK = topK,
            filter = filter
        ).mapNotNull { result ->
            // 메타데이터에서 ScreenApiSpec 재구성 (간소화된 버전)
            try {
                ScreenApiSpec(
                    screenId = result.metadata["screenId"] as? String ?: "",
                    screenName = result.metadata["screenName"] as? String ?: "",
                    imageUrl = result.metadata["imageUrl"] as? String,
                    figmaFileKey = result.metadata["figmaFileKey"] as? String ?: "",
                    projectId = result.metadata["projectId"] as? String,
                    apis = emptyList(),  // 전체 데이터는 별도 조회 필요
                    businessRules = emptyList(),
                    validations = emptyList(),
                    uiStates = emptyList(),
                    relatedScreens = emptyList(),
                    analyzedAt = Instant.now()
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    // ===== Figma API Helper Methods =====

    /**
     * Figma URL에서 파일 키 추출
     */
    private fun extractFigmaFileKey(url: String): String? {
        val patterns = listOf(
            """figma\.com/(?:file|design)/([a-zA-Z0-9]+)""".toRegex(),
            """figma\.com/(?:proto|board)/([a-zA-Z0-9]+)""".toRegex()
        )
        for (pattern in patterns) {
            pattern.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    /**
     * Figma URL에서 node-id 추출
     *
     * URL 예시: https://www.figma.com/design/xxx?node-id=11250-126016
     * 반환: "11250:126016" (Figma API 형식으로 변환)
     */
    private fun extractFigmaNodeId(url: String): String? {
        // node-id 파라미터 추출 (URL에서는 - 사용, API에서는 : 사용)
        val nodeIdPattern = """[?&]node-id=([0-9]+-[0-9]+)""".toRegex()
        return nodeIdPattern.find(url)?.groupValues?.get(1)?.replace("-", ":")
    }

    private fun fetchFigmaApi(path: String): JSONObject {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$FIGMA_API_BASE$path"))
            .header("X-Figma-Token", figmaAccessToken)
            .header("Content-Type", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(60))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 429) {
            throw RuntimeException("Figma rate limit exceeded")
        }
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Figma API error: ${response.statusCode()}")
        }

        return JSONObject(response.body())
    }

    /**
     * 전체 문서에서 최상위 Frame 추출 (기존 방식)
     */
    private fun extractTopLevelFrames(document: JSONObject?): List<Pair<String, String>> {
        if (document == null) return emptyList()

        val frames = mutableListOf<Pair<String, String>>()
        val pages = document.optJSONArray("children") ?: return emptyList()

        for (i in 0 until pages.length()) {
            val page = pages.getJSONObject(i)
            val pageChildren = page.optJSONArray("children") ?: continue

            for (j in 0 until pageChildren.length()) {
                val node = pageChildren.getJSONObject(j)
                val nodeType = node.optString("type")
                val nodeName = node.optString("name", "Untitled")
                val nodeId = node.optString("id")

                if (nodeType in listOf("FRAME", "COMPONENT_SET", "COMPONENT") && nodeId.isNotBlank()) {
                    frames.add(nodeId to nodeName)
                }
            }
        }

        return frames
    }

    /**
     * 특정 노드 하위에서 Frame 추출 (node-id 지정 시 사용)
     *
     * 재귀적으로 하위 노드를 탐색하여 FRAME, COMPONENT_SET, COMPONENT 타입 노드를 수집합니다.
     */
    private fun extractFramesFromNode(node: JSONObject?): List<Pair<String, String>> {
        if (node == null) return emptyList()

        val frames = mutableListOf<Pair<String, String>>()
        val nodeType = node.optString("type")
        val nodeName = node.optString("name", "Untitled")
        val nodeId = node.optString("id")

        // 현재 노드가 Frame 타입이면 추가
        if (nodeType in listOf("FRAME", "COMPONENT_SET", "COMPONENT") && nodeId.isNotBlank()) {
            frames.add(nodeId to nodeName)
        }

        // 하위 노드 재귀 탐색
        val children = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                val child = children.getJSONObject(i)
                frames.addAll(extractFramesFromNode(child))
            }
        }

        return frames
    }

    private fun exportFrameImages(fileKey: String, nodeIds: List<String>): Map<String, String> {
        if (nodeIds.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, String>()

        // 배치 처리 (10개씩)
        nodeIds.chunked(10).forEach { batch ->
            try {
                val idsParam = batch.joinToString(",")
                val response = fetchFigmaApi("/images/$fileKey?ids=$idsParam&format=png&scale=1")
                val images = response.optJSONObject("images") ?: return@forEach

                for (nodeId in batch) {
                    val imageUrl = images.optString(nodeId, null)
                    if (!imageUrl.isNullOrBlank() && imageUrl != "null") {
                        result[nodeId] = imageUrl
                    }
                }

                // Rate limit 방지
                Thread.sleep(200)
            } catch (e: Exception) {
                logger.warn { "Failed to export images for batch: ${e.message}" }
            }
        }

        return result
    }

    private fun extractFigmaTextNodes(
        node: JSONObject,
        builder: StringBuilder,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return

        val nodeType = node.optString("type")
        if (nodeType == "TEXT") {
            val characters = node.optString("characters", "")
            if (characters.isNotBlank()) {
                builder.appendLine(characters)
            }
        }

        node.optJSONArray("children")?.let { children ->
            for (i in 0 until children.length()) {
                extractFigmaTextNodes(children.getJSONObject(i), builder, depth + 1, maxDepth)
            }
        }
    }

    private fun fetchFigmaComments(fileKey: String): List<String> {
        return try {
            val response = fetchFigmaApi("/files/$fileKey/comments")
            val comments = mutableListOf<String>()
            response.optJSONArray("comments")?.let { commentsArray ->
                for (i in 0 until minOf(commentsArray.length(), 50)) {
                    val comment = commentsArray.getJSONObject(i)
                    val user = comment.optJSONObject("user")?.optString("handle", "Unknown")
                    val message = comment.optString("message", "")
                    if (message.isNotBlank()) {
                        comments.add("@$user: $message")
                    }
                }
            }
            comments
        } catch (e: Exception) {
            logger.warn { "Failed to fetch comments: ${e.message}" }
            emptyList()
        }
    }

    // 내부 파싱 결과 데이터 클래스
    private data class ParsedApiSpec(
        val screenName: String,
        val apis: List<ApiEndpointSpec>,
        val businessRules: List<String>,
        val validations: List<ValidationRule>,
        val uiStates: List<String>,
        val relatedScreens: List<String>
    )
}
