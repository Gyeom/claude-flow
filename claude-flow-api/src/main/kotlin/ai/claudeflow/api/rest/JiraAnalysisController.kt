package ai.claudeflow.api.rest

import ai.claudeflow.core.plugin.PluginManager
import ai.claudeflow.executor.ClaudeExecutor
import ai.claudeflow.executor.ExecutionRequest
import ai.claudeflow.executor.ExecutionStatus
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * Jira 이슈 분석 API (Claude 연동)
 *
 * - 이슈 분석 및 구현 방향 제안
 * - 코드 리뷰 연결
 * - 자동 요약 생성
 */
@RestController
@RequestMapping("/api/v1/jira")
class JiraAnalysisController(
    private val pluginManager: PluginManager,
    private val claudeExecutor: ClaudeExecutor
) {

    /**
     * 이슈 분석 - Claude가 이슈를 분석하고 구현 방향 제안
     */
    @PostMapping("/analyze/{issueKey}")
    fun analyzeIssue(
        @PathVariable issueKey: String,
        @RequestBody request: AnalyzeRequest?
    ): Mono<ResponseEntity<AnalyzeResponse>> = mono {
        logger.info { "Analyzing issue: $issueKey" }

        // 1. Jira에서 이슈 정보 가져오기
        val issueResult = pluginManager.execute("jira", "issue", mapOf("issue_key" to issueKey))

        if (!issueResult.success) {
            return@mono ResponseEntity.ok(AnalyzeResponse(
                success = false,
                error = issueResult.error ?: "Failed to fetch issue"
            ))
        }

        val issueData = issueResult.data as? Map<*, *> ?: return@mono ResponseEntity.ok(
            AnalyzeResponse(success = false, error = "Invalid issue data")
        )

        // 2. Claude에게 분석 요청
        val prompt = buildAnalysisPrompt(issueData, request?.context)

        try {
            val result = claudeExecutor.execute(ExecutionRequest(
                prompt = prompt,
                workingDirectory = request?.projectPath ?: System.getProperty("user.dir"),
                model = "claude-sonnet-4-20250514"
            ))

            val isSuccess = result.status == ExecutionStatus.SUCCESS

            // 3. 분석 결과를 Jira 댓글로 추가 (옵션)
            if (request?.addComment == true && isSuccess) {
                val commentText = "🤖 **AI Analysis**\n\n${result.result}"
                pluginManager.execute("jira", "comment", mapOf(
                    "issue_key" to issueKey,
                    "comment" to commentText
                ))
            }

            ResponseEntity.ok(AnalyzeResponse(
                success = isSuccess,
                analysis = result.result,
                issueKey = issueKey,
                issueSummary = issueData["summary"] as? String,
                tokensUsed = result.usage?.let { it.inputTokens + it.outputTokens }
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to analyze issue: $issueKey" }
            ResponseEntity.ok(AnalyzeResponse(
                success = false,
                error = e.message
            ))
        }
    }

    /**
     * 이슈 → 코드 연결 분석
     * 이슈 내용을 기반으로 관련 코드 파일 찾기
     */
    @PostMapping("/analyze/{issueKey}/code-context")
    fun analyzeCodeContext(
        @PathVariable issueKey: String,
        @RequestBody request: CodeContextRequest
    ): Mono<ResponseEntity<CodeContextResponse>> = mono {
        logger.info { "Analyzing code context for issue: $issueKey" }

        // 1. 이슈 정보 가져오기
        val issueResult = pluginManager.execute("jira", "issue", mapOf("issue_key" to issueKey))

        if (!issueResult.success) {
            return@mono ResponseEntity.ok(CodeContextResponse(
                success = false,
                error = issueResult.error
            ))
        }

        val issueData = issueResult.data as? Map<*, *> ?: return@mono ResponseEntity.ok(
            CodeContextResponse(success = false, error = "Invalid issue data")
        )

        // 2. Claude에게 코드 탐색 요청
        val prompt = """
            |프로젝트에서 다음 Jira 이슈와 관련된 코드를 찾아주세요:
            |
            |**이슈**: ${issueData["key"]} - ${issueData["summary"]}
            |**타입**: ${issueData["issuetype"]}
            |**설명**: ${issueData["description"] ?: "없음"}
            |
            |다음을 수행해주세요:
            |1. 관련된 파일들을 찾아 나열
            |2. 각 파일이 이슈와 어떻게 관련되는지 설명
            |3. 수정이 필요한 부분 식별
            |4. 의존성 관계 파악
            |
            |결과를 JSON 형식으로 반환해주세요:
            |```json
            |{
            |  "relatedFiles": ["path/to/file.kt", ...],
            |  "analysis": "분석 결과...",
            |  "suggestedChanges": ["변경1", "변경2", ...]
            |}
            |```
        """.trimMargin()

        try {
            val result = claudeExecutor.execute(ExecutionRequest(
                prompt = prompt,
                workingDirectory = request.projectPath,
                model = "claude-sonnet-4-20250514"
            ))

            ResponseEntity.ok(CodeContextResponse(
                success = result.status == ExecutionStatus.SUCCESS,
                issueKey = issueKey,
                analysis = result.result,
                projectPath = request.projectPath
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to analyze code context for: $issueKey" }
            ResponseEntity.ok(CodeContextResponse(
                success = false,
                error = e.message
            ))
        }
    }

    /**
     * 스프린트 리포트 생성
     */
    @PostMapping("/sprint-report")
    fun generateSprintReport(
        @RequestBody request: SprintReportRequest?
    ): Mono<ResponseEntity<SprintReportResponse>> = mono {
        logger.info { "Generating sprint report" }

        // 1. 스프린트 이슈 가져오기
        val sprintResult = pluginManager.execute("jira", "sprint",
            request?.boardId?.let { mapOf("board_id" to it) } ?: emptyMap()
        )

        if (!sprintResult.success) {
            return@mono ResponseEntity.ok(SprintReportResponse(
                success = false,
                error = sprintResult.error
            ))
        }

        val issues = sprintResult.data as? List<*> ?: emptyList<Any>()

        // 2. 상태별 분류
        val byStatus = issues.filterIsInstance<Map<*, *>>().groupBy { it["status"] as? String ?: "Unknown" }

        val statusSummary = byStatus.map { (status, list) ->
            "$status: ${list.size}개"
        }.joinToString(", ")

        // 3. Claude에게 리포트 생성 요청
        val prompt = """
            |다음 스프린트 이슈들을 분석하고 리포트를 작성해주세요:
            |
            |**이슈 현황**: 총 ${issues.size}개
            |$statusSummary
            |
            |**상세 이슈 목록**:
            |${issues.filterIsInstance<Map<*, *>>().joinToString("\n") { issue ->
                "- [${issue["key"]}] ${issue["summary"]} (${issue["status"]}, ${issue["assignee"] ?: "미배정"})"
            }}
            |
            |다음 내용을 포함한 스프린트 리포트를 작성해주세요:
            |1. 전체 진행 상황 요약
            |2. 주요 성과
            |3. 블로커 또는 지연 사항
            |4. 다음 스프린트를 위한 제안
            |
            |마크다운 형식으로 작성해주세요.
        """.trimMargin()

        try {
            val result = claudeExecutor.execute(ExecutionRequest(
                prompt = prompt,
                workingDirectory = System.getProperty("user.dir"),
                model = "claude-sonnet-4-20250514"
            ))

            ResponseEntity.ok(SprintReportResponse(
                success = result.status == ExecutionStatus.SUCCESS,
                report = result.result,
                totalIssues = issues.size,
                byStatus = byStatus.mapValues { it.value.size }.mapKeys { it.key ?: "Unknown" }
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate sprint report" }
            ResponseEntity.ok(SprintReportResponse(
                success = false,
                error = e.message
            ))
        }
    }

    /**
     * 이슈 자동 분류/라벨링
     */
    @PostMapping("/auto-label/{issueKey}")
    fun autoLabelIssue(
        @PathVariable issueKey: String
    ): Mono<ResponseEntity<AutoLabelResponse>> = mono {
        logger.info { "Auto-labeling issue: $issueKey" }

        // 1. 이슈 정보 가져오기
        val issueResult = pluginManager.execute("jira", "issue", mapOf("issue_key" to issueKey))

        if (!issueResult.success) {
            return@mono ResponseEntity.ok(AutoLabelResponse(
                success = false,
                error = issueResult.error
            ))
        }

        val issueData = issueResult.data as? Map<*, *> ?: return@mono ResponseEntity.ok(
            AutoLabelResponse(success = false, error = "Invalid issue data")
        )

        // 2. Claude에게 라벨 추천 요청
        val prompt = """
            |다음 이슈에 적합한 라벨을 추천해주세요:
            |
            |**이슈**: ${issueData["key"]} - ${issueData["summary"]}
            |**타입**: ${issueData["issuetype"]}
            |**설명**: ${issueData["description"] ?: "없음"}
            |
            |사용 가능한 라벨 카테고리:
            |- 영역: frontend, backend, database, infra, docs
            |- 복잡도: simple, moderate, complex
            |- 긴급도: critical, high, normal, low
            |- AI 처리: ai:auto-fix, ai:needs-review, ai:analyzed
            |
            |JSON 형식으로 추천 라벨을 반환해주세요:
            |```json
            |{
            |  "labels": ["label1", "label2"],
            |  "reasoning": "추천 이유..."
            |}
            |```
        """.trimMargin()

        try {
            val result = claudeExecutor.execute(ExecutionRequest(
                prompt = prompt,
                workingDirectory = System.getProperty("user.dir"),
                model = "claude-sonnet-4-20250514"
            ))

            // JSON 파싱 시도
            val suggestedLabels = extractLabelsFromResponse(result.result ?: "")

            // 라벨 추가 (옵션)
            suggestedLabels.forEach { label ->
                pluginManager.execute("jira", "labels", mapOf(
                    "issue_key" to issueKey,
                    "action" to "add",
                    "label" to label
                ))
            }

            ResponseEntity.ok(AutoLabelResponse(
                success = result.status == ExecutionStatus.SUCCESS,
                issueKey = issueKey,
                suggestedLabels = suggestedLabels,
                analysis = result.result
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to auto-label issue: $issueKey" }
            ResponseEntity.ok(AutoLabelResponse(
                success = false,
                error = e.message
            ))
        }
    }

    // ==================== Helper Functions ====================

    private fun buildAnalysisPrompt(issueData: Map<*, *>, additionalContext: String?): String {
        return """
            |Jira 이슈를 분석하고 구현 방향을 제안해주세요:
            |
            |## 이슈 정보
            |**키**: ${issueData["key"]}
            |**제목**: ${issueData["summary"]}
            |**타입**: ${issueData["issuetype"]}
            |**우선순위**: ${issueData["priority"]}
            |**담당자**: ${issueData["assignee"] ?: "미배정"}
            |**리포터**: ${issueData["reporter"]}
            |
            |## 설명
            |${issueData["description"] ?: "설명 없음"}
            |
            |${additionalContext?.let { "## 추가 컨텍스트\n$it" } ?: ""}
            |
            |## 요청 사항
            |다음 내용을 포함해서 분석해주세요:
            |
            |1. **요구사항 분석**: 이슈의 핵심 요구사항을 정리
            |2. **구현 접근 방식**: 권장하는 구현 방법 제안
            |3. **작업 분해**: 구체적인 작업 단계 (체크리스트 형태)
            |4. **예상 영향 범위**: 영향받을 수 있는 컴포넌트/파일
            |5. **위험 요소**: 주의해야 할 점이나 잠재적 이슈
            |6. **테스트 전략**: 검증 방법 제안
            |
            |마크다운 형식으로 깔끔하게 정리해주세요.
        """.trimMargin()
    }

    private fun extractLabelsFromResponse(response: String): List<String> {
        // JSON 블록에서 labels 배열 추출 시도
        val jsonRegex = """```json\s*(\{[\s\S]*?})\s*```""".toRegex()
        val match = jsonRegex.find(response)

        if (match != null) {
            try {
                val jsonStr = match.groupValues[1]
                // 간단한 파싱 (labels 배열 추출)
                val labelsMatch = """"labels"\s*:\s*\[(.*?)]""".toRegex().find(jsonStr)
                if (labelsMatch != null) {
                    return labelsMatch.groupValues[1]
                        .split(",")
                        .map { it.trim().removeSurrounding("\"") }
                        .filter { it.isNotBlank() }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to parse labels from response" }
            }
        }

        return emptyList()
    }
}

// ==================== DTOs ====================

data class AnalyzeRequest(
    val context: String? = null,
    val projectPath: String? = null,
    val addComment: Boolean = false
)

data class AnalyzeResponse(
    val success: Boolean,
    val analysis: String? = null,
    val issueKey: String? = null,
    val issueSummary: String? = null,
    val tokensUsed: Int? = null,
    val error: String? = null
)

data class CodeContextRequest(
    val projectPath: String
)

data class CodeContextResponse(
    val success: Boolean,
    val issueKey: String? = null,
    val analysis: String? = null,
    val projectPath: String? = null,
    val error: String? = null
)

data class SprintReportRequest(
    val boardId: Int? = null
)

data class SprintReportResponse(
    val success: Boolean,
    val report: String? = null,
    val totalIssues: Int? = null,
    val byStatus: Map<String, Int>? = null,
    val error: String? = null
)

data class AutoLabelResponse(
    val success: Boolean,
    val issueKey: String? = null,
    val suggestedLabels: List<String>? = null,
    val analysis: String? = null,
    val error: String? = null
)
