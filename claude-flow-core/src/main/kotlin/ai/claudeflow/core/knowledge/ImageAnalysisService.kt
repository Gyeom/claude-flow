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
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * 이미지 분석 서비스
 *
 * Claude Vision API를 사용하여 이미지에서 UI 스펙, 기능 명세 등을 추출합니다.
 * Figma 스크린샷, 와이어프레임, 디자인 시안 등을 분석할 수 있습니다.
 */
class ImageAnalysisService(
    private val apiKey: String = System.getenv("ANTHROPIC_API_KEY") ?: "",
    private val model: String = "claude-sonnet-4-20250514"
) {
    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"

        // 지원하는 이미지 타입
        val SUPPORTED_TYPES = setOf(
            "image/png", "image/jpeg", "image/gif", "image/webp"
        )

        // 분석 프롬프트
        private val ANALYSIS_PROMPT = """
            이 이미지를 분석하여 다음 정보를 JSON 형식으로 추출해주세요:

            1. **description**: 이미지에 대한 전체적인 설명 (한국어)
            2. **extractedText**: 이미지에서 읽을 수 있는 모든 텍스트 (OCR)
            3. **uiComponents**: UI 컴포넌트 목록
               - name: 컴포넌트 이름 (예: "로그인 버튼")
               - type: 컴포넌트 타입 (Button, Input, Modal, Card, Table, etc.)
               - description: 컴포넌트 설명
               - properties: 속성 (color, size, state 등)
            4. **designSpecs**: 디자인 스펙
               - colors: 사용된 색상 목록 (가능하면 hex 코드)
               - fonts: 폰트 스타일
               - spacing: 여백/간격 패턴
               - layout: 레이아웃 구조 설명
            5. **functionalSpecs**: 기능 명세 목록
               - 화면에서 파악할 수 있는 기능 요구사항
               - 사용자 인터랙션 흐름
               - 비즈니스 로직 힌트

            JSON 형식으로만 응답해주세요:
            ```json
            {
              "description": "...",
              "extractedText": "...",
              "uiComponents": [...],
              "designSpecs": {...},
              "functionalSpecs": [...]
            }
            ```
        """.trimIndent()
    }

    /**
     * 이미지 파일 분석
     */
    suspend fun analyzeImage(imageFile: File): ImageAnalysisResult {
        require(imageFile.exists()) { "Image file not found: ${imageFile.absolutePath}" }

        val mimeType = detectMimeType(imageFile)
        require(mimeType in SUPPORTED_TYPES) { "Unsupported image type: $mimeType" }

        val base64Data = Base64.getEncoder().encodeToString(imageFile.readBytes())
        return analyzeBase64Image(base64Data, mimeType)
    }

    /**
     * Base64 인코딩된 이미지 분석
     */
    suspend fun analyzeBase64Image(
        base64Data: String,
        mimeType: String
    ): ImageAnalysisResult {
        require(apiKey.isNotBlank()) { "ANTHROPIC_API_KEY is not set" }

        logger.info { "Analyzing image with Claude Vision (type: $mimeType)" }

        val requestBody = mapOf(
            "model" to model,
            "max_tokens" to 4096,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "image",
                            "source" to mapOf(
                                "type" to "base64",
                                "media_type" to mimeType,
                                "data" to base64Data
                            )
                        ),
                        mapOf(
                            "type" to "text",
                            "text" to ANALYSIS_PROMPT
                        )
                    )
                )
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
            .timeout(Duration.ofSeconds(120))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Claude API error: ${response.statusCode()} - ${response.body()}")
        }

        return parseAnalysisResponse(response.body())
    }

    /**
     * URL 이미지 분석
     */
    suspend fun analyzeImageUrl(imageUrl: String): ImageAnalysisResult {
        require(apiKey.isNotBlank()) { "ANTHROPIC_API_KEY is not set" }

        logger.info { "Analyzing image from URL: $imageUrl" }

        val requestBody = mapOf(
            "model" to model,
            "max_tokens" to 4096,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "image",
                            "source" to mapOf(
                                "type" to "url",
                                "url" to imageUrl
                            )
                        ),
                        mapOf(
                            "type" to "text",
                            "text" to ANALYSIS_PROMPT
                        )
                    )
                )
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
            .timeout(Duration.ofSeconds(120))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Claude API error: ${response.statusCode()} - ${response.body()}")
        }

        return parseAnalysisResponse(response.body())
    }

    /**
     * Claude 응답 파싱
     */
    private fun parseAnalysisResponse(responseBody: String): ImageAnalysisResult {
        val response: Map<String, Any> = mapper.readValue(responseBody)

        @Suppress("UNCHECKED_CAST")
        val content = response["content"] as? List<Map<String, Any>>
            ?: throw RuntimeException("Invalid response format")

        val textContent = content.find { it["type"] == "text" }
            ?: throw RuntimeException("No text content in response")

        val rawText = textContent["text"] as String

        // JSON 블록 추출
        val jsonMatch = Regex("```json\\s*([\\s\\S]*?)\\s*```").find(rawText)
            ?: Regex("\\{[\\s\\S]*\\}").find(rawText)

        val jsonString = jsonMatch?.groupValues?.getOrNull(1)
            ?: jsonMatch?.value
            ?: rawText

        return try {
            val parsed: Map<String, Any> = mapper.readValue(jsonString)
            mapToAnalysisResult(parsed, rawText)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse JSON, using raw text" }
            ImageAnalysisResult(
                description = rawText.take(500),
                extractedText = null,
                uiComponents = emptyList(),
                designSpecs = null,
                functionalSpecs = emptyList(),
                rawAnalysis = rawText
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
