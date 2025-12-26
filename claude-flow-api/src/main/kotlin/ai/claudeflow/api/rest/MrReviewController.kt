package ai.claudeflow.api.rest

import ai.claudeflow.core.plugin.GitLabPlugin
import ai.claudeflow.core.plugin.PluginRegistry
import ai.claudeflow.core.review.MrAnalyzer
import ai.claudeflow.core.review.MrAnalysisResult
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

/**
 * MR 리뷰 전용 API 컨트롤러
 *
 * Best Practice 기반 MR 분석 엔드포인트 제공:
 * - 단일 API 호출로 필요한 모든 정보 수집
 * - GitLab API 플래그 직접 활용 (renamed_file, new_file, deleted_file)
 * - 2-Pass 리뷰 아키텍처 (규칙 기반 + AI 분석)
 */
@RestController
@RequestMapping("/api/v1/mr-review")
class MrReviewController(
    private val pluginRegistry: PluginRegistry
) {
    private val mrAnalyzer = MrAnalyzer()

    /**
     * MR 분석 (Pass 1: 규칙 기반 빠른 분석)
     *
     * GitLab API 플래그를 직접 활용하여 효율적으로 분석합니다.
     *
     * @param project GitLab 프로젝트 경로 (예: sirius/ccds-server)
     * @param mrId MR 번호
     * @return MrAnalysisResult
     */
    @GetMapping("/analyze/{project}/{mrId}")
    fun analyzeMr(
        @PathVariable project: String,
        @PathVariable mrId: Int
    ): ResponseEntity<Map<String, Any>> {
        logger.info { "Analyzing MR !$mrId in project $project" }

        val gitlabPlugin = pluginRegistry.get("gitlab") as? GitLabPlugin
        if (gitlabPlugin == null) {
            return ResponseEntity.badRequest().body(mapOf<String, Any>(
                "success" to false,
                "error" to "GitLab 플러그인이 초기화되지 않았습니다"
            ))
        }

        return try {
            val result = runBlocking {
                gitlabPlugin.execute("mr-review", mapOf(
                    "project" to project,
                    "mr_id" to mrId
                ))
            }

            if (result.success) {
                ResponseEntity.ok(mapOf<String, Any>(
                    "success" to true,
                    "data" to (result.data ?: emptyMap<String, Any>()),
                    "message" to (result.message ?: "")
                ))
            } else {
                ResponseEntity.badRequest().body(mapOf<String, Any>(
                    "success" to false,
                    "error" to (result.error ?: "Unknown error")
                ))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to analyze MR !$mrId" }
            ResponseEntity.internalServerError().body(mapOf<String, Any>(
                "success" to false,
                "error" to "MR 분석 실패: ${e.message}"
            ))
        }
    }

    /**
     * MR 파일 분석만 수행 (경량 버전)
     *
     * RAG 없이 GitLab API 플래그만으로 빠르게 분석합니다.
     *
     * @param project GitLab 프로젝트 경로
     * @param mrId MR 번호
     * @return 파일 분석 결과 (renamed, added, deleted, modified)
     */
    @GetMapping("/files/{project}/{mrId}")
    fun analyzeFiles(
        @PathVariable project: String,
        @PathVariable mrId: Int
    ): ResponseEntity<Map<String, Any>> {
        logger.info { "Analyzing files for MR !$mrId in project $project" }

        val gitlabPlugin = pluginRegistry.get("gitlab") as? GitLabPlugin
        if (gitlabPlugin == null) {
            return ResponseEntity.badRequest().body(mapOf<String, Any>(
                "success" to false,
                "error" to "GitLab 플러그인이 초기화되지 않았습니다"
            ))
        }

        return try {
            val result = runBlocking {
                gitlabPlugin.execute("mr-review", mapOf(
                    "project" to project,
                    "mr_id" to mrId
                ))
            }

            if (result.success && result.data != null) {
                @Suppress("UNCHECKED_CAST")
                val data = result.data as Map<String, Any>
                val fileAnalysis = data["fileAnalysis"] as? Map<String, Any> ?: emptyMap()

                ResponseEntity.ok(mapOf<String, Any>(
                    "success" to true,
                    "fileAnalysis" to fileAnalysis,
                    "summary" to (data["summary"] ?: ""),
                    "quickIssues" to (data["quickIssues"] ?: emptyList<Any>())
                ))
            } else {
                ResponseEntity.badRequest().body(mapOf<String, Any>(
                    "success" to false,
                    "error" to (result.error ?: "Unknown error")
                ))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to analyze files for MR !$mrId" }
            ResponseEntity.internalServerError().body(mapOf<String, Any>(
                "success" to false,
                "error" to "파일 분석 실패: ${e.message}"
            ))
        }
    }

    /**
     * MR 빠른 이슈 감지 (Pass 1만 실행)
     *
     * 보안, Breaking Change, 네이밍 불일치 등을 빠르게 감지합니다.
     *
     * @param project GitLab 프로젝트 경로
     * @param mrId MR 번호
     * @return 감지된 이슈 목록
     */
    @GetMapping("/issues/{project}/{mrId}")
    fun detectIssues(
        @PathVariable project: String,
        @PathVariable mrId: Int
    ): ResponseEntity<Map<String, Any>> {
        logger.info { "Detecting issues for MR !$mrId in project $project" }

        val gitlabPlugin = pluginRegistry.get("gitlab") as? GitLabPlugin
        if (gitlabPlugin == null) {
            return ResponseEntity.badRequest().body(mapOf<String, Any>(
                "success" to false,
                "error" to "GitLab 플러그인이 초기화되지 않았습니다"
            ))
        }

        return try {
            val result = runBlocking {
                gitlabPlugin.execute("mr-review", mapOf(
                    "project" to project,
                    "mr_id" to mrId
                ))
            }

            if (result.success && result.data != null) {
                @Suppress("UNCHECKED_CAST")
                val data = result.data as Map<String, Any>
                val quickIssues = data["quickIssues"] as? List<Map<String, Any>> ?: emptyList()

                val groupedIssues = quickIssues.groupBy { it["severity"] as? String ?: "INFO" }

                ResponseEntity.ok(mapOf<String, Any>(
                    "success" to true,
                    "totalIssues" to quickIssues.size,
                    "errors" to (groupedIssues["ERROR"] ?: emptyList<Any>()),
                    "warnings" to (groupedIssues["WARNING"] ?: emptyList<Any>()),
                    "info" to (groupedIssues["INFO"] ?: emptyList<Any>())
                ))
            } else {
                ResponseEntity.badRequest().body(mapOf<String, Any>(
                    "success" to false,
                    "error" to (result.error ?: "Unknown error")
                ))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to detect issues for MR !$mrId" }
            ResponseEntity.internalServerError().body(mapOf<String, Any>(
                "success" to false,
                "error" to "이슈 감지 실패: ${e.message}"
            ))
        }
    }

    /**
     * MR 리뷰 프롬프트 생성 (AI 리뷰용)
     *
     * Claude/GPT에게 전달할 수 있는 구조화된 리뷰 프롬프트를 생성합니다.
     *
     * @param project GitLab 프로젝트 경로
     * @param mrId MR 번호
     * @return 리뷰 프롬프트
     */
    @GetMapping("/prompt/{project}/{mrId}")
    fun generatePrompt(
        @PathVariable project: String,
        @PathVariable mrId: Int
    ): ResponseEntity<Map<String, Any>> {
        logger.info { "Generating review prompt for MR !$mrId in project $project" }

        val gitlabPlugin = pluginRegistry.get("gitlab") as? GitLabPlugin
        if (gitlabPlugin == null) {
            return ResponseEntity.badRequest().body(mapOf<String, Any>(
                "success" to false,
                "error" to "GitLab 플러그인이 초기화되지 않았습니다"
            ))
        }

        return try {
            val result = runBlocking {
                gitlabPlugin.execute("mr-review", mapOf(
                    "project" to project,
                    "mr_id" to mrId
                ))
            }

            if (result.success && result.data != null) {
                @Suppress("UNCHECKED_CAST")
                val data = result.data as Map<String, Any>
                val reviewPrompt = data["review_prompt"] as? String ?: ""
                val priorityFiles = data["priorityFiles"] as? List<String> ?: emptyList<String>()

                ResponseEntity.ok(mapOf<String, Any>(
                    "success" to true,
                    "prompt" to reviewPrompt,
                    "priorityFiles" to priorityFiles,
                    "mrUrl" to ((data["mr"] as? Map<*, *>)?.get("web_url") ?: "")
                ))
            } else {
                ResponseEntity.badRequest().body(mapOf<String, Any>(
                    "success" to false,
                    "error" to (result.error ?: "Unknown error")
                ))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate prompt for MR !$mrId" }
            ResponseEntity.internalServerError().body(mapOf<String, Any>(
                "success" to false,
                "error" to "프롬프트 생성 실패: ${e.message}"
            ))
        }
    }
}
