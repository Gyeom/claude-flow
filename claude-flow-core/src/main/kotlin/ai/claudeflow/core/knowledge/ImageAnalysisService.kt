package ai.claudeflow.core.knowledge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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

        // 임시 파일 디렉토리
        private val TEMP_DIR = File(System.getProperty("java.io.tmpdir"), "claude-flow-images").apply {
            if (!exists()) mkdirs()
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
     */
    suspend fun analyzeImageUrl(imageUrl: String): ImageAnalysisResult {
        logger.info { "Downloading image from URL: ${imageUrl.take(80)}..." }

        // 임시 파일로 다운로드
        val tempFile = downloadImage(imageUrl)

        return try {
            executeClaudeAnalysis(tempFile.absolutePath)
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
     */
    private suspend fun executeClaudeAnalysis(imagePath: String): ImageAnalysisResult {
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

        val prompt = """
다음 이미지 파일을 읽고 분석해주세요: $imagePath

$ANALYSIS_PROMPT
        """.trimIndent()

        try {
            // 리플렉션으로 ClaudeExecutor.execute 호출 (순환 의존 방지)
            val executeMethod = claudeExecutor.javaClass.getMethod("execute", Class.forName("ai.claudeflow.executor.ExecutionRequest"))
            val requestClass = Class.forName("ai.claudeflow.executor.ExecutionRequest")
            val requestConstructor = requestClass.constructors.first()

            // ExecutionRequest 생성 (prompt만 필수, 나머지 기본값)
            val request = requestConstructor.newInstance(
                prompt,      // prompt
                null,        // systemPrompt
                null,        // workingDirectory
                "sonnet",    // model
                3,           // maxTurns
                listOf("Read"),  // allowedTools - Read만 허용
                null,        // deniedTools
                null,        // config
                null,        // userId
                null,        // threadTs
                false,       // forceNewSession
                "image-analysis"  // agentId
            )

            val result = executeMethod.invoke(claudeExecutor, request)

            // ExecutionResult에서 결과 추출
            val statusMethod = result.javaClass.getMethod("getStatus")
            val resultMethod = result.javaClass.getMethod("getResult")

            val status = statusMethod.invoke(result).toString()
            val responseText = resultMethod.invoke(result) as? String ?: ""

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
        // JSON 블록 추출
        val jsonMatch = Regex("```json\\s*([\\s\\S]*?)\\s*```").find(responseText)
            ?: Regex("\\{[\\s\\S]*?\\}").find(responseText)

        val jsonString = jsonMatch?.groupValues?.getOrNull(1)
            ?: jsonMatch?.value
            ?: responseText

        return try {
            val parsed: Map<String, Any> = mapper.readValue(jsonString)
            mapToAnalysisResult(parsed, responseText)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse JSON, using raw text" }
            ImageAnalysisResult(
                description = responseText.take(500),
                extractedText = null,
                uiComponents = emptyList(),
                designSpecs = null,
                functionalSpecs = emptyList(),
                rawAnalysis = responseText
            )
        }
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
