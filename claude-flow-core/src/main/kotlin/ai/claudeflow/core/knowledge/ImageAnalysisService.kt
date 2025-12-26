package ai.claudeflow.core.knowledge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.reflect.full.primaryConstructor
import mu.KotlinLogging
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * 이미지 분석 서비스
 *
 * Claude Code CLI를 사용하여 이미지에서 UI 스펙, 기능 명세 등을 추출합니다.
 * Figma 스크린샷, 와이어프레임, 디자인 시안 등을 분석할 수 있습니다.
 *
 * @param claudeExecutor Claude Code CLI 실행기 (선택적, null이면 API 직접 호출 시도)
 */
class ImageAnalysisService(
    private val claudeExecutor: Any? = null,  // ClaudeExecutor 타입 (순환 의존 방지)
    private val model: String = "claude-sonnet-4-20250514"
) {
    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    companion object {
        // 지원하는 이미지 타입
        val SUPPORTED_TYPES = setOf(
            "image/png", "image/jpeg", "image/gif", "image/webp"
        )

        // 분석 프롬프트
        private val ANALYSIS_PROMPT = """
이 이미지를 분석하여 다음 정보를 JSON 형식으로 추출해주세요:

1. **description**: 이미지에 대한 전체적인 설명 (한국어, 2-3문장)
2. **extractedText**: 이미지에서 읽을 수 있는 주요 텍스트 (OCR)
3. **uiComponents**: 주요 UI 컴포넌트 목록 (최대 5개)
   - name: 컴포넌트 이름
   - type: 컴포넌트 타입 (Button, Input, Modal, Card, Table, etc.)
4. **functionalSpecs**: 기능 명세 목록 (최대 5개)
   - 화면에서 파악할 수 있는 기능 요구사항

JSON 형식으로만 응답해주세요 (마크다운 코드 블록 없이):
{
  "description": "...",
  "extractedText": "...",
  "uiComponents": [{"name": "...", "type": "..."}],
  "functionalSpecs": ["..."]
}
        """.trimIndent()

        // 임시 파일 디렉토리 (WORKSPACE_PATH 내에 위치해야 Claude CLI가 접근 가능)
        private val TEMP_DIR: File by lazy {
            val workspacePath = System.getenv("WORKSPACE_PATH") ?: System.getProperty("user.dir")
            File(workspacePath, ".claude-flow-images").apply {
                if (!exists()) mkdirs()
            }
        }
    }

    /**
     * 이미지 파일 분석 (Claude Code CLI 사용)
     */
    suspend fun analyzeImage(imageFile: File): ImageAnalysisResult {
        require(imageFile.exists()) { "Image file not found: ${imageFile.absolutePath}" }

        val mimeType = detectMimeType(imageFile)
        require(mimeType in SUPPORTED_TYPES) { "Unsupported image type: $mimeType" }

        logger.info { "Analyzing image with Claude Code CLI: ${imageFile.name}" }

        return executeClaudeAnalysis(imageFile.absolutePath)
    }

    /**
     * URL 이미지 분석
     *
     * 1. URL에서 이미지를 다운로드하여 임시 파일로 저장
     * 2. Claude Code CLI로 분석
     * 3. 임시 파일 삭제
     *
     * @param imageUrl 분석할 이미지 URL
     * @param customPrompt 커스텀 프롬프트 (null이면 기본 ANALYSIS_PROMPT 사용)
     */
    suspend fun analyzeImageUrl(imageUrl: String, customPrompt: String? = null): ImageAnalysisResult {
        logger.info { "Downloading image from URL: ${imageUrl.take(80)}..." }

        // 임시 파일로 다운로드
        val tempFile = downloadImage(imageUrl)

        return try {
            executeClaudeAnalysis(tempFile.absolutePath, customPrompt)
        } finally {
            // 임시 파일 정리
            tempFile.delete()
        }
    }

    /**
     * 이미지 다운로드
     */
    private fun downloadImage(imageUrl: String): File {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(imageUrl))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Failed to download image: ${response.statusCode()}")
        }

        // 확장자 추출
        val extension = when {
            imageUrl.contains(".png", ignoreCase = true) -> "png"
            imageUrl.contains(".jpg", ignoreCase = true) || imageUrl.contains(".jpeg", ignoreCase = true) -> "jpg"
            imageUrl.contains(".gif", ignoreCase = true) -> "gif"
            imageUrl.contains(".webp", ignoreCase = true) -> "webp"
            else -> "png"
        }

        val tempFile = File(TEMP_DIR, "figma_${UUID.randomUUID()}.$extension")
        tempFile.writeBytes(response.body())

        logger.info { "Downloaded image to: ${tempFile.absolutePath} (${tempFile.length()} bytes)" }
        return tempFile
    }

    /**
     * Claude Code CLI로 이미지 분석 실행
     *
     * @param imagePath 이미지 파일 경로
     * @param customPrompt 커스텀 프롬프트 (null이면 기본 ANALYSIS_PROMPT 사용)
     */
    private suspend fun executeClaudeAnalysis(imagePath: String, customPrompt: String? = null): ImageAnalysisResult {
        // ClaudeExecutor가 주입되지 않은 경우 기본 결과 반환
        if (claudeExecutor == null) {
            logger.warn { "ClaudeExecutor not available, returning basic result" }
            return ImageAnalysisResult(
                description = "이미지 분석 서비스가 설정되지 않았습니다.",
                extractedText = null,
                uiComponents = emptyList(),
                designSpecs = null,
                functionalSpecs = emptyList(),
                rawAnalysis = ""
            )
        }

        val analysisPrompt = customPrompt ?: ANALYSIS_PROMPT
        val promptText = """
다음 이미지 파일을 읽고 분석해주세요: $imagePath

$analysisPrompt
        """.trimIndent()

        logger.info { "Prompt text length: ${promptText.length}, first 100 chars: ${promptText.take(100)}" }

        try {
            // Kotlin 리플렉션으로 ExecutionRequest 생성 (파라미터 이름 기반)
            val requestClass = Class.forName("ai.claudeflow.executor.ExecutionRequest").kotlin
            val constructor = requestClass.primaryConstructor
                ?: throw IllegalStateException("ExecutionRequest has no primary constructor")

            // workingDirectory 설정 (WORKSPACE_PATH 사용)
            val workspaceDir = System.getenv("WORKSPACE_PATH") ?: System.getProperty("user.dir")
            logger.info { "WorkspaceDir: $workspaceDir" }

            // 파라미터 이름으로 값 매핑 (callBy에서 null을 전달하면 default 사용이 아닌 null 설정이 됨)
            // 따라서 설정하지 않을 파라미터는 맵에서 제외해야 함
            val params = constructor.parameters.mapNotNull { param ->
                logger.debug { "Parameter: ${param.name}, type: ${param.type}" }
                when (param.name) {
                    "prompt" -> param to promptText
                    "model" -> param to "sonnet"
                    "maxTurns" -> param to 3
                    "allowedTools" -> param to listOf("Read")
                    "workingDirectory" -> param to workspaceDir
                    "agentId" -> param to "image-analysis"
                    "forceNewSession" -> param to false
                    else -> null  // 맵에서 제외하여 default 값 사용
                }
            }.toMap()

            logger.info { "Params map keys: ${params.keys.map { it.name }}" }
            val request = constructor.callBy(params)

            // 생성된 request의 prompt 필드 확인
            val promptField = request.javaClass.getDeclaredField("prompt")
            promptField.isAccessible = true
            val actualPrompt = promptField.get(request) as? String
            logger.info { "ExecutionRequest created - prompt length: ${actualPrompt?.length ?: 0}, first 50: ${actualPrompt?.take(50)}" }

            // execute 메소드 호출
            val executeMethod = claudeExecutor.javaClass.methods.first { it.name == "execute" }
            val result = executeMethod.invoke(claudeExecutor, request)

            // ExecutionResult에서 결과 추출
            val status = result.javaClass.getMethod("getStatus").invoke(result).toString()
            val responseText = result.javaClass.getMethod("getResult").invoke(result) as? String ?: ""

            if (status != "SUCCESS") {
                logger.error { "Claude Code CLI analysis failed: $status" }
                return ImageAnalysisResult(
                    description = "이미지 분석 실패",
                    extractedText = null,
                    uiComponents = emptyList(),
                    designSpecs = null,
                    functionalSpecs = emptyList(),
                    rawAnalysis = responseText
                )
            }

            return parseAnalysisResponse(responseText)

        } catch (e: Exception) {
            logger.error(e) { "Failed to execute Claude Code CLI analysis" }
            return ImageAnalysisResult(
                description = "이미지 분석 중 오류 발생: ${e.message}",
                extractedText = null,
                uiComponents = emptyList(),
                designSpecs = null,
                functionalSpecs = emptyList(),
                rawAnalysis = ""
            )
        }
    }

    /**
     * Claude 응답 파싱
     */
    private fun parseAnalysisResponse(responseText: String): ImageAnalysisResult {
        logger.debug { "Parsing response (${responseText.length} chars): ${responseText.take(200)}..." }

        // 1. 먼저 전체 응답을 JSON으로 파싱 시도 (Claude가 순수 JSON 반환 시)
        try {
            val parsed: Map<String, Any> = mapper.readValue(responseText.trim())
            logger.debug { "Direct JSON parsing successful" }
            return mapToAnalysisResult(parsed, responseText)
        } catch (_: Exception) {
            logger.debug { "Direct JSON parsing failed, trying regex extraction" }
        }

        // 2. ```json ... ``` 코드 블록에서 추출
        val codeBlockMatch = Regex("```json\\s*([\\s\\S]*?)\\s*```").find(responseText)
        if (codeBlockMatch != null) {
            val jsonContent = codeBlockMatch.groupValues[1].trim()
            try {
                val parsed: Map<String, Any> = mapper.readValue(jsonContent)
                logger.debug { "Code block JSON parsing successful" }
                return mapToAnalysisResult(parsed, responseText)
            } catch (e: Exception) {
                logger.debug { "Code block JSON parsing failed: ${e.message}" }
            }
        }

        // 3. 전체 JSON 객체 추출 (중첩 지원 - greedy 매칭)
        val jsonObjectMatch = Regex("\\{[\\s\\S]*\\}").find(responseText)
        if (jsonObjectMatch != null) {
            try {
                val parsed: Map<String, Any> = mapper.readValue(jsonObjectMatch.value)
                logger.debug { "Greedy JSON extraction successful" }
                return mapToAnalysisResult(parsed, responseText)
            } catch (e: Exception) {
                logger.debug { "Greedy JSON extraction failed: ${e.message}" }
            }
        }

        // 4. 파싱 실패 시 원문 반환
        logger.warn { "All JSON parsing attempts failed, using raw text" }
        return ImageAnalysisResult(
            description = responseText.take(500),
            extractedText = null,
            uiComponents = emptyList(),
            designSpecs = null,
            functionalSpecs = emptyList(),
            rawAnalysis = responseText
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToAnalysisResult(parsed: Map<String, Any>, rawText: String): ImageAnalysisResult {
        val uiComponents = (parsed["uiComponents"] as? List<Map<String, Any>>)?.map { comp ->
            UIComponent(
                name = comp["name"] as? String ?: "",
                type = comp["type"] as? String ?: "Unknown",
                description = comp["description"] as? String,
                properties = (comp["properties"] as? Map<String, String>) ?: emptyMap()
            )
        } ?: emptyList()

        val designSpecs = (parsed["designSpecs"] as? Map<String, Any>)?.let { specs ->
            DesignSpecs(
                colors = (specs["colors"] as? List<String>) ?: emptyList(),
                fonts = (specs["fonts"] as? List<String>) ?: emptyList(),
                spacing = (specs["spacing"] as? List<String>) ?: emptyList(),
                layout = specs["layout"] as? String
            )
        }

        val functionalSpecs = (parsed["functionalSpecs"] as? List<String>) ?: emptyList()

        return ImageAnalysisResult(
            description = parsed["description"] as? String ?: "",
            extractedText = parsed["extractedText"] as? String,
            uiComponents = uiComponents,
            designSpecs = designSpecs,
            functionalSpecs = functionalSpecs,
            rawAnalysis = rawText
        )
    }

    /**
     * 분석 결과를 검색 가능한 텍스트로 변환
     */
    fun toSearchableText(result: ImageAnalysisResult): String {
        return buildString {
            appendLine("# 이미지 분석 결과")
            appendLine()
            appendLine("## 설명")
            appendLine(result.description)
            appendLine()

            result.extractedText?.let {
                appendLine("## 추출된 텍스트")
                appendLine(it)
                appendLine()
            }

            if (result.uiComponents.isNotEmpty()) {
                appendLine("## UI 컴포넌트")
                result.uiComponents.forEach { comp ->
                    appendLine("- **${comp.name}** (${comp.type})")
                    comp.description?.let { appendLine("  - $it") }
                }
                appendLine()
            }

            result.designSpecs?.let { specs ->
                appendLine("## 디자인 스펙")
                if (specs.colors.isNotEmpty()) {
                    appendLine("- 색상: ${specs.colors.joinToString(", ")}")
                }
                if (specs.fonts.isNotEmpty()) {
                    appendLine("- 폰트: ${specs.fonts.joinToString(", ")}")
                }
                specs.layout?.let { appendLine("- 레이아웃: $it") }
                appendLine()
            }

            if (result.functionalSpecs.isNotEmpty()) {
                appendLine("## 기능 명세")
                result.functionalSpecs.forEach { spec ->
                    appendLine("- $spec")
                }
            }
        }
    }

    private fun detectMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }
}
