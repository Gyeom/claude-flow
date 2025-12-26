package ai.claudeflow.api.rest

import ai.claudeflow.core.knowledge.FigmaApiSpecService
import ai.claudeflow.core.knowledge.ScreenApiSpec
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
    private val pluginRegistry: PluginRegistry,
    private val figmaApiSpecService: FigmaApiSpecService?
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

    // ==================== Design-Aware Code Review ====================

    /**
     * Design-Aware MR 리뷰 (기획서 스펙 연동)
     *
     * MR 변경사항과 관련된 Figma 기획서 API 스펙을 검색하고
     * 기획서 준수 여부를 함께 리뷰합니다.
     *
     * @param project GitLab 프로젝트 경로
     * @param mrId MR 번호
     * @param projectId 프로젝트 ID (기획서 검색 필터용)
     * @return 리뷰 결과 + 관련 기획서 스펙
     */
    @GetMapping("/design-aware/{project}/{mrId}")
    fun designAwareReview(
        @PathVariable project: String,
        @PathVariable mrId: Int,
        @RequestParam(required = false) projectId: String?
    ): ResponseEntity<Map<String, Any>> {
        logger.info { "Design-aware review for MR !$mrId in project $project (projectId: $projectId)" }

        val gitlabPlugin = pluginRegistry.get("gitlab") as? GitLabPlugin
        if (gitlabPlugin == null) {
            return ResponseEntity.badRequest().body(mapOf<String, Any>(
                "success" to false,
                "error" to "GitLab 플러그인이 초기화되지 않았습니다"
            ))
        }

        return runBlocking {
            try {
                // 1. MR 분석 수행
                val result = gitlabPlugin.execute("mr-review", mapOf(
                    "project" to project,
                    "mr_id" to mrId
                ))

                if (!result.success || result.data == null) {
                    return@runBlocking ResponseEntity.badRequest().body(mapOf<String, Any>(
                        "success" to false,
                        "error" to (result.error ?: "MR 분석 실패")
                    ))
                }

                @Suppress("UNCHECKED_CAST")
                val mrData = result.data as Map<String, Any>

                // 2. Design Spec 검색 (FigmaApiSpecService 사용)
                val designSpecs = mutableListOf<ScreenApiSpec>()
                if (figmaApiSpecService != null) {
                    // MR 제목, 변경 파일 경로로 관련 기획서 검색
                    val mrTitle = (mrData["mr"] as? Map<*, *>)?.get("title") as? String ?: ""
                    val priorityFiles = mrData["priorityFiles"] as? List<*> ?: emptyList<String>()

                    // 제목으로 검색
                    val titleSpecs = figmaApiSpecService.searchApiSpecs(
                        query = mrTitle,
                        projectId = projectId,
                        topK = 3
                    )
                    designSpecs.addAll(titleSpecs)

                    // 파일 경로 키워드로 검색 (예: auth, login, user 등)
                    val keywords = extractKeywordsFromFiles(priorityFiles.filterIsInstance<String>())
                    for (keyword in keywords.take(3)) {
                        val keywordSpecs = figmaApiSpecService.searchApiSpecs(
                            query = keyword,
                            projectId = projectId,
                            topK = 2
                        )
                        designSpecs.addAll(keywordSpecs)
                    }

                    logger.info { "Found ${designSpecs.size} related design specs for MR !$mrId" }
                }

                // 3. 결과 통합
                val response = mrData.toMutableMap()
                response["designSpecs"] = designSpecs.distinctBy { it.screenId }.map { spec ->
                    mapOf(
                        "screenId" to spec.screenId,
                        "screenName" to spec.screenName,
                        "imageUrl" to spec.imageUrl,
                        "apis" to spec.apis.map { api ->
                            mapOf(
                                "method" to api.method,
                                "path" to api.path,
                                "description" to api.description
                            )
                        },
                        "businessRules" to spec.businessRules,
                        "validations" to spec.validations.map { v ->
                            mapOf(
                                "field" to v.field,
                                "rules" to v.rules
                            )
                        }
                    )
                }
                response["designAware"] = figmaApiSpecService != null
                response["designSpecsCount"] = designSpecs.distinctBy { it.screenId }.size

                ResponseEntity.ok(mapOf<String, Any>(
                    "success" to true,
                    "data" to response
                ))

            } catch (e: Exception) {
                logger.error(e) { "Failed design-aware review for MR !$mrId" }
                ResponseEntity.internalServerError().body(mapOf<String, Any>(
                    "success" to false,
                    "error" to "Design-aware 리뷰 실패: ${e.message}"
                ))
            }
        }
    }

    /**
     * 파일 경로에서 검색 키워드 추출
     * 예: src/auth/LoginController.kt -> ["auth", "login"]
     */
    private fun extractKeywordsFromFiles(filePaths: List<String>): List<String> {
        val keywords = mutableSetOf<String>()

        for (path in filePaths) {
            // 경로와 파일명 분리
            val parts = path.lowercase()
                .replace("\\", "/")
                .split("/")
                .flatMap { it.split("_", "-", ".") }
                .filter { it.length in 3..20 }
                .filter { it !in listOf("src", "main", "kotlin", "java", "test", "impl", "controller", "service", "repository", "kt", "java", "ts", "tsx", "js") }

            keywords.addAll(parts)
        }

        return keywords.toList().take(10)
    }
}
