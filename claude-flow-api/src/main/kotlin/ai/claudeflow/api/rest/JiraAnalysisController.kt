package ai.claudeflow.api.rest

import ai.claudeflow.core.plugin.PluginManager
import ai.claudeflow.executor.ClaudeExecutor
import ai.claudeflow.executor.ExecutionRequest
import ai.claudeflow.executor.ExecutionStatus
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.reactor.mono
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

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
    companion object {
        /**
         * JQL 파싱 결과 (캐시용)
         */
        data class CachedJqlResponse(
            val jql: String,
            val explanation: String?,
            val confidence: Double,
            val warnings: List<String>?
        )

        /**
         * JQL 변환 캐시 - 유사한 쿼리 결과를 5분간 캐싱하여 토큰 비용 절감
         */
        private val jqlCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build<String, CachedJqlResponse>()

        /**
         * 이슈 필드 제안 (캐시용)
         */
        data class CachedIssueFieldSuggestion(
            val summary: String,
            val description: String,
            val issueType: String,
            val priority: String,
            val labels: List<String>?
        )

        /**
         * 이슈 텍스트 분석 캐시 - 유사한 텍스트 결과를 5분간 캐싱
         */
        private val issueTextCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build<String, CachedIssueFieldSuggestion>()

        /**
         * 정적 시스템 프롬프트: JQL 변환 규칙 (캐시 가능)
         * Anthropic API의 system 필드에 전달하면 캐싱 혜택을 받음
         */
        val JQL_SYSTEM_PROMPT = """
            |당신은 Jira JQL 변환 전문가입니다. 사용자의 자연어 검색 요청을 정확한 JQL로 변환해주세요.
            |
            |## JQL 변환 규칙
            |
            |### 프로젝트 (project)
            |- 프로젝트 키가 언급되면 해당 프로젝트로 필터링
            |- "XX 프로젝트", "XX 티켓", "XX 이슈" 패턴 → project = XX (대문자로 변환)
            |- 예: "dc 티켓 조회해줘" → project = DC
            |- 예: "proj 이슈" → project = PROJ
            |- 예: "mpa 관련" → project = MPA
            |- **중요**: 사용자가 입력한 프로젝트 키를 그대로 대문자로 변환하여 사용. 유사한 다른 프로젝트로 추측하거나 변경하지 말 것
            |- 프로젝트 키는 정확히 사용자가 말한 것만 사용 (proj → PROJ)
            |
            |### 담당자 (assignee)
            |- "내 이슈", "나한테 할당된", "내가 담당" → assignee = currentUser()
            |- "미할당", "담당자 없는" → assignee is EMPTY
            |- "홍길동이 담당" → assignee = "홍길동"
            |
            |### 상태 (status)
            |- "진행중", "작업중" → status = "In Progress"
            |- "완료", "끝난", "해결된" → status = "Done" OR status = "Resolved"
            |- "할일", "해야할" → status = "To Do"
            |- "리뷰", "검토중" → status = "In Review"
            |- "백로그" → status = "Backlog"
            |
            |### 이슈 타입 (issuetype)
            |- "버그", "오류" → issuetype = Bug
            |- "스토리", "기능" → issuetype = Story
            |- "작업", "태스크" → issuetype = Task
            |- "에픽" → issuetype = Epic
            |
            |### 우선순위 (priority)
            |- "긴급", "높은 우선순위" → priority in (Highest, High)
            |- "낮은 우선순위" → priority in (Low, Lowest)
            |
            |### 날짜 (created, updated)
            |- "오늘" → created >= startOfDay()
            |- "이번주" → created >= startOfWeek()
            |- "이번달" → created >= startOfMonth()
            |- "지난 7일", "최근 일주일" → created >= -7d
            |- "지난달" → created >= -30d
            |
            |### 텍스트 검색
            |- "로그인 관련" → text ~ "로그인"
            |- "API 에러" → text ~ "API 에러"
            |
            |### 리포터 (reporter)
            |- "내가 등록한", "내가 만든" → reporter = currentUser()
            |
            |## 응답 형식
            |반드시 다음 JSON 형식으로만 응답하세요:
            |```json
            |{
            |  "jql": "변환된 JQL 쿼리 (ORDER BY updated DESC 포함)",
            |  "explanation": "변환 설명 (한국어)",
            |  "confidence": 0.0-1.0 사이의 신뢰도,
            |  "warnings": ["주의사항이 있으면 여기에"]
            |}
            |```
            |
            |주의: JSON 외의 다른 텍스트를 출력하지 마세요.
        """.trimMargin()

        /**
         * 정적 시스템 프롬프트: 이슈 텍스트 분석 규칙 (캐시 가능)
         */
        val ISSUE_TEXT_SYSTEM_PROMPT = """
            |당신은 Jira 이슈 생성을 도와주는 AI 어시스턴트입니다.
            |사용자가 입력한 자연어 텍스트를 분석하여 적절한 Jira 이슈 필드를 제안해주세요.
            |
            |## 분석 지침
            |
            |### 이슈 타입 판단 기준
            |- **Bug**: 오류, 버그, 안됨, 동작하지 않음, 크래시, 에러, 문제 발생
            |- **Story**: 기능 추가, 새 기능, ~하고 싶다, ~하면 좋겠다, 요청
            |- **Task**: 작업, 구현, 개발, 수정, 변경, 업데이트, 리팩토링
            |- **Epic**: 대규모 기능, 프로젝트, 전체 개편
            |
            |### 우선순위 판단 기준
            |- **Highest**: 긴급, 장애, 서비스 불가, 즉시, critical, blocker
            |- **High**: 중요, 심각, 빠른 대응 필요
            |- **Medium**: 일반적인 요청, 개선 사항
            |- **Low**: 낮은 우선순위, 나중에, 시간날 때
            |- **Lowest**: 아이디어, 제안, 검토 필요
            |
            |## 응답 형식
            |반드시 다음 JSON 형식으로만 응답하세요:
            |```json
            |{
            |  "summary": "이슈 제목 (간결하고 명확하게, 50자 이내)",
            |  "description": "이슈 상세 설명 (Markdown 형식, 문제/배경/예상결과 포함)",
            |  "issueType": "Bug|Story|Task|Epic 중 하나",
            |  "priority": "Highest|High|Medium|Low|Lowest 중 하나",
            |  "labels": ["관련 라벨들", "최대 3개"]
            |}
            |```
            |
            |주의:
            |- JSON 외의 다른 텍스트를 출력하지 마세요
            |- summary는 명사형으로 간결하게 작성
            |- description은 구조화된 형태로 작성 (## 섹션 사용)
        """.trimMargin()
    }

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

        // 2. Claude에게 분석 요청 (비동기 실행)
        val prompt = buildAnalysisPrompt(issueData, request?.context)

        try {
            val result = claudeExecutor.executeAsync(ExecutionRequest(
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
            // 비동기 실행
            val result = claudeExecutor.executeAsync(ExecutionRequest(
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
            // 비동기 실행
            val result = claudeExecutor.executeAsync(ExecutionRequest(
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
     * 자연어 → JQL 변환 (Claude 기반)
     *
     * 사용자의 자연어 검색 쿼리를 JQL로 변환합니다.
     * 예: "내가 진행중인 PROJ 버그" → project = PROJ AND assignee = currentUser() AND status = "In Progress" AND issuetype = Bug
     *
     * 성능 최적화:
     * - JQL 캐시: 동일한 쿼리는 5분간 캐싱
     * - 시스템 프롬프트 분리: Anthropic API 캐싱 혜택
     * - 비동기 실행: executeAsync() 사용
     */
    @PostMapping("/nl-to-jql")
    fun convertNaturalLanguageToJql(
        @RequestBody request: NlToJqlRequest
    ): Mono<ResponseEntity<NlToJqlResponse>> = mono {
        logger.info { "Converting natural language to JQL: ${request.query}" }

        // 1. 캐시 확인 - 동일한 쿼리는 캐시에서 반환
        val cacheKey = request.query.trim().lowercase()
        val cachedResponse = jqlCache.getIfPresent(cacheKey)
        if (cachedResponse != null) {
            logger.debug { "JQL cache hit: $cacheKey" }
            return@mono ResponseEntity.ok(NlToJqlResponse(
                success = true,
                jql = cachedResponse.jql,
                explanation = cachedResponse.explanation,
                confidence = cachedResponse.confidence,
                warnings = cachedResponse.warnings,
                cached = true  // 캐시에서 반환됨을 표시
            ))
        }

        // 2. 사용 가능한 프로젝트 목록 가져오기 (컨텍스트 제공)
        val projectsInfo = if (request.includeProjects == true) {
            val projectsResult = pluginManager.execute("jira", "projects", emptyMap())
            if (projectsResult.success) {
                val projects = projectsResult.data as? List<*>
                projects?.mapNotNull { (it as? Map<*, *>)?.get("key") as? String }?.take(20)?.joinToString(", ")
            } else null
        } else null

        // 3. 사용자 프롬프트만 구성 (시스템 프롬프트는 분리됨)
        val userPrompt = """
            |## 자연어 쿼리
            |"${request.query}"
            |
            |${projectsInfo?.let { "## 사용 가능한 프로젝트\n$it\n" } ?: ""}
        """.trimMargin()

        try {
            // 4. 비동기 실행 + 시스템 프롬프트 분리
            val result = claudeExecutor.executeAsync(ExecutionRequest(
                prompt = userPrompt,
                systemPrompt = JQL_SYSTEM_PROMPT,  // 캐시 가능한 시스템 프롬프트
                workingDirectory = System.getProperty("user.dir"),
                model = "claude-sonnet-4-20250514"
            ))

            val response = parseJqlResponse(result.result ?: "")

            // 5. 성공 시 캐시에 저장
            if (response != null) {
                jqlCache.put(cacheKey, CachedJqlResponse(
                    jql = response.jql,
                    explanation = response.explanation,
                    confidence = response.confidence,
                    warnings = response.warnings
                ))
                logger.debug { "JQL cached: $cacheKey -> ${response.jql}" }
            }

            ResponseEntity.ok(NlToJqlResponse(
                success = response != null,
                jql = response?.jql,
                explanation = response?.explanation,
                confidence = response?.confidence ?: 0.0,
                warnings = response?.warnings,
                error = if (response == null) "Failed to parse LLM response" else null
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to convert NL to JQL: ${request.query}" }
            ResponseEntity.ok(NlToJqlResponse(
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
            // 비동기 실행
            val result = claudeExecutor.executeAsync(ExecutionRequest(
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

    /**
     * 자연어 텍스트를 분석하여 이슈 필드 제안
     * 예: "로그인 페이지에서 비밀번호 입력 후 Enter 키가 안 먹어요" → Bug, High, 제목, 설명 생성
     *
     * 성능 최적화:
     * - 이슈 텍스트 캐시: 동일한 텍스트는 5분간 캐싱
     * - 시스템 프롬프트 분리: Anthropic API 캐싱 혜택
     * - 비동기 실행: executeAsync() 사용
     */
    @PostMapping("/analyze-text")
    fun analyzeTextForIssue(
        @RequestBody request: AnalyzeTextRequest
    ): Mono<ResponseEntity<AnalyzeTextResponse>> = mono {
        logger.info { "Analyzing text for issue creation: ${request.text.take(100)}..." }

        // 1. 캐시 확인 - 동일한 텍스트는 캐시에서 반환
        val cacheKey = request.text.trim().lowercase()
        val cachedResponse = issueTextCache.getIfPresent(cacheKey)
        if (cachedResponse != null) {
            logger.debug { "Issue text cache hit: ${cacheKey.take(50)}..." }
            return@mono ResponseEntity.ok(AnalyzeTextResponse(
                success = true,
                data = IssueFieldSuggestion(
                    summary = cachedResponse.summary,
                    description = cachedResponse.description,
                    issueType = cachedResponse.issueType,
                    priority = cachedResponse.priority,
                    labels = cachedResponse.labels
                ),
                cached = true
            ))
        }

        // 2. 사용자 프롬프트만 구성 (시스템 프롬프트는 분리됨)
        val userPrompt = """
            |## 사용자 입력
            |"${request.text}"
        """.trimMargin()

        try {
            // 3. 비동기 실행 + 시스템 프롬프트 분리
            val result = claudeExecutor.executeAsync(ExecutionRequest(
                prompt = userPrompt,
                systemPrompt = ISSUE_TEXT_SYSTEM_PROMPT,  // 캐시 가능한 시스템 프롬프트
                workingDirectory = System.getProperty("user.dir"),
                model = "claude-sonnet-4-20250514"
            ))

            val response = parseIssueTextResponse(result.result ?: "")

            // 4. 성공 시 캐시에 저장
            if (response != null) {
                issueTextCache.put(cacheKey, CachedIssueFieldSuggestion(
                    summary = response.summary,
                    description = response.description,
                    issueType = response.issueType,
                    priority = response.priority,
                    labels = response.labels
                ))
                logger.debug { "Issue text cached: ${cacheKey.take(50)}..." }
            }

            ResponseEntity.ok(AnalyzeTextResponse(
                success = response != null,
                data = response,
                error = if (response == null) "Failed to parse AI response" else null
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to analyze text: ${request.text.take(100)}" }
            ResponseEntity.ok(AnalyzeTextResponse(
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

    // JSON 파서 (lenient 모드 - Claude 응답의 유연한 파싱)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Serializable
    private data class JqlJsonResponse(
        val jql: String,
        val explanation: String? = null,
        val confidence: Double = 0.8,
        val warnings: List<String> = emptyList()
    )

    private fun parseJqlResponse(response: String): ParsedJqlResponse? {
        // JSON 블록에서 JQL 응답 추출
        val jsonRegex = """```json\s*(\{[\s\S]*?})\s*```""".toRegex()
        val match = jsonRegex.find(response)

        val jsonStr = match?.groupValues?.get(1) ?: response.trim().let {
            if (it.startsWith("{")) it else return null
        }

        return try {
            // kotlinx.serialization으로 JSON 파싱
            val parsed = json.decodeFromString<JqlJsonResponse>(jsonStr)

            ParsedJqlResponse(
                jql = parsed.jql,
                explanation = parsed.explanation,
                confidence = parsed.confidence,
                warnings = parsed.warnings.takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            logger.warn { "Failed to parse JQL response with JSON parser: ${e.message}, trying regex fallback" }
            // Fallback to regex parsing
            parseJqlResponseWithRegex(jsonStr)
        }
    }

    private fun parseJqlResponseWithRegex(jsonStr: String): ParsedJqlResponse? {
        return try {
            val jqlMatch = """"jql"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex().find(jsonStr)
            val explanationMatch = """"explanation"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex().find(jsonStr)
            val confidenceMatch = """"confidence"\s*:\s*([\d.]+)""".toRegex().find(jsonStr)
            val warningsMatch = """"warnings"\s*:\s*\[(.*?)]""".toRegex().find(jsonStr)

            val warnings = warningsMatch?.groupValues?.get(1)
                ?.split(",")
                ?.map { it.trim().removeSurrounding("\"") }
                ?.filter { it.isNotBlank() }

            val jql = jqlMatch?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?: return null

            ParsedJqlResponse(
                jql = jql,
                explanation = explanationMatch?.groupValues?.get(1)?.replace("\\\"", "\""),
                confidence = confidenceMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.8,
                warnings = warnings
            )
        } catch (e: Exception) {
            logger.warn { "Failed to parse JQL response with regex: ${e.message}" }
            null
        }
    }

    private data class ParsedJqlResponse(
        val jql: String,
        val explanation: String?,
        val confidence: Double,
        val warnings: List<String>?
    )

    @Serializable
    private data class IssueTextJsonResponse(
        val summary: String,
        val description: String = "",
        val issueType: String = "Task",
        val priority: String = "Medium",
        val labels: List<String> = emptyList()
    )

    private fun parseIssueTextResponse(response: String): IssueFieldSuggestion? {
        // JSON 블록에서 응답 추출
        val jsonRegex = """```json\s*(\{[\s\S]*?})\s*```""".toRegex()
        val match = jsonRegex.find(response)

        val jsonStr = match?.groupValues?.get(1) ?: response.trim().let {
            if (it.startsWith("{")) it else return null
        }

        return try {
            val parsed = json.decodeFromString<IssueTextJsonResponse>(jsonStr)
            IssueFieldSuggestion(
                summary = parsed.summary,
                description = parsed.description,
                issueType = parsed.issueType,
                priority = parsed.priority,
                labels = parsed.labels.takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            logger.warn { "Failed to parse issue text response: ${e.message}" }
            // Fallback to regex
            parseIssueTextResponseWithRegex(jsonStr)
        }
    }

    private fun parseIssueTextResponseWithRegex(jsonStr: String): IssueFieldSuggestion? {
        return try {
            val summaryMatch = """"summary"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex().find(jsonStr)
            val descMatch = """"description"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex().find(jsonStr)
            val typeMatch = """"issueType"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex().find(jsonStr)
            val priorityMatch = """"priority"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex().find(jsonStr)
            val labelsMatch = """"labels"\s*:\s*\[(.*?)]""".toRegex().find(jsonStr)

            val summary = summaryMatch?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")
                ?: return null

            val labels = labelsMatch?.groupValues?.get(1)
                ?.split(",")
                ?.map { it.trim().removeSurrounding("\"") }
                ?.filter { it.isNotBlank() }

            IssueFieldSuggestion(
                summary = summary,
                description = descMatch?.groupValues?.get(1)
                    ?.replace("\\\"", "\"")
                    ?.replace("\\n", "\n") ?: "",
                issueType = typeMatch?.groupValues?.get(1) ?: "Task",
                priority = priorityMatch?.groupValues?.get(1) ?: "Medium",
                labels = labels
            )
        } catch (e: Exception) {
            logger.warn { "Failed to parse issue text with regex: ${e.message}" }
            null
        }
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

data class NlToJqlRequest(
    val query: String,
    val includeProjects: Boolean? = true
)

data class NlToJqlResponse(
    val success: Boolean,
    val jql: String? = null,
    val explanation: String? = null,
    val confidence: Double = 0.0,
    val warnings: List<String>? = null,
    val error: String? = null,
    val cached: Boolean = false  // 캐시에서 반환됨을 표시
)

data class AnalyzeTextRequest(
    val text: String
)

data class IssueFieldSuggestion(
    val summary: String,
    val description: String,
    val issueType: String,
    val priority: String,
    val labels: List<String>? = null
)

data class AnalyzeTextResponse(
    val success: Boolean,
    val data: IssueFieldSuggestion? = null,
    val error: String? = null,
    val cached: Boolean = false  // 캐시에서 반환됨을 표시
)
