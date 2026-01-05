package ai.claudeflow.core.plugin

import ai.claudeflow.core.clarification.ClarificationRequest
import ai.claudeflow.core.clarification.ClarificationOption
import ai.claudeflow.core.rag.CodeChunk
import ai.claudeflow.core.rag.CodeKnowledgeService
import ai.claudeflow.core.rag.ReviewGuideline
import ai.claudeflow.core.review.MrAnalyzer
import ai.claudeflow.core.review.MrAnalysisResult
import ai.claudeflow.core.review.IssueSeverity
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * GitLab 플러그인
 *
 * GitLab API를 통한 MR, 이슈, 파이프라인 관리
 * RAG 기반 컨텍스트 인식 코드 리뷰 지원
 */
class GitLabPlugin(
    private val codeKnowledgeService: CodeKnowledgeService? = null,
    private val mrAnalyzer: MrAnalyzer = MrAnalyzer()
) : BasePlugin() {
    override val id = "gitlab"
    override val name = "GitLab"
    override val description = "GitLab MR, 이슈, 파이프라인 관리"

    override val commands = listOf(
        // 조회 명령어
        PluginCommand(
            name = "mr-list",
            description = "오픈된 MR 목록 조회",
            usage = "/gitlab mr-list [project]",
            examples = listOf("/gitlab mr-list", "/gitlab mr-list my-project")
        ),
        PluginCommand(
            name = "mr-info",
            description = "MR 상세 정보 조회",
            usage = "/gitlab mr-info <project> <mr_id>",
            examples = listOf("/gitlab mr-info my-project 123")
        ),
        PluginCommand(
            name = "pipeline-status",
            description = "파이프라인 상태 조회",
            usage = "/gitlab pipeline-status <project>",
            examples = listOf("/gitlab pipeline-status my-project")
        ),
        PluginCommand(
            name = "issues",
            description = "이슈 목록 조회",
            usage = "/gitlab issues [project] [state]",
            examples = listOf("/gitlab issues", "/gitlab issues my-project opened")
        ),
        // 쓰기 명령어
        PluginCommand(
            name = "create-branch",
            description = "새 브랜치 생성",
            usage = "/gitlab create-branch <project> <branch_name> [ref]",
            examples = listOf(
                "/gitlab create-branch my-project feature/AUTH-123",
                "/gitlab create-branch my-project hotfix/login-fix main"
            )
        ),
        PluginCommand(
            name = "commit",
            description = "파일 변경 후 커밋",
            usage = "/gitlab commit <project> <branch> <message> <file_path> <content>",
            examples = listOf("/gitlab commit my-project feature/test \"fix: 버그 수정\" src/main.kt \"코드 내용\"")
        ),
        PluginCommand(
            name = "create-mr",
            description = "Merge Request 생성",
            usage = "/gitlab create-mr <project> <source_branch> <target_branch> <title> [description]",
            examples = listOf("/gitlab create-mr my-project feature/AUTH-123 main \"feat: 로그인 기능 추가\"")
        ),
        // RAG 기반 리뷰 명령어
        PluginCommand(
            name = "mr-review",
            description = "MR을 RAG 기반으로 컨텍스트 인식 리뷰",
            usage = "/gitlab mr-review <project> <mr_id>",
            examples = listOf("/gitlab mr-review my-project 123")
        ),
        PluginCommand(
            name = "index-project",
            description = "프로젝트 코드를 RAG 인덱싱",
            usage = "/gitlab index-project <project> [branch]",
            examples = listOf("/gitlab index-project my-project", "/gitlab index-project my-project develop")
        ),
        PluginCommand(
            name = "knowledge-stats",
            description = "프로젝트 RAG 인덱싱 통계 조회",
            usage = "/gitlab knowledge-stats <project>",
            examples = listOf("/gitlab knowledge-stats my-project")
        ),
        PluginCommand(
            name = "mr-comment",
            description = "MR에 코멘트 작성",
            usage = "/gitlab mr-comment <mr_id> <comment>",
            examples = listOf("/gitlab mr-comment 123 \"리뷰 완료\"")
        ),
        PluginCommand(
            name = "mr-add-label",
            description = "MR에 라벨 추가",
            usage = "/gitlab mr-add-label <mr_id> <label>",
            examples = listOf("/gitlab mr-add-label 123 \"ai-review::done\"")
        ),
        PluginCommand(
            name = "mr-notes",
            description = "MR 코멘트 목록 조회",
            usage = "/gitlab mr-notes <project> <mr_id>",
            examples = listOf("/gitlab mr-notes my-project 123")
        ),
        PluginCommand(
            name = "note-emojis",
            description = "코멘트의 이모지 조회",
            usage = "/gitlab note-emojis <project> <mr_id> <note_id>",
            examples = listOf("/gitlab note-emojis my-project 123 456789")
        ),
        PluginCommand(
            name = "search-mrs-by-issue",
            description = "Jira 이슈 키로 관련 MR 검색 (커밋 메시지, 브랜치명, 설명에서)",
            usage = "/gitlab search-mrs-by-issue <issue-key> [project]",
            examples = listOf("/gitlab search-mrs-by-issue PROJ-123", "/gitlab search-mrs-by-issue PROJ-123 my-project")
        ),
        PluginCommand(
            name = "reviewed-mrs",
            description = "AI 리뷰 완료된 MR 목록 조회 (ai-review::done 라벨)",
            usage = "/gitlab reviewed-mrs <project> [days]",
            examples = listOf("/gitlab reviewed-mrs my-project", "/gitlab reviewed-mrs my-project 7")
        )
    )

    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private lateinit var baseUrl: String
    private lateinit var token: String
    private var group: String? = null
    private val projectCache = mutableMapOf<String, String>()

    /**
     * 토큰 마스킹 유틸리티
     * 보안을 위해 토큰의 앞 4자리와 뒤 4자리만 표시
     */
    private fun maskToken(token: String): String {
        return if (token.length > 8) {
            "${token.take(4)}****${token.takeLast(4)}"
        } else {
            "****"
        }
    }

    override suspend fun initialize(config: Map<String, String>) {
        super.initialize(config)
        baseUrl = requireConfig("GITLAB_URL").trimEnd('/')
        token = requireConfig("GITLAB_TOKEN")
        group = config["GITLAB_GROUP"]  // 선택: team/subgroup 형태
        logger.info { "GitLab plugin initialized: $baseUrl (group: ${group ?: "none"}, token: ${maskToken(token)})" }
    }

    /**
     * MR 검색 결과 타입
     */
    sealed class MrSearchResult {
        data class Found(val project: String, val mr: Map<String, Any>) : MrSearchResult()
        data class MultipleFound(val matches: List<MrMatch>) : MrSearchResult()
        object NotFound : MrSearchResult()
    }

    data class MrMatch(
        val project: String,
        val iid: Int,
        val title: String,
        val author: String,
        val state: String,
        val webUrl: String
    )

    /**
     * MR 번호로 프로젝트 찾기 (전역 검색)
     *
     * @param mrIid MR 번호 (프로젝트 내 IID)
     * @return MrSearchResult - Found, MultipleFound, 또는 NotFound
     */
    private fun findProjectByMrIid(mrIid: Int): MrSearchResult {
        try {
            // 그룹이 설정되어 있으면 그룹 내에서 검색
            val searchUrl = if (group != null) {
                "$baseUrl/api/v4/groups/${java.net.URLEncoder.encode(group, "UTF-8")}/merge_requests?state=all&per_page=100"
            } else {
                "$baseUrl/api/v4/merge_requests?state=all&scope=all&per_page=100"
            }

            val response = apiGet(searchUrl)
            val allMrs = mapper.readValue<List<Map<String, Any>>>(response)

            // 해당 IID를 가진 MR 찾기
            val matchingMrs = allMrs.filter { mr ->
                (mr["iid"] as? Number)?.toInt() == mrIid
            }

            return when {
                matchingMrs.isEmpty() -> {
                    logger.warn { "MR !$mrIid not found in ${group ?: "accessible projects"}" }
                    MrSearchResult.NotFound
                }
                matchingMrs.size == 1 -> {
                    val mr = matchingMrs[0]
                    val webUrl = mr["web_url"] as? String ?: ""
                    val projectPath = webUrl
                        .substringAfter("$baseUrl/")
                        .substringBefore("/-/merge_requests")
                    logger.info { "Found MR !$mrIid in project: $projectPath" }
                    MrSearchResult.Found(projectPath, mr)
                }
                else -> {
                    // 여러 프로젝트에서 같은 MR 번호 발견 - 사용자에게 선택 요청
                    val matches = matchingMrs.map { mr ->
                        val webUrl = mr["web_url"] as? String ?: ""
                        val projectPath = webUrl.substringAfter("$baseUrl/").substringBefore("/-/merge_requests")
                        MrMatch(
                            project = projectPath,
                            iid = (mr["iid"] as? Number)?.toInt() ?: mrIid,
                            title = mr["title"] as? String ?: "",
                            author = (mr["author"] as? Map<*, *>)?.get("name") as? String ?: "unknown",
                            state = mr["state"] as? String ?: "",
                            webUrl = webUrl
                        )
                    }
                    logger.info { "Multiple MRs found with IID $mrIid: ${matches.map { it.project }}" }
                    MrSearchResult.MultipleFound(matches)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to search for MR !$mrIid" }
            return MrSearchResult.NotFound
        }
    }

    /**
     * 여러 MR 중 선택을 요청하는 응답 생성
     * ClarificationRequest 패턴 사용
     */
    private fun createMrSelectionRequest(mrIid: Int, matches: List<MrMatch>): PluginResult {
        val clarification = ClarificationRequest(
            header = "MR 선택",
            question = "MR !$mrIid 가 여러 프로젝트에서 발견되었습니다. 어떤 MR을 처리할까요?",
            options = matches.take(4).map { match ->
                ClarificationOption(
                    label = "${match.project.substringAfterLast("/")} !${match.iid}",
                    description = "${match.title.take(40)} (by ${match.author}, ${match.state})",
                    value = match.project,
                    metadata = mapOf(
                        "project" to match.project,
                        "iid" to match.iid,
                        "webUrl" to match.webUrl
                    )
                )
            },
            context = mapOf("mrIid" to mrIid, "matchCount" to matches.size)
        )

        return PluginResult(
            success = false,
            message = clarification.toSlackMessage(),
            data = mapOf(
                "clarificationRequired" to true,
                "clarification" to clarification.toAskUserQuestionFormat(),
                "matches" to matches.map { mapOf(
                    "project" to it.project,
                    "iid" to it.iid,
                    "title" to it.title,
                    "author" to it.author,
                    "webUrl" to it.webUrl
                )}
            )
        )
    }

    /**
     * 프로젝트 이름을 GitLab API용 full path로 변환
     *
     * - 이미 full path(/포함)이면 그대로 반환
     * - 숫자(프로젝트 ID)면 그대로 반환
     * - 단순 이름이면 GitLab API로 검색 후 캐싱
     */
    private fun resolveProject(project: String): String {
        // 이미 full path이거나 숫자(프로젝트 ID)이면 그대로 사용
        if (project.contains("/") || project.all { it.isDigit() }) {
            return project
        }

        // 캐시 확인
        projectCache[project]?.let { return it }

        // GitLab API로 프로젝트 검색
        try {
            val searchUrl = "$baseUrl/api/v4/projects?search=$project&per_page=20"
            val response = apiGet(searchUrl)
            val projects = mapper.readValue<List<Map<String, Any>>>(response)

            // 그룹 설정이 있으면 필터링
            val filtered = if (group != null) {
                projects.filter { p ->
                    val path = p["path_with_namespace"] as? String ?: ""
                    path.startsWith("$group/")
                }
            } else {
                projects
            }

            // 정확히 일치하는 프로젝트 찾기
            val exactMatch = filtered.find { p ->
                val name = p["name"] as? String ?: ""
                val path = p["path"] as? String ?: ""
                name.equals(project, ignoreCase = true) || path.equals(project, ignoreCase = true)
            }

            val resolvedPath = if (exactMatch != null) {
                exactMatch["path_with_namespace"] as? String ?: project
            } else if (filtered.size == 1) {
                // 필터링 결과가 1개면 사용
                filtered[0]["path_with_namespace"] as? String ?: project
            } else if (filtered.isNotEmpty()) {
                // 여러 개면 첫 번째 사용 (경고 로그)
                val firstPath = filtered[0]["path_with_namespace"] as? String ?: project
                logger.warn { "Multiple projects found for '$project': ${filtered.map { it["path_with_namespace"] }}. Using: $firstPath" }
                firstPath
            } else {
                logger.warn { "Project not found: $project. Using as-is." }
                project
            }

            projectCache[project] = resolvedPath
            logger.info { "Resolved project '$project' -> '$resolvedPath'" }
            return resolvedPath
        } catch (e: Exception) {
            logger.warn { "Failed to resolve project '$project': ${e.message}. Using as-is." }
            return project
        }
    }

    override fun shouldHandle(message: String): Boolean {
        val lower = message.lowercase()
        return lower.startsWith("/gitlab") ||
                lower.contains("mr ") && (lower.contains("목록") || lower.contains("리스트") || lower.contains("조회")) ||
                lower.contains("merge request") ||
                lower.contains("파이프라인") ||
                lower.contains("pipeline")
    }

    override suspend fun execute(command: String, args: Map<String, Any>): PluginResult {
        return when (command) {
            // 조회 명령어
            "mr-list" -> listMergeRequests(args["project"] as? String)
            "mr-info" -> {
                val mrId = (args["mr_id"] as? Number)?.toInt() ?: return PluginResult(false, error = "MR ID required")
                val project = args["project"] as? String ?: run {
                    // 프로젝트 없으면 MR 번호로 검색
                    when (val result = findProjectByMrIid(mrId)) {
                        is MrSearchResult.Found -> {
                            logger.info { "Auto-resolved project for MR !$mrId: ${result.project}" }
                            result.project
                        }
                        is MrSearchResult.MultipleFound -> {
                            // 여러 개 발견 - 사용자에게 선택 요청
                            return createMrSelectionRequest(mrId, result.matches)
                        }
                        is MrSearchResult.NotFound -> {
                            return PluginResult(false, error = "MR !$mrId not found. Please specify project: /gitlab mr-info <project> $mrId")
                        }
                    }
                }
                getMergeRequestInfo(project, mrId)
            }
            "pipeline-status" -> getPipelineStatus(
                args["project"] as? String ?: return PluginResult(false, error = "Project required")
            )
            "issues" -> listIssues(
                args["project"] as? String,
                args["state"] as? String ?: "opened"
            )
            // 쓰기 명령어
            "create-branch" -> createBranch(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                args["branch"] as? String ?: return PluginResult(false, error = "Branch name required"),
                args["ref"] as? String ?: "main"
            )
            "commit" -> createCommit(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                args["branch"] as? String ?: return PluginResult(false, error = "Branch required"),
                args["message"] as? String ?: return PluginResult(false, error = "Commit message required"),
                args["actions"] as? List<Map<String, String>> ?: return PluginResult(false, error = "Actions required")
            )
            "create-mr" -> createMergeRequest(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                args["source_branch"] as? String ?: return PluginResult(false, error = "Source branch required"),
                args["target_branch"] as? String ?: "main",
                args["title"] as? String ?: return PluginResult(false, error = "Title required"),
                args["description"] as? String
            )
            // RAG 기반 명령어
            "mr-review" -> {
                val mrId = (args["mr_id"] as? Number)?.toInt() ?: return PluginResult(false, error = "MR ID required")
                val project = args["project"] as? String ?: run {
                    // 프로젝트 없으면 MR 번호로 검색
                    when (val result = findProjectByMrIid(mrId)) {
                        is MrSearchResult.Found -> {
                            logger.info { "Auto-resolved project for MR !$mrId: ${result.project}" }
                            result.project
                        }
                        is MrSearchResult.MultipleFound -> {
                            // 여러 개 발견 - 사용자에게 선택 요청
                            return createMrSelectionRequest(mrId, result.matches)
                        }
                        is MrSearchResult.NotFound -> {
                            return PluginResult(false, error = "MR !$mrId not found. Please specify project: /gitlab mr-review <project> $mrId")
                        }
                    }
                }
                reviewMergeRequestWithRag(project, mrId)
            }
            "index-project" -> indexProjectToKnowledgeBase(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                args["branch"] as? String ?: "main"
            )
            "knowledge-stats" -> getKnowledgeStats(
                args["project"] as? String ?: return PluginResult(false, error = "Project required")
            )
            // MR 코멘트 작성
            "mr-comment" -> {
                val mrId = (args["mr_id"] as? Number)?.toInt() ?: return PluginResult(false, error = "MR ID required")
                val comment = args["comment"] as? String ?: return PluginResult(false, error = "Comment required")
                val project = args["project"] as? String ?: run {
                    when (val result = findProjectByMrIid(mrId)) {
                        is MrSearchResult.Found -> result.project
                        is MrSearchResult.MultipleFound -> return createMrSelectionRequest(mrId, result.matches)
                        is MrSearchResult.NotFound -> return PluginResult(false, error = "MR !$mrId not found")
                    }
                }
                postMrComment(project, mrId, comment)
            }
            // MR 라벨 추가
            "mr-add-label" -> {
                val mrId = (args["mr_id"] as? Number)?.toInt() ?: return PluginResult(false, error = "MR ID required")
                val label = args["label"] as? String ?: return PluginResult(false, error = "Label required")
                val project = args["project"] as? String ?: run {
                    when (val result = findProjectByMrIid(mrId)) {
                        is MrSearchResult.Found -> result.project
                        is MrSearchResult.MultipleFound -> return createMrSelectionRequest(mrId, result.matches)
                        is MrSearchResult.NotFound -> return PluginResult(false, error = "MR !$mrId not found")
                    }
                }
                addMrLabel(project, mrId, label)
            }
            // MR 노트 조회
            "mr-notes" -> {
                val mrId = (args["mr_id"] as? Number)?.toInt() ?: return PluginResult(false, error = "MR ID required")
                val project = args["project"] as? String ?: return PluginResult(false, error = "Project required")
                getMrNotes(project, mrId)
            }
            // 노트 이모지 조회
            "note-emojis" -> {
                val mrId = (args["mr_id"] as? Number)?.toInt() ?: return PluginResult(false, error = "MR ID required")
                val noteId = (args["note_id"] as? Number)?.toInt() ?: return PluginResult(false, error = "Note ID required")
                val project = args["project"] as? String ?: return PluginResult(false, error = "Project required")
                getNoteEmojis(project, mrId, noteId)
            }
            // Jira 이슈 키로 MR 검색
            "search-mrs-by-issue" -> searchMRsByIssueKey(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required"),
                args["project"] as? String
            )
            // AI 리뷰 완료된 MR 목록 (피드백 폴링용)
            "reviewed-mrs" -> listReviewedMRs(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                (args["days"] as? Number)?.toInt() ?: 3
            )
            else -> PluginResult(false, error = "Unknown command: $command")
        }
    }

    private fun listMergeRequests(project: String?): PluginResult {
        val url = if (project != null) {
            "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests?state=opened&per_page=10"
        } else {
            "$baseUrl/api/v4/merge_requests?state=opened&scope=all&per_page=10"
        }

        return try {
            val response = apiGet(url)
            val mrs = mapper.readValue(response, List::class.java) as List<Map<String, Any>>

            val formatted = mrs.map { mr ->
                mapOf(
                    "iid" to mr["iid"],
                    "title" to mr["title"],
                    "author" to (mr["author"] as? Map<*, *>)?.get("name"),
                    "source_branch" to mr["source_branch"],
                    "target_branch" to mr["target_branch"],
                    "web_url" to mr["web_url"],
                    "created_at" to mr["created_at"],
                    "labels" to mr["labels"]  // ai-review::done 필터링을 위해 추가
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${mrs.size} open merge requests"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list MRs" }
            PluginResult(false, error = e.message)
        }
    }

    private fun getMergeRequestInfo(project: String, mrId: Int): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests/$mrId"

        return try {
            val response = apiGet(url)
            val mr = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val info = mapOf(
                "iid" to mr["iid"],
                "title" to mr["title"],
                "description" to mr["description"],
                "author" to (mr["author"] as? Map<*, *>)?.get("name"),
                "state" to mr["state"],
                "source_branch" to mr["source_branch"],
                "target_branch" to mr["target_branch"],
                "merge_status" to mr["merge_status"],
                "has_conflicts" to mr["has_conflicts"],
                "web_url" to mr["web_url"],
                "created_at" to mr["created_at"],
                "updated_at" to mr["updated_at"]
            )

            PluginResult(success = true, data = info)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get MR info" }
            PluginResult(false, error = e.message)
        }
    }

    private fun getPipelineStatus(project: String): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/pipelines?per_page=5"

        return try {
            val response = apiGet(url)
            val pipelines = mapper.readValue(response, List::class.java) as List<Map<String, Any>>

            val formatted = pipelines.map { pipeline ->
                mapOf(
                    "id" to pipeline["id"],
                    "status" to pipeline["status"],
                    "ref" to pipeline["ref"],
                    "sha" to (pipeline["sha"] as? String)?.take(8),
                    "web_url" to pipeline["web_url"],
                    "created_at" to pipeline["created_at"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${pipelines.size} recent pipelines"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get pipeline status" }
            PluginResult(false, error = e.message)
        }
    }

    private fun listIssues(project: String?, state: String): PluginResult {
        val url = if (project != null) {
            "$baseUrl/api/v4/projects/${encodeProject(project)}/issues?state=$state&per_page=10"
        } else {
            "$baseUrl/api/v4/issues?state=$state&scope=all&per_page=10"
        }

        return try {
            val response = apiGet(url)
            val issues = mapper.readValue(response, List::class.java) as List<Map<String, Any>>

            val formatted = issues.map { issue ->
                mapOf(
                    "iid" to issue["iid"],
                    "title" to issue["title"],
                    "state" to issue["state"],
                    "author" to (issue["author"] as? Map<*, *>)?.get("name"),
                    "labels" to issue["labels"],
                    "web_url" to issue["web_url"],
                    "created_at" to issue["created_at"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${issues.size} issues"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list issues" }
            PluginResult(false, error = e.message)
        }
    }

    // ============================================================
    // 쓰기 명령어 구현
    // ============================================================

    /**
     * 브랜치 생성
     * POST /api/v4/projects/:id/repository/branches
     */
    private fun createBranch(project: String, branchName: String, ref: String): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/repository/branches"
        val body = mapOf(
            "branch" to branchName,
            "ref" to ref
        )

        return try {
            val response = apiPost(url, body)
            val branch = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val result = mapOf(
                "name" to branch["name"],
                "commit" to (branch["commit"] as? Map<*, *>)?.let { commit ->
                    mapOf(
                        "id" to (commit["id"] as? String)?.take(8),
                        "message" to commit["message"]
                    )
                },
                "web_url" to branch["web_url"]
            )

            logger.info { "Created branch: $branchName from $ref in $project" }
            PluginResult(
                success = true,
                data = result,
                message = "브랜치 '$branchName'가 생성되었습니다 (base: $ref)"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create branch: $branchName" }
            PluginResult(false, error = "브랜치 생성 실패: ${e.message}")
        }
    }

    /**
     * 커밋 생성 (파일 추가/수정/삭제)
     * POST /api/v4/projects/:id/repository/commits
     *
     * actions 형식:
     * [
     *   { "action": "create|update|delete", "file_path": "...", "content": "..." }
     * ]
     */
    private fun createCommit(
        project: String,
        branch: String,
        message: String,
        actions: List<Map<String, String>>
    ): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/repository/commits"
        val body = mapOf(
            "branch" to branch,
            "commit_message" to message,
            "actions" to actions.map { action ->
                mapOf(
                    "action" to (action["action"] ?: "update"),
                    "file_path" to action["file_path"],
                    "content" to action["content"]
                )
            }
        )

        return try {
            val response = apiPost(url, body)
            val commit = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val result = mapOf(
                "id" to (commit["id"] as? String)?.take(8),
                "short_id" to commit["short_id"],
                "message" to commit["message"],
                "author_name" to commit["author_name"],
                "created_at" to commit["created_at"],
                "web_url" to commit["web_url"]
            )

            logger.info { "Created commit on $branch: ${commit["short_id"]}" }
            PluginResult(
                success = true,
                data = result,
                message = "커밋이 생성되었습니다: ${commit["short_id"]}"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create commit on $branch" }
            PluginResult(false, error = "커밋 생성 실패: ${e.message}")
        }
    }

    /**
     * Merge Request 생성
     * POST /api/v4/projects/:id/merge_requests
     */
    private fun createMergeRequest(
        project: String,
        sourceBranch: String,
        targetBranch: String,
        title: String,
        description: String?
    ): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests"
        val body = mutableMapOf(
            "source_branch" to sourceBranch,
            "target_branch" to targetBranch,
            "title" to title,
            "remove_source_branch" to true
        )
        if (!description.isNullOrBlank()) {
            body["description"] = description
        }

        return try {
            val response = apiPost(url, body)
            val mr = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val result = mapOf(
                "iid" to mr["iid"],
                "title" to mr["title"],
                "state" to mr["state"],
                "source_branch" to mr["source_branch"],
                "target_branch" to mr["target_branch"],
                "web_url" to mr["web_url"],
                "created_at" to mr["created_at"]
            )

            logger.info { "Created MR !${mr["iid"]}: $title" }
            PluginResult(
                success = true,
                data = result,
                message = "MR이 생성되었습니다: !${mr["iid"]} - $title\n${mr["web_url"]}"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create MR: $title" }
            PluginResult(false, error = "MR 생성 실패: ${e.message}")
        }
    }

    // ============================================================
    // RAG 기반 코드 리뷰
    // ============================================================

    /**
     * MR을 2-Pass 아키텍처로 리뷰
     *
     * Pass 1: 규칙 기반 빠른 분석 (MrAnalyzer)
     *   - GitLab API 플래그 직접 활용 (renamed_file, new_file, deleted_file)
     *   - diff 텍스트 파싱 불필요!
     *   - 빠른 이슈 감지 (보안, Breaking Change, 네이밍 등)
     *
     * Pass 2: RAG 기반 심층 분석 (선택적)
     *   - 관련 코드베이스 검색
     *   - 리뷰 가이드라인 생성
     */
    private fun reviewMergeRequestWithRag(project: String, mrId: Int): PluginResult {
        return try {
            // 단일 API 호출로 MR 정보 + 변경사항 모두 가져오기
            val changesResponse = getChangesResponseFull(project, mrId)

            @Suppress("UNCHECKED_CAST")
            val changes = changesResponse["changes"] as? List<Map<String, Any>> ?: emptyList()

            if (changes.isEmpty()) {
                return PluginResult(
                    success = true,
                    data = mapOf("review" to "변경사항이 없습니다."),
                    message = "MR에 변경사항이 없습니다."
                )
            }

            // ====== Pass 1: 규칙 기반 빠른 분석 (MrAnalyzer) ======
            val analysisResult = mrAnalyzer.analyze(changesResponse, changesResponse)

            // 빠른 이슈 결과 (API 플래그 기반)
            val quickIssuesFormatted = analysisResult.quickIssues.map { issue ->
                val icon = when (issue.severity) {
                    IssueSeverity.ERROR -> "🚨"
                    IssueSeverity.WARNING -> "⚠️"
                    IssueSeverity.INFO -> "ℹ️"
                }
                mapOf(
                    "severity" to issue.severity.name,
                    "category" to issue.category,
                    "message" to "$icon [${issue.category}] ${issue.message}",
                    "suggestion" to issue.suggestion
                )
            }

            // 파일 분석 결과 (API 플래그 기반 - diff 파싱 불필요!)
            val fileAnalysisData = mapOf(
                "renamed" to analysisResult.fileAnalysis.renamed.map {
                    mapOf("oldPath" to it.oldPath, "newPath" to it.newPath, "additions" to it.additions, "deletions" to it.deletions)
                },
                "added" to analysisResult.fileAnalysis.added.map {
                    mapOf("path" to it.newPath, "additions" to it.additions)
                },
                "deleted" to analysisResult.fileAnalysis.deleted.map {
                    mapOf("path" to it.oldPath, "deletions" to it.deletions)
                },
                "modified" to analysisResult.fileAnalysis.modified.map {
                    mapOf("path" to it.newPath, "additions" to it.additions, "deletions" to it.deletions)
                },
                "totalFiles" to analysisResult.fileAnalysis.totalFiles,
                "totalAdditions" to analysisResult.fileAnalysis.totalAdditions,
                "totalDeletions" to analysisResult.fileAnalysis.totalDeletions
            )

            // ====== Pass 2: RAG 기반 심층 분석 (선택적) ======
            var relatedCode = emptyList<CodeChunk>()
            var guidelines = emptyList<ReviewGuideline>()

            if (codeKnowledgeService != null) {
                // 우선순위 파일만 RAG 검색
                for (filePath in analysisResult.reviewContext.priorityFiles.take(5)) {
                    val fileContext = codeKnowledgeService.findRelevantCode(
                        query = "file: $filePath code changes",
                        projectId = project,
                        topK = 3,
                        minScore = 0.5f
                    )
                    relatedCode = relatedCode + fileContext
                }

                // 리뷰 가이드라인 생성
                val allDiffs = changes.take(5).mapNotNull { it["diff"] as? String }.joinToString("\n")
                guidelines = codeKnowledgeService.findReviewGuidelines(allDiffs, project)
            }

            // 통합 결과 구성
            val reviewResult = mapOf(
                "mr" to mapOf(
                    "iid" to analysisResult.mrInfo.iid,
                    "title" to analysisResult.mrInfo.title,
                    "author" to analysisResult.mrInfo.author,
                    "source_branch" to analysisResult.mrInfo.sourceBranch,
                    "target_branch" to analysisResult.mrInfo.targetBranch,
                    "web_url" to analysisResult.mrInfo.webUrl
                ),
                "summary" to analysisResult.summary,
                "fileAnalysis" to fileAnalysisData,
                "quickIssues" to quickIssuesFormatted,
                "priorityFiles" to analysisResult.reviewContext.priorityFiles,
                "guidelines" to guidelines.map { g ->
                    mapOf("rule" to g.rule, "category" to g.category, "severity" to g.severity)
                },
                "related_code" to relatedCode.take(5).map { chunk ->
                    mapOf(
                        "file" to chunk.filePath,
                        "lines" to "${chunk.startLine}-${chunk.endLine}",
                        "type" to chunk.chunkType,
                        "relevance" to "%.2f".format(chunk.score)
                    )
                },
                "review_prompt" to analysisResult.reviewContext.formattedPrompt
            )

            val issueCount = analysisResult.quickIssues.size
            val ragInfo = if (codeKnowledgeService != null) {
                ", ${guidelines.size}개 가이드라인, ${relatedCode.size}개 관련 코드"
            } else ""

            PluginResult(
                success = true,
                data = reviewResult,
                message = "MR !$mrId 분석 완료: ${analysisResult.summary}. ${issueCount}개 이슈 감지$ragInfo"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to review MR !$mrId" }
            PluginResult(false, error = "MR 리뷰 실패: ${e.message}")
        }
    }

    /**
     * /changes API 전체 응답 가져오기 (MR 정보 + 변경사항 포함)
     */
    private fun getChangesResponseFull(project: String, mrId: Int): Map<String, Any> {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests/$mrId/changes"
        val response = apiGet(url)
        return mapper.readValue(response)
    }

    /**
     * MR 상세 정보 (description 포함)
     */
    private fun getMergeRequestDetails(project: String, mrId: Int): Map<String, Any> {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests/$mrId"
        val response = apiGet(url)
        return mapper.readValue(response)
    }

    /**
     * MR 변경사항 (diff) 가져오기
     */
    private fun getMergeRequestChanges(project: String, mrId: Int): List<Map<String, Any>> {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests/$mrId/changes"
        val response = apiGet(url)
        val result: Map<String, Any> = mapper.readValue(response)
        @Suppress("UNCHECKED_CAST")
        return result["changes"] as? List<Map<String, Any>> ?: emptyList()
    }

    /**
     * 리뷰 결과 구성
     */
    private fun buildReviewResult(
        mrInfo: Map<String, Any>,
        changes: List<Map<String, Any>>,
        relatedCode: List<CodeChunk>,
        guidelines: List<ReviewGuideline>
    ): Map<String, Any> {
        return mapOf(
            "mr" to mapOf(
                "iid" to mrInfo["iid"],
                "title" to mrInfo["title"],
                "author" to (mrInfo["author"] as? Map<*, *>)?.get("name"),
                "source_branch" to mrInfo["source_branch"],
                "target_branch" to mrInfo["target_branch"],
                "web_url" to mrInfo["web_url"]
            ),
            "summary" to mapOf(
                "files_changed" to changes.size,
                "additions" to changes.sumOf { (it["diff"] as? String)?.count { c -> c == '+' } ?: 0 },
                "deletions" to changes.sumOf { (it["diff"] as? String)?.count { c -> c == '-' } ?: 0 }
            ),
            "files" to changes.map { change ->
                mapOf(
                    "path" to change["new_path"],
                    "old_path" to change["old_path"],
                    "renamed" to change["renamed_file"],
                    "deleted" to change["deleted_file"],
                    "new_file" to change["new_file"]
                )
            },
            "guidelines" to guidelines.map { g ->
                mapOf(
                    "rule" to g.rule,
                    "category" to g.category,
                    "severity" to g.severity
                )
            },
            "related_code" to relatedCode.take(5).map { chunk ->
                mapOf(
                    "file" to chunk.filePath,
                    "lines" to "${chunk.startLine}-${chunk.endLine}",
                    "type" to chunk.chunkType,
                    "relevance" to "%.2f".format(chunk.score),
                    "preview" to chunk.contentPreview.take(100)
                )
            },
            "review_prompt" to generateReviewPrompt(mrInfo, changes, guidelines, relatedCode)
        )
    }

    /**
     * Claude에게 전달할 리뷰 프롬프트 생성
     */
    private fun generateReviewPrompt(
        mrInfo: Map<String, Any>,
        changes: List<Map<String, Any>>,
        guidelines: List<ReviewGuideline>,
        relatedCode: List<CodeChunk>
    ): String {
        val sb = StringBuilder()

        sb.appendLine("## MR 리뷰 요청")
        sb.appendLine("- 제목: ${mrInfo["title"]}")
        sb.appendLine("- 브랜치: ${mrInfo["source_branch"]} → ${mrInfo["target_branch"]}")
        sb.appendLine("- 변경 파일: ${changes.size}개")
        sb.appendLine()

        if (guidelines.isNotEmpty()) {
            sb.appendLine("## 자동 검출된 리뷰 포인트")
            for (g in guidelines) {
                val icon = when (g.severity) {
                    "error" -> "🚨"
                    "warning" -> "⚠️"
                    else -> "ℹ️"
                }
                sb.appendLine("$icon [${g.category}] ${g.rule}")
            }
            sb.appendLine()
        }

        if (relatedCode.isNotEmpty()) {
            sb.appendLine("## 관련 코드베이스 (RAG)")
            for (chunk in relatedCode.take(3)) {
                sb.appendLine("- ${chunk.filePath}:${chunk.startLine}-${chunk.endLine}")
                sb.appendLine("  ${chunk.contentPreview.take(80)}...")
            }
            sb.appendLine()
        }

        sb.appendLine("## 변경된 파일 및 코드 diff")
        for (change in changes.take(10)) {  // 최대 10개 파일
            val status = when {
                change["new_file"] == true -> "[신규]"
                change["deleted_file"] == true -> "[삭제]"
                change["renamed_file"] == true -> "[이름변경]"
                else -> "[수정]"
            }
            sb.appendLine("### $status ${change["new_path"]}")

            // diff 내용 포함 (최대 300줄)
            val diff = change["diff"] as? String
            if (!diff.isNullOrBlank()) {
                val diffLines = diff.lines().take(300)
                sb.appendLine("```diff")
                sb.appendLine(diffLines.joinToString("\n"))
                if (diff.lines().size > 300) {
                    sb.appendLine("... (truncated, ${diff.lines().size - 300} more lines)")
                }
                sb.appendLine("```")
            }
            sb.appendLine()
        }

        if (changes.size > 10) {
            sb.appendLine("... 그 외 ${changes.size - 10}개 파일 (생략)")
        }

        return sb.toString()
    }

    /**
     * 프로젝트 코드를 RAG 지식 베이스에 인덱싱
     *
     * GitLab API로 프로젝트 파일 목록을 가져와 인덱싱
     */
    private fun indexProjectToKnowledgeBase(project: String, branch: String): PluginResult {
        if (codeKnowledgeService == null) {
            return PluginResult(
                success = false,
                error = "RAG 서비스가 비활성화되어 있습니다."
            )
        }

        return try {
            // 컬렉션 초기화
            codeKnowledgeService.initCollection()

            // 프로젝트 파일 트리 가져오기
            val files = getProjectFileTree(project, branch)

            if (files.isEmpty()) {
                return PluginResult(
                    success = true,
                    data = mapOf("indexed" to 0),
                    message = "인덱싱할 파일이 없습니다."
                )
            }

            var filesProcessed = 0
            var chunksIndexed = 0
            var errorCount = 0

            // 각 파일 내용 가져와서 인덱싱
            for (file in files.take(100)) {  // 최대 100개 파일
                val path = file["path"] as? String ?: continue
                val type = file["type"] as? String

                // blob (파일)만 처리
                if (type != "blob") continue

                // 지원하는 확장자만
                val ext = path.substringAfterLast(".", "")
                if (ext !in CodeKnowledgeService.SUPPORTED_EXTENSIONS) continue

                try {
                    val content = getFileContent(project, path, branch)
                    if (content.isNotBlank()) {
                        val chunks = indexFileContent(project, path, content)
                        if (chunks > 0) {
                            filesProcessed++
                            chunksIndexed += chunks
                        }
                    }
                } catch (e: Exception) {
                    logger.debug { "Failed to index file $path: ${e.message}" }
                    errorCount++
                }

                if (filesProcessed % 20 == 0 && filesProcessed > 0) {
                    logger.info { "Indexed $filesProcessed files ($chunksIndexed chunks) for $project..." }
                }
            }

            logger.info { "Project indexing complete: $filesProcessed files, $chunksIndexed chunks, $errorCount errors" }

            PluginResult(
                success = true,
                data = mapOf(
                    "project" to project,
                    "branch" to branch,
                    "files_indexed" to filesProcessed,
                    "chunks_created" to chunksIndexed,
                    "errors" to errorCount
                ),
                message = "프로젝트 '$project' 인덱싱 완료: ${filesProcessed}개 파일, ${chunksIndexed}개 청크"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to index project $project" }
            PluginResult(false, error = "프로젝트 인덱싱 실패: ${e.message}")
        }
    }

    /**
     * 프로젝트 파일 트리 조회
     */
    private fun getProjectFileTree(project: String, branch: String): List<Map<String, Any>> {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/repository/tree" +
                "?ref=$branch&recursive=true&per_page=100"
        val response = apiGet(url)
        return mapper.readValue(response)
    }

    /**
     * 파일 내용 조회
     */
    private fun getFileContent(project: String, filePath: String, branch: String): String {
        val encodedPath = java.net.URLEncoder.encode(filePath, "UTF-8")
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/repository/files/$encodedPath/raw?ref=$branch"
        return apiGet(url)
    }

    /**
     * 파일 내용 직접 인덱싱
     *
     * @return 인덱싱된 청크 수
     */
    private fun indexFileContent(projectId: String, filePath: String, content: String): Int {
        return codeKnowledgeService?.indexRemoteFile(projectId, filePath, content) ?: 0
    }

    /**
     * 프로젝트 RAG 인덱싱 통계 조회
     */
    private fun getKnowledgeStats(project: String): PluginResult {
        if (codeKnowledgeService == null) {
            return PluginResult(
                success = false,
                error = "RAG 서비스가 비활성화되어 있습니다."
            )
        }

        return try {
            val stats = codeKnowledgeService.getProjectStats(project)

            PluginResult(
                success = true,
                data = mapOf(
                    "project" to stats.projectId,
                    "total_chunks" to stats.totalChunks,
                    "last_updated" to stats.lastUpdated,
                    "rag_enabled" to true
                ),
                message = "프로젝트 '$project': ${stats.totalChunks}개 청크 인덱싱됨"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get knowledge stats for $project" }
            PluginResult(false, error = "통계 조회 실패: ${e.message}")
        }
    }

    // ============================================================
    // HTTP 유틸리티
    // ============================================================

    private fun apiGet(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("GitLab API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    private fun apiPost(url: String, body: Map<String, Any?>): String {
        val jsonBody = mapper.writeValueAsString(body)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("GitLab API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    private fun apiPut(url: String, body: Map<String, Any?>): String {
        val jsonBody = mapper.writeValueAsString(body)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("GitLab API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    /**
     * MR에 코멘트 작성
     * POST /api/v4/projects/:id/merge_requests/:merge_request_iid/notes
     */
    private fun postMrComment(project: String, mrId: Int, comment: String): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests/$mrId/notes"
        val body = mapOf("body" to comment)

        return try {
            val response = apiPost(url, body)
            val note = mapper.readValue<Map<String, Any>>(response)

            val result = mapOf(
                "id" to note["id"],
                "body" to (note["body"] as? String)?.take(100),
                "author" to (note["author"] as? Map<*, *>)?.get("name"),
                "created_at" to note["created_at"],
                "mr_iid" to mrId,
                "project" to project
            )

            logger.info { "Posted comment to MR !$mrId in $project" }
            PluginResult(
                success = true,
                data = result,
                message = "MR !${mrId}에 코멘트가 작성되었습니다."
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to post comment to MR !$mrId" }
            PluginResult(false, error = "코멘트 작성 실패: ${e.message}")
        }
    }

    /**
     * MR에 라벨 추가
     * PUT /api/v4/projects/:id/merge_requests/:merge_request_iid
     */
    private fun addMrLabel(project: String, mrId: Int, label: String): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests/$mrId"
        val body = mapOf("add_labels" to label)

        return try {
            val response = apiPut(url, body)
            val mr = mapper.readValue<Map<String, Any>>(response)

            val result = mapOf(
                "iid" to mr["iid"],
                "labels" to mr["labels"],
                "project" to project,
                "added_label" to label
            )

            logger.info { "Added label '$label' to MR !$mrId in $project" }
            PluginResult(
                success = true,
                data = result,
                message = "MR !${mrId}에 라벨 '$label'이(가) 추가되었습니다."
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to add label to MR !$mrId" }
            PluginResult(false, error = "라벨 추가 실패: ${e.message}")
        }
    }

    /**
     * MR 노트(코멘트) 목록 조회
     * GET /api/v4/projects/:id/merge_requests/:merge_request_iid/notes
     */
    private fun getMrNotes(project: String, mrId: Int): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests/$mrId/notes?per_page=100"

        return try {
            val response = apiGet(url)
            val notes = mapper.readValue<List<Map<String, Any>>>(response)

            val formatted = notes.map { note ->
                mapOf(
                    "id" to note["id"],
                    "body" to (note["body"] as? String)?.take(200),
                    "author" to (note["author"] as? Map<*, *>)?.get("username"),
                    "created_at" to note["created_at"],
                    "system" to note["system"],
                    "resolvable" to note["resolvable"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "MR !${mrId}에 ${notes.size}개 코멘트"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get notes for MR !$mrId" }
            PluginResult(false, error = "노트 조회 실패: ${e.message}")
        }
    }

    /**
     * 노트의 이모지(Award Emoji) 조회
     * GET /api/v4/projects/:id/merge_requests/:merge_request_iid/notes/:note_id/award_emoji
     */
    private fun getNoteEmojis(project: String, mrId: Int, noteId: Int): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests/$mrId/notes/$noteId/award_emoji"

        return try {
            val response = apiGet(url)
            val emojis = mapper.readValue<List<Map<String, Any>>>(response)

            val formatted = emojis.map { emoji ->
                mapOf(
                    "id" to emoji["id"],
                    "name" to emoji["name"],
                    "user_id" to (emoji["user"] as? Map<*, *>)?.get("id"),
                    "username" to (emoji["user"] as? Map<*, *>)?.get("username"),
                    "created_at" to emoji["created_at"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "노트 ${noteId}에 ${emojis.size}개 이모지"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get emojis for note $noteId" }
            PluginResult(false, error = "이모지 조회 실패: ${e.message}")
        }
    }

    /**
     * 프로젝트 이름을 resolve 후 URL 인코딩
     */
    private fun encodeProject(project: String): String {
        val resolved = resolveProject(project)
        return java.net.URLEncoder.encode(resolved, "UTF-8")
    }

    // ============================================================
    // Jira 이슈 키로 MR 검색
    // ============================================================

    /**
     * Jira 이슈 키로 관련 MR 검색
     *
     * 검색 대상:
     * - MR 제목 (title)
     * - MR 설명 (description)
     * - 소스 브랜치명 (source_branch)
     *
     * @param issueKey Jira 이슈 키 (예: PROJ-123)
     * @param project 특정 프로젝트만 검색 (null이면 그룹 전체 검색)
     * @return 매칭된 MR 목록
     */
    private fun searchMRsByIssueKey(issueKey: String, project: String?): PluginResult {
        return try {
            val issueKeyUpper = issueKey.uppercase()
            val issueKeyLower = issueKey.lowercase()

            // 1. MR 검색 (제목과 설명에서 검색)
            val searchUrl = if (project != null) {
                "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests?state=all&search=$issueKeyUpper&per_page=50"
            } else if (group != null) {
                "$baseUrl/api/v4/groups/${java.net.URLEncoder.encode(group, "UTF-8")}/merge_requests?state=all&search=$issueKeyUpper&per_page=50"
            } else {
                "$baseUrl/api/v4/merge_requests?state=all&scope=all&search=$issueKeyUpper&per_page=50"
            }

            val response = apiGet(searchUrl)
            val allMrs = mapper.readValue<List<Map<String, Any>>>(response)

            // 2. 정확히 이슈 키가 포함된 MR만 필터링 (브랜치명 포함)
            val matchingMrs = allMrs.filter { mr ->
                val title = mr["title"]?.toString() ?: ""
                val description = mr["description"]?.toString() ?: ""
                val sourceBranch = mr["source_branch"]?.toString() ?: ""

                // 제목, 설명, 브랜치명에서 이슈 키 확인 (대소문자 무시)
                title.contains(issueKeyUpper, ignoreCase = true) ||
                        title.contains(issueKeyLower, ignoreCase = true) ||
                        description.contains(issueKeyUpper, ignoreCase = true) ||
                        description.contains(issueKeyLower, ignoreCase = true) ||
                        sourceBranch.contains(issueKeyUpper, ignoreCase = true) ||
                        sourceBranch.contains(issueKeyLower, ignoreCase = true)
            }

            // 3. 결과 포맷팅
            val formatted = matchingMrs.map { mr ->
                val webUrl = mr["web_url"]?.toString() ?: ""
                val projectPath = webUrl
                    .substringAfter("$baseUrl/")
                    .substringBefore("/-/merge_requests")

                mapOf(
                    "iid" to mr["iid"],
                    "title" to mr["title"],
                    "state" to mr["state"],
                    "source_branch" to mr["source_branch"],
                    "target_branch" to mr["target_branch"],
                    "author" to (mr["author"] as? Map<*, *>)?.get("name"),
                    "web_url" to webUrl,
                    "project" to projectPath,
                    "created_at" to mr["created_at"],
                    "updated_at" to mr["updated_at"],
                    "merged_at" to mr["merged_at"]
                )
            }

            // 4. 상태별 그룹화 (merged, open, closed)
            val grouped = formatted.groupBy { it["state"] }
            val mergedCount = grouped["merged"]?.size ?: 0
            val openedCount = grouped["opened"]?.size ?: 0
            val closedCount = grouped["closed"]?.size ?: 0

            PluginResult(
                success = true,
                data = mapOf(
                    "issueKey" to issueKey,
                    "totalFound" to formatted.size,
                    "merged" to mergedCount,
                    "opened" to openedCount,
                    "closed" to closedCount,
                    "mrs" to formatted
                ),
                message = if (formatted.isEmpty()) {
                    "이슈 $issueKey 와 연관된 MR을 찾을 수 없습니다."
                } else {
                    "이슈 $issueKey 와 연관된 MR ${formatted.size}개 발견 (Merged: $mergedCount, Open: $openedCount)"
                }
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to search MRs by issue key: $issueKey" }
            PluginResult(false, error = "MR 검색 실패: ${e.message}")
        }
    }

    // ============================================================
    // AI 리뷰 완료 MR 목록 (피드백 폴링용)
    // ============================================================

    /**
     * AI 리뷰 완료된 MR 목록 조회
     *
     * n8n 피드백 폴러에서 사용:
     * - state=opened
     * - labels=ai-review::done
     * - updated_after=N일 전
     *
     * @param project GitLab 프로젝트 경로
     * @param days 최근 N일 내 업데이트된 MR만 조회 (기본 3일)
     */
    private fun listReviewedMRs(project: String, days: Int): PluginResult {
        return try {
            val updatedAfter = java.time.Instant.now()
                .minus(java.time.Duration.ofDays(days.toLong()))
                .toString()

            val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests" +
                    "?state=opened" +
                    "&labels=ai-review::done" +
                    "&updated_after=$updatedAfter" +
                    "&per_page=50"

            val response = apiGet(url)
            val mrs = mapper.readValue<List<Map<String, Any>>>(response)

            val formatted = mrs.map { mr ->
                mapOf(
                    "iid" to mr["iid"],
                    "title" to mr["title"],
                    "author" to (mr["author"] as? Map<*, *>)?.get("name"),
                    "source_branch" to mr["source_branch"],
                    "target_branch" to mr["target_branch"],
                    "web_url" to mr["web_url"],
                    "updated_at" to mr["updated_at"],
                    "labels" to mr["labels"]
                )
            }

            logger.info { "Found ${mrs.size} reviewed MRs in $project (last $days days)" }

            PluginResult(
                success = true,
                data = formatted,
                message = "$project 에서 AI 리뷰 완료된 MR ${mrs.size}개 (최근 ${days}일)"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list reviewed MRs for $project" }
            PluginResult(false, error = "리뷰된 MR 조회 실패: ${e.message}")
        }
    }
}
