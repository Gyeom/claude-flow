package ai.claudeflow.core.plugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Jira 플러그인
 *
 * Jira API를 통한 이슈 조회 및 관리
 * - 이슈 CRUD 및 상태 관리
 * - 댓글 추가/조회
 * - 라벨 관리
 * - 스프린트/보드 조회
 * - JQL 검색
 */
class JiraPlugin : BasePlugin() {
    override val id = "jira"
    override val name = "Jira"
    override val description = "Jira 이슈 조회, 생성, 댓글, 라벨 관리"

    override val commands = listOf(
        PluginCommand(
            name = "issue",
            description = "이슈 상세 조회",
            usage = "/jira issue <issue-key>",
            examples = listOf("/jira issue PROJ-123")
        ),
        PluginCommand(
            name = "my-issues",
            description = "내게 할당된 이슈 조회",
            usage = "/jira my-issues",
            examples = listOf("/jira my-issues")
        ),
        PluginCommand(
            name = "sprint",
            description = "현재 스프린트 이슈 조회",
            usage = "/jira sprint [board-id]",
            examples = listOf("/jira sprint", "/jira sprint 123")
        ),
        PluginCommand(
            name = "search",
            description = "JQL로 이슈 검색",
            usage = "/jira search <jql>",
            examples = listOf("/jira search project=PROJ AND status=Open")
        ),
        PluginCommand(
            name = "transition",
            description = "이슈 상태 변경",
            usage = "/jira transition <issue-key> <status>",
            examples = listOf("/jira transition PROJ-123 Done")
        ),
        PluginCommand(
            name = "create",
            description = "새 이슈 생성",
            usage = "/jira create <project> <summary> [description]",
            examples = listOf("/jira create PROJ \"버그 수정 필요\" \"로그인 실패 이슈\"")
        ),
        PluginCommand(
            name = "comment",
            description = "이슈에 댓글 추가",
            usage = "/jira comment <issue-key> <comment>",
            examples = listOf("/jira comment PROJ-123 \"분석 완료, PR 준비중\"")
        ),
        PluginCommand(
            name = "comments",
            description = "이슈 댓글 조회",
            usage = "/jira comments <issue-key>",
            examples = listOf("/jira comments PROJ-123")
        ),
        PluginCommand(
            name = "assign",
            description = "이슈 담당자 변경",
            usage = "/jira assign <issue-key> <account-id|email>",
            examples = listOf("/jira assign PROJ-123 user@example.com")
        ),
        PluginCommand(
            name = "labels",
            description = "이슈 라벨 추가/제거",
            usage = "/jira labels <issue-key> <add|remove> <label>",
            examples = listOf("/jira labels PROJ-123 add ai:analyzed")
        ),
        PluginCommand(
            name = "link",
            description = "이슈 링크 생성 (relates to, blocks 등)",
            usage = "/jira link <issue-key> <link-type> <target-issue>",
            examples = listOf("/jira link PROJ-123 blocks PROJ-456")
        ),
        PluginCommand(
            name = "projects",
            description = "접근 가능한 프로젝트 목록",
            usage = "/jira projects",
            examples = listOf("/jira projects")
        ),
        PluginCommand(
            name = "boards",
            description = "스크럼/칸반 보드 목록",
            usage = "/jira boards [project-key]",
            examples = listOf("/jira boards", "/jira boards PROJ")
        ),
        PluginCommand(
            name = "sprints",
            description = "보드의 스프린트 목록 조회",
            usage = "/jira sprints <board-id>",
            examples = listOf("/jira sprints 123")
        ),
        PluginCommand(
            name = "set_sprint",
            description = "이슈의 스프린트 설정/변경",
            usage = "/jira set_sprint <issue-key> <sprint-id>",
            examples = listOf("/jira set_sprint PROJ-123 456", "/jira set_sprint PROJ-123 backlog")
        ),
        PluginCommand(
            name = "search_users",
            description = "사용자 검색",
            usage = "/jira search_users <query> [project-key]",
            examples = listOf("/jira search_users john", "/jira search_users john PROJ")
        ),
        PluginCommand(
            name = "get_issue_links",
            description = "이슈의 연결된 링크 조회 (MR, PR 등)",
            usage = "/jira get_issue_links <issue-key>",
            examples = listOf("/jira get_issue_links PROJ-123")
        ),
        PluginCommand(
            name = "add_remote_link",
            description = "이슈에 웹 링크 추가 (GitLab MR 등)",
            usage = "/jira add_remote_link <issue-key> <url> <title>",
            examples = listOf("/jira add_remote_link PROJ-123 https://gitlab.example.com/group/project/-/merge_requests/456 \"MR: Fix bug\"")
        ),
        PluginCommand(
            name = "check_transition_requirements",
            description = "트랜지션 실행 가능 여부 사전 검증",
            usage = "/jira check_transition_requirements <issue-key> <transition-id>",
            examples = listOf("/jira check_transition_requirements PROJ-123 81")
        )
    )

    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private lateinit var baseUrl: String
    private lateinit var authHeader: String

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
        baseUrl = requireConfig("JIRA_URL").trimEnd('/')
        val email = requireConfig("JIRA_EMAIL")
        val apiToken = requireConfig("JIRA_API_TOKEN")

        // Basic Auth 헤더 생성
        val credentials = "$email:$apiToken"
        authHeader = "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}"

        logger.info { "Jira plugin initialized: $baseUrl (email: $email, token: ${maskToken(apiToken)})" }
    }

    override fun shouldHandle(message: String): Boolean {
        val lower = message.lowercase()
        return lower.startsWith("/jira") ||
                lower.matches(Regex(".*[A-Z]+-\\d+.*")) ||  // 이슈 키 패턴 (PROJ-123)
                (lower.contains("이슈") && (lower.contains("조회") || lower.contains("확인"))) ||
                lower.contains("스프린트") ||
                lower.contains("sprint")
    }

    override suspend fun execute(command: String, args: Map<String, Any>): PluginResult {
        return when (command) {
            "issue" -> getIssue(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required")
            )
            "my-issues" -> getMyIssues()
            "sprint" -> getSprintIssues(args["board_id"] as? Int, args["sprint_id"] as? Int)
            "search" -> searchIssues(
                args["jql"] as? String ?: return PluginResult(false, error = "JQL required")
            )
            "transition" -> transitionIssue(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required"),
                args["status"] as? String ?: return PluginResult(false, error = "Status required"),
                args["due_date"] as? String,
                args["start_date"] as? String,
                args["resolution"] as? String
            )
            "get_transitions" -> getAvailableTransitions(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required")
            )
            "create" -> createIssue(
                projectKey = args["project"] as? String ?: return PluginResult(false, error = "Project key required"),
                summary = args["summary"] as? String ?: return PluginResult(false, error = "Summary required"),
                description = args["description"] as? String,
                issueType = args["issue_type"] as? String ?: "Task",
                priority = args["priority"] as? String,
                assignee = args["assignee"] as? String,
                reporter = args["reporter"] as? String,
                labels = (args["labels"] as? List<*>)?.filterIsInstance<String>(),
                components = (args["components"] as? List<*>)?.filterIsInstance<String>(),
                parentIssue = args["parent"] as? String,
                epicLink = args["epic_link"] as? String,
                storyPoints = args["story_points"] as? Int,
                originalEstimate = args["original_estimate"] as? String,
                startDate = args["start_date"] as? String,
                dueDate = args["due_date"] as? String,
                sprintId = args["sprint_id"] as? Int
            )
            "comment" -> addComment(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required"),
                args["comment"] as? String ?: return PluginResult(false, error = "Comment text required")
            )
            "comments" -> getComments(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required")
            )
            "assign" -> assignIssue(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required"),
                args["assignee"] as? String ?: return PluginResult(false, error = "Assignee required")
            )
            "labels" -> manageLabels(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required"),
                args["action"] as? String ?: return PluginResult(false, error = "Action (add/remove) required"),
                args["label"] as? String ?: return PluginResult(false, error = "Label required")
            )
            "link" -> linkIssues(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required"),
                args["link_type"] as? String ?: return PluginResult(false, error = "Link type required"),
                args["target_issue"] as? String ?: return PluginResult(false, error = "Target issue required")
            )
            "projects" -> listProjects()
            "boards" -> listBoards(args["project_key"] as? String)
            "sprints" -> listSprints(
                args["board_id"] as? Int ?: return PluginResult(false, error = "Board ID required")
            )
            "set_sprint" -> setIssueSprint(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required"),
                args["sprint_id"] as? String ?: args["sprint_id"]?.toString() ?: return PluginResult(false, error = "Sprint ID required (number or 'backlog')")
            )
            "search_users" -> searchUsers(
                args["query"] as? String ?: return PluginResult(false, error = "Search query required"),
                args["project_key"] as? String
            )
            "get_issue_links" -> getIssueLinks(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required")
            )
            "add_remote_link" -> addRemoteLink(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required"),
                args["url"] as? String ?: return PluginResult(false, error = "URL required"),
                args["title"] as? String ?: return PluginResult(false, error = "Title required")
            )
            "check_transition_requirements" -> checkTransitionRequirements(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required"),
                args["transition_id"] as? String ?: return PluginResult(false, error = "Transition ID required")
            )
            else -> PluginResult(false, error = "Unknown command: $command")
        }
    }

    private fun getIssue(issueKey: String): PluginResult {
        val url = "$baseUrl/rest/api/3/issue/$issueKey"

        return try {
            val response = apiGet(url)
            val issue = mapper.readValue(response, Map::class.java) as Map<String, Any>
            val fields = issue["fields"] as Map<String, Any>

            // Sprint 정보 추출 (customfield_10020는 일반적인 Sprint 필드)
            val sprintData = extractSprintInfo(fields["customfield_10020"])

            val info = mapOf(
                "key" to issue["key"],
                "summary" to fields["summary"],
                "description" to extractDescription(fields["description"]),
                "status" to (fields["status"] as? Map<*, *>)?.get("name"),
                "priority" to (fields["priority"] as? Map<*, *>)?.get("name"),
                "assignee" to (fields["assignee"] as? Map<*, *>)?.get("displayName"),
                "reporter" to (fields["reporter"] as? Map<*, *>)?.get("displayName"),
                "issuetype" to (fields["issuetype"] as? Map<*, *>)?.get("name"),
                "created" to fields["created"],
                "updated" to fields["updated"],
                "url" to "$baseUrl/browse/$issueKey",
                "sprint" to sprintData,
                "labels" to (fields["labels"] as? List<*> ?: emptyList<String>())
            )

            PluginResult(success = true, data = info)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get issue: $issueKey" }
            PluginResult(false, error = e.message)
        }
    }

    private fun getMyIssues(): PluginResult {
        val jql = "assignee=currentUser() AND resolution=Unresolved ORDER BY updated DESC"
        return searchIssues(jql)
    }

    private fun getSprintIssues(boardId: Int?, sprintId: Int?): PluginResult {
        // sprintId가 있으면 해당 스프린트의 이슈 검색
        // 없으면 현재 활성 스프린트의 이슈 검색
        val jql = if (sprintId != null) {
            "sprint=$sprintId ORDER BY rank ASC"
        } else {
            "sprint in openSprints() AND resolution=Unresolved ORDER BY rank ASC"
        }
        return searchIssues(jql)
    }

    private fun searchIssues(jql: String): PluginResult {
        // 2024년 이후 Jira Cloud는 /rest/api/3/search/jql (POST) 사용
        val url = "$baseUrl/rest/api/3/search/jql"
        val body = mapper.writeValueAsString(mapOf(
            "jql" to jql,
            "maxResults" to 20,
            "fields" to listOf("key", "summary", "status", "assignee", "priority", "issuetype")
        ))

        return try {
            val response = apiPost(url, body)
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
            val issues = result["issues"] as? List<Map<String, Any>> ?: emptyList()

            val formatted = issues.map { issue ->
                val fields = issue["fields"] as Map<String, Any>
                mapOf(
                    "key" to issue["key"],
                    "summary" to fields["summary"],
                    "status" to (fields["status"] as? Map<*, *>)?.get("name"),
                    "assignee" to (fields["assignee"] as? Map<*, *>)?.get("displayName"),
                    "priority" to (fields["priority"] as? Map<*, *>)?.get("name"),
                    "type" to (fields["issuetype"] as? Map<*, *>)?.get("name"),
                    "url" to "$baseUrl/browse/${issue["key"]}"
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${issues.size} issues"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to search issues: $jql" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 이슈에 사용 가능한 트랜지션 목록 조회
     */
    fun getAvailableTransitions(issueKey: String): PluginResult {
        val transitionsUrl = "$baseUrl/rest/api/3/issue/$issueKey/transitions"

        return try {
            val response = apiGet(transitionsUrl)
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
            val transitions = result["transitions"] as? List<Map<String, Any>> ?: emptyList()

            val transitionList = transitions.map { t ->
                mapOf(
                    "id" to (t["id"] as? String ?: ""),
                    "name" to (t["name"] as? String ?: ""),
                    "to" to ((t["to"] as? Map<*, *>)?.let { to ->
                        mapOf(
                            "id" to (to["id"] as? String ?: ""),
                            "name" to (to["name"] as? String ?: "")
                        )
                    } ?: emptyMap<String, String>())
                )
            }

            PluginResult(
                success = true,
                data = mapOf("transitions" to transitionList)
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get transitions for issue: $issueKey" }
            PluginResult(false, error = e.message)
        }
    }

    private fun transitionIssue(
        issueKey: String,
        targetStatus: String,
        dueDate: String? = null,
        startDate: String? = null,
        resolution: String? = null
    ): PluginResult {
        // 먼저 가능한 전환 조회
        val transitionsUrl = "$baseUrl/rest/api/3/issue/$issueKey/transitions?expand=transitions.fields"

        return try {
            val response = apiGet(transitionsUrl)
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
            val transitions = result["transitions"] as? List<Map<String, Any>> ?: emptyList()

            val transition = transitions.find { t ->
                (t["name"] as? String)?.equals(targetStatus, ignoreCase = true) == true
            }

            if (transition == null) {
                val available = transitions.mapNotNull { it["name"] as? String }
                return PluginResult(
                    false,
                    error = "Cannot transition to '$targetStatus'. Available: ${available.joinToString(", ")}"
                )
            }

            // 전환에 필요한 필드 확인
            val transitionFields = transition["fields"] as? Map<String, Any> ?: emptyMap()
            val resolutionField = transitionFields["resolution"] as? Map<String, Any>
            val resolutionRequired = resolutionField?.get("required") == true

            // 전환 실행 - 필수 필드 포함
            val bodyMap = mutableMapOf<String, Any>(
                "transition" to mapOf("id" to transition["id"])
            )

            // 필수 필드가 있으면 fields에 추가
            val fields = mutableMapOf<String, Any>()
            if (dueDate != null) {
                fields["duedate"] = dueDate
            }
            if (startDate != null) {
                // customfield_10015는 일반적인 Start date 커스텀 필드 ID
                // 프로젝트에 따라 다를 수 있음
                fields["customfield_10015"] = startDate
            }

            // Resolution 필드 처리
            if (resolution != null) {
                fields["resolution"] = mapOf("name" to resolution)
            } else if (resolutionRequired) {
                // Resolution이 필수이지만 지정되지 않은 경우 기본값 사용
                val targetTo = (transition["to"] as? Map<*, *>)?.get("name") as? String ?: ""
                val defaultResolution = when {
                    targetTo.contains("완료", ignoreCase = true) ||
                    targetTo.contains("Done", ignoreCase = true) -> "Done"
                    targetTo.contains("취소", ignoreCase = true) ||
                    targetTo.contains("Cancel", ignoreCase = true) -> "Won't Do"
                    else -> "Done"
                }
                fields["resolution"] = mapOf("name" to defaultResolution)
                logger.info { "Auto-setting resolution to '$defaultResolution' for transition to '$targetStatus'" }
            }

            if (fields.isNotEmpty()) {
                bodyMap["fields"] = fields
            }

            apiPost(transitionsUrl, mapper.writeValueAsString(bodyMap))

            PluginResult(
                success = true,
                message = "Issue $issueKey transitioned to $targetStatus"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to transition issue: $issueKey" }
            PluginResult(false, error = e.message)
        }
    }

    private fun apiGet(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Jira API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    private fun apiPost(url: String, body: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Jira API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    private fun extractDescription(description: Any?): String? {
        // Atlassian Document Format (ADF) 처리
        if (description is Map<*, *>) {
            val content = description["content"] as? List<Map<*, *>> ?: return null
            return content.mapNotNull { block ->
                val blockContent = block["content"] as? List<Map<*, *>>
                blockContent?.mapNotNull { it["text"] }?.joinToString(" ")
            }.joinToString("\n")
        }
        return description?.toString()
    }

    /**
     * Sprint 필드에서 정보 추출
     * Jira Cloud의 Sprint 필드는 배열 형태로, 마지막 스프린트가 현재 스프린트
     */
    private fun extractSprintInfo(sprintField: Any?): Map<String, Any?>? {
        val sprints = sprintField as? List<*> ?: return null
        if (sprints.isEmpty()) return null

        // 마지막 스프린트 (가장 최근 스프린트)
        val currentSprint = sprints.lastOrNull() as? Map<*, *> ?: return null

        return mapOf(
            "id" to currentSprint["id"],
            "name" to currentSprint["name"],
            "state" to currentSprint["state"],
            "startDate" to currentSprint["startDate"],
            "endDate" to currentSprint["endDate"]
        )
    }

    // ==================== 새로운 기능 구현 ====================

    /**
     * 새 이슈 생성
     */
    private fun createIssue(
        projectKey: String,
        summary: String,
        description: String?,
        issueType: String,
        priority: String? = null,
        assignee: String? = null,
        reporter: String? = null,
        labels: List<String>? = null,
        components: List<String>? = null,
        parentIssue: String? = null,
        epicLink: String? = null,
        storyPoints: Int? = null,
        originalEstimate: String? = null,
        startDate: String? = null,
        dueDate: String? = null,
        sprintId: Int? = null
    ): PluginResult {
        val url = "$baseUrl/rest/api/3/issue"

        // Atlassian Document Format (ADF) 형식으로 description 변환
        val descriptionAdf = if (description != null) {
            mapOf(
                "type" to "doc",
                "version" to 1,
                "content" to listOf(
                    mapOf(
                        "type" to "paragraph",
                        "content" to listOf(
                            mapOf("type" to "text", "text" to description)
                        )
                    )
                )
            )
        } else null

        val fields = mutableMapOf<String, Any>(
            "project" to mapOf("key" to projectKey),
            "summary" to summary,
            "issuetype" to mapOf("name" to issueType)
        )

        // Optional fields
        descriptionAdf?.let { fields["description"] = it }
        priority?.let { fields["priority"] = mapOf("name" to it) }
        assignee?.let { fields["assignee"] = mapOf("accountId" to it) }
        reporter?.let { fields["reporter"] = mapOf("accountId" to it) }
        labels?.takeIf { it.isNotEmpty() }?.let { fields["labels"] = it }
        components?.takeIf { it.isNotEmpty() }?.let {
            fields["components"] = it.map { name -> mapOf("name" to name) }
        }

        // Parent issue for Sub-tasks
        parentIssue?.let { fields["parent"] = mapOf("key" to it) }

        // Epic link (customfield - may vary by Jira instance)
        epicLink?.let { fields["customfield_10014"] = it }  // Epic Link field

        // Story points (customfield - may vary by Jira instance)
        storyPoints?.let { fields["customfield_10016"] = it }  // Story Points field

        // Time tracking
        originalEstimate?.let { fields["timetracking"] = mapOf("originalEstimate" to it) }

        // Dates
        startDate?.let { fields["customfield_10015"] = it }  // Start date (if custom field)
        dueDate?.let { fields["duedate"] = it }

        val body = mapOf("fields" to fields)

        return try {
            val response = apiPost(url, mapper.writeValueAsString(body))
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val issueKey = result["key"] as? String
            val issueId = result["id"] as? String

            // Sprint 설정 (이슈 생성 후 별도 API로 설정)
            if (sprintId != null && issueId != null) {
                try {
                    val sprintUrl = "$baseUrl/rest/agile/1.0/sprint/$sprintId/issue"
                    val sprintBody = mapOf("issues" to listOf(issueKey))
                    apiPost(sprintUrl, mapper.writeValueAsString(sprintBody))
                    logger.info { "Added issue $issueKey to sprint $sprintId" }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to add issue to sprint $sprintId, but issue was created" }
                }
            }

            PluginResult(
                success = true,
                data = mapOf(
                    "key" to issueKey,
                    "id" to issueId,
                    "url" to "$baseUrl/browse/$issueKey"
                ),
                message = "Issue $issueKey created successfully"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create issue in $projectKey" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 이슈에 댓글 추가
     */
    private fun addComment(issueKey: String, comment: String): PluginResult {
        val url = "$baseUrl/rest/api/3/issue/$issueKey/comment"

        // ADF 형식으로 댓글 변환
        val body = mapOf(
            "body" to mapOf(
                "type" to "doc",
                "version" to 1,
                "content" to listOf(
                    mapOf(
                        "type" to "paragraph",
                        "content" to listOf(
                            mapOf("type" to "text", "text" to comment)
                        )
                    )
                )
            )
        )

        return try {
            val response = apiPost(url, mapper.writeValueAsString(body))
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>

            PluginResult(
                success = true,
                data = mapOf(
                    "id" to result["id"],
                    "created" to result["created"]
                ),
                message = "Comment added to $issueKey"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to add comment to $issueKey" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 이슈 댓글 조회
     */
    private fun getComments(issueKey: String): PluginResult {
        val url = "$baseUrl/rest/api/3/issue/$issueKey/comment?orderBy=-created&maxResults=10"

        return try {
            val response = apiGet(url)
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
            val comments = result["comments"] as? List<Map<String, Any>> ?: emptyList()

            val formatted = comments.map { comment ->
                val body = extractDescription(comment["body"])
                val author = comment["author"] as? Map<String, Any>
                mapOf(
                    "id" to comment["id"],
                    "author" to (author?.get("displayName") ?: "Unknown"),
                    "body" to body,
                    "created" to comment["created"],
                    "updated" to comment["updated"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${formatted.size} comments"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get comments for $issueKey" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 이슈 담당자 변경
     */
    private fun assignIssue(issueKey: String, assignee: String): PluginResult {
        val url = "$baseUrl/rest/api/3/issue/$issueKey/assignee"

        // assignee가 이메일 형식이면 accountId 조회 필요
        val accountId = if (assignee.contains("@")) {
            findUserAccountId(assignee) ?: return PluginResult(false, error = "User not found: $assignee")
        } else {
            assignee
        }

        val body = mapOf("accountId" to accountId)

        return try {
            apiPut(url, mapper.writeValueAsString(body))
            PluginResult(
                success = true,
                message = "$issueKey assigned to $assignee"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to assign $issueKey to $assignee" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 이메일로 사용자 accountId 찾기
     */
    private fun findUserAccountId(email: String): String? {
        val encodedEmail = java.net.URLEncoder.encode(email, "UTF-8")
        val url = "$baseUrl/rest/api/3/user/search?query=$encodedEmail"

        return try {
            val response = apiGet(url)
            val users = mapper.readValue(response, List::class.java) as? List<Map<String, Any>>
            users?.firstOrNull()?.get("accountId") as? String
        } catch (e: Exception) {
            logger.warn(e) { "Failed to find user: $email" }
            null
        }
    }

    /**
     * 라벨 추가/제거
     */
    private fun manageLabels(issueKey: String, action: String, label: String): PluginResult {
        val url = "$baseUrl/rest/api/3/issue/$issueKey"

        val update = when (action.lowercase()) {
            "add" -> mapOf("labels" to listOf(mapOf("add" to label)))
            "remove" -> mapOf("labels" to listOf(mapOf("remove" to label)))
            else -> return PluginResult(false, error = "Invalid action: $action. Use 'add' or 'remove'")
        }

        val body = mapOf("update" to update)

        return try {
            apiPut(url, mapper.writeValueAsString(body))
            PluginResult(
                success = true,
                message = "Label '$label' ${action}ed on $issueKey"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to $action label '$label' on $issueKey" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 이슈 링크 생성
     */
    private fun linkIssues(issueKey: String, linkType: String, targetIssue: String): PluginResult {
        val url = "$baseUrl/rest/api/3/issueLink"

        // 링크 타입 매핑 (일반적인 표현 → Jira 링크 타입)
        val jiraLinkType = when (linkType.lowercase()) {
            "blocks", "block" -> "Blocks"
            "is blocked by", "blocked" -> "Blocks"
            "relates", "relates to", "related" -> "Relates"
            "duplicates", "duplicate" -> "Duplicate"
            "is duplicated by" -> "Duplicate"
            "clones", "clone" -> "Cloners"
            else -> linkType // 직접 지정
        }

        val body = mapOf(
            "type" to mapOf("name" to jiraLinkType),
            "inwardIssue" to mapOf("key" to issueKey),
            "outwardIssue" to mapOf("key" to targetIssue)
        )

        return try {
            apiPost(url, mapper.writeValueAsString(body))
            PluginResult(
                success = true,
                message = "$issueKey linked to $targetIssue (${jiraLinkType})"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to link $issueKey to $targetIssue" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 프로젝트 목록 조회 (페이지네이션으로 전체 조회)
     */
    private fun listProjects(): PluginResult {
        return try {
            val allProjects = mutableListOf<Map<String, Any?>>()
            var startAt = 0
            val maxResults = 100

            // 페이지네이션으로 모든 프로젝트 조회
            while (true) {
                val url = "$baseUrl/rest/api/3/project/search?startAt=$startAt&maxResults=$maxResults&orderBy=name"
                val response = apiGet(url)
                val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
                val projects = result["values"] as? List<Map<String, Any>> ?: emptyList()
                val total = (result["total"] as? Number)?.toInt() ?: projects.size

                projects.forEach { project ->
                    // 아바타 URL 추출
                    val avatarUrls = project["avatarUrls"] as? Map<*, *>
                    val avatarUrl = avatarUrls?.get("24x24") as? String
                        ?: avatarUrls?.get("48x48") as? String

                    allProjects.add(mapOf(
                        "key" to project["key"],
                        "name" to project["name"],
                        "projectTypeKey" to project["projectTypeKey"],
                        "style" to project["style"],
                        "url" to "$baseUrl/browse/${project["key"]}",
                        "avatarUrl" to avatarUrl
                    ))
                }

                startAt += projects.size
                if (startAt >= total || projects.isEmpty()) break
            }

            PluginResult(
                success = true,
                data = allProjects,
                message = "Found ${allProjects.size} projects"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list projects" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 보드 목록 조회 (Agile API) - 페이지네이션 지원
     */
    private fun listBoards(projectKey: String?): PluginResult {
        return try {
            val allBoards = mutableListOf<Map<String, Any?>>()
            var startAt = 0
            val maxResults = 100

            while (true) {
                var url = "$baseUrl/rest/agile/1.0/board?startAt=$startAt&maxResults=$maxResults"
                if (projectKey != null) {
                    url += "&projectKeyOrId=$projectKey"
                }

                val response = apiGet(url)
                val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
                val boards = result["values"] as? List<Map<String, Any>> ?: emptyList()
                val total = (result["total"] as? Number)?.toInt() ?: boards.size
                val isLast = result["isLast"] as? Boolean ?: true

                boards.forEach { board ->
                    val location = board["location"] as? Map<String, Any>
                    allBoards.add(mapOf(
                        "id" to board["id"],
                        "name" to board["name"],
                        "type" to board["type"], // scrum, kanban
                        "projectKey" to location?.get("projectKey"),
                        "projectName" to location?.get("name")
                    ))
                }

                startAt += boards.size
                if (isLast || startAt >= total || boards.isEmpty()) break
            }

            PluginResult(
                success = true,
                data = allBoards,
                message = "Found ${allBoards.size} boards"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list boards" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 스프린트 목록 조회 (Agile API)
     */
    private fun listSprints(boardId: Int): PluginResult {
        return try {
            val allSprints = mutableListOf<Map<String, Any?>>()
            var startAt = 0
            val maxResults = 50

            while (true) {
                val url = "$baseUrl/rest/agile/1.0/board/$boardId/sprint?startAt=$startAt&maxResults=$maxResults"

                val response = apiGet(url)
                val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
                val sprints = result["values"] as? List<Map<String, Any>> ?: emptyList()
                val total = (result["total"] as? Number)?.toInt() ?: sprints.size
                val isLast = result["isLast"] as? Boolean ?: true

                sprints.forEach { sprint ->
                    allSprints.add(mapOf(
                        "id" to sprint["id"],
                        "name" to sprint["name"],
                        "state" to sprint["state"], // active, closed, future
                        "startDate" to sprint["startDate"],
                        "endDate" to sprint["endDate"],
                        "completeDate" to sprint["completeDate"],
                        "goal" to sprint["goal"]
                    ))
                }

                startAt += sprints.size
                if (isLast || startAt >= total || sprints.isEmpty()) break
            }

            // 상태 순서대로 정렬: active > future > closed
            val stateOrder = mapOf("active" to 0, "future" to 1, "closed" to 2)
            allSprints.sortBy { stateOrder[it["state"] as? String] ?: 3 }

            PluginResult(
                success = true,
                data = allSprints,
                message = "Found ${allSprints.size} sprints"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list sprints for board: $boardId" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 이슈의 스프린트 설정/변경
     *
     * @param issueKey 이슈 키 (예: PROJ-123)
     * @param sprintIdOrBacklog 스프린트 ID 또는 "backlog" (백로그로 이동)
     */
    private fun setIssueSprint(issueKey: String, sprintIdOrBacklog: String): PluginResult {
        return try {
            if (sprintIdOrBacklog.lowercase() == "backlog") {
                // 백로그로 이동 - 스프린트에서 이슈 제거
                // 먼저 현재 이슈의 스프린트 정보 확인 필요
                val issueUrl = "$baseUrl/rest/api/3/issue/$issueKey?fields=customfield_10020"
                val issueResponse = apiGet(issueUrl)
                val issueData = mapper.readValue(issueResponse, Map::class.java) as Map<String, Any>
                val fields = issueData["fields"] as? Map<String, Any> ?: emptyMap()

                // customfield_10020는 일반적인 Sprint 필드 ID (Jira 인스턴스마다 다를 수 있음)
                val sprintField = fields["customfield_10020"] as? List<*>
                if (sprintField.isNullOrEmpty()) {
                    return PluginResult(
                        success = true,
                        message = "Issue $issueKey is already in backlog"
                    )
                }

                // 현재 활성 스프린트에서 이슈 제거
                val currentSprint = sprintField.lastOrNull() as? Map<*, *>
                val currentSprintId = currentSprint?.get("id")?.toString()?.toIntOrNull()

                if (currentSprintId != null) {
                    val removeUrl = "$baseUrl/rest/agile/1.0/sprint/$currentSprintId/issue"
                    val removeBody = mapOf("issues" to listOf(issueKey))

                    // POST로 이슈 제거가 안 되면 백로그 이동 API 사용
                    try {
                        // Jira Agile API: backlog로 이동
                        val backlogUrl = "$baseUrl/rest/agile/1.0/backlog/issue"
                        val backlogBody = mapOf("issues" to listOf(issueKey))
                        apiPost(backlogUrl, mapper.writeValueAsString(backlogBody))
                    } catch (e: Exception) {
                        logger.warn { "Backlog move API failed, trying alternative: ${e.message}" }
                        // 대안: Sprint 필드를 null로 설정
                        val updateUrl = "$baseUrl/rest/api/3/issue/$issueKey"
                        val updateBody = mapOf("fields" to mapOf("customfield_10020" to null))
                        apiPut(updateUrl, mapper.writeValueAsString(updateBody))
                    }
                }

                PluginResult(
                    success = true,
                    message = "Issue $issueKey moved to backlog"
                )
            } else {
                // 특정 스프린트로 이동
                val sprintId = sprintIdOrBacklog.toIntOrNull()
                    ?: return PluginResult(false, error = "Invalid sprint ID: $sprintIdOrBacklog")

                val sprintUrl = "$baseUrl/rest/agile/1.0/sprint/$sprintId/issue"
                val body = mapOf("issues" to listOf(issueKey))
                apiPost(sprintUrl, mapper.writeValueAsString(body))

                PluginResult(
                    success = true,
                    data = mapOf(
                        "issueKey" to issueKey,
                        "sprintId" to sprintId
                    ),
                    message = "Issue $issueKey added to sprint $sprintId"
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to set sprint for issue: $issueKey" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 사용자 검색
     */
    private fun searchUsers(query: String, projectKey: String?): PluginResult {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            // projectKey가 있으면 해당 프로젝트에 할당 가능한 사용자만 검색
            val url = if (projectKey != null) {
                "$baseUrl/rest/api/3/user/assignable/search?query=$encodedQuery&project=$projectKey&maxResults=20"
            } else {
                "$baseUrl/rest/api/3/user/search?query=$encodedQuery&maxResults=20"
            }

            val response = apiGet(url)
            val users = mapper.readValue(response, List::class.java) as? List<Map<String, Any>> ?: emptyList()

            val formatted = users.map { user ->
                val avatarUrls = user["avatarUrls"] as? Map<*, *>
                mapOf(
                    "accountId" to (user["accountId"] as? String ?: ""),
                    "displayName" to (user["displayName"] as? String ?: ""),
                    "emailAddress" to (user["emailAddress"] as? String ?: ""),
                    "avatarUrl" to (avatarUrls?.get("24x24") as? String ?: "")
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${formatted.size} users"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to search users: $query" }
            PluginResult(false, error = e.message)
        }
    }

    // ==================== HTTP Helper ====================

    private fun apiPut(url: String, body: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        // PUT 요청은 204 No Content 반환 가능
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Jira API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body() ?: ""
    }

    // ==================== 이슈 링크 관련 기능 ====================

    /**
     * 이슈의 연결된 링크 조회 (Issue Links + Remote Links)
     */
    private fun getIssueLinks(issueKey: String): PluginResult {
        return try {
            // 1. Issue Links (다른 Jira 이슈와의 연결)
            val issueUrl = "$baseUrl/rest/api/3/issue/$issueKey?fields=issuelinks"
            val issueResponse = apiGet(issueUrl)
            val issueData = mapper.readValue(issueResponse, Map::class.java) as Map<String, Any>
            val fields = issueData["fields"] as? Map<String, Any> ?: emptyMap()
            val issueLinks = fields["issuelinks"] as? List<Map<String, Any>> ?: emptyList()

            val formattedIssueLinks = issueLinks.map { link ->
                val linkType = link["type"] as? Map<String, Any>
                val inwardIssue = link["inwardIssue"] as? Map<String, Any>
                val outwardIssue = link["outwardIssue"] as? Map<String, Any>
                val linkedIssue = inwardIssue ?: outwardIssue
                val linkedFields = linkedIssue?.get("fields") as? Map<String, Any>

                mapOf(
                    "type" to "issue_link",
                    "linkType" to (linkType?.get("name") ?: "Unknown"),
                    "direction" to if (inwardIssue != null) "inward" else "outward",
                    "linkedIssueKey" to (linkedIssue?.get("key") ?: ""),
                    "linkedIssueSummary" to (linkedFields?.get("summary") ?: ""),
                    "linkedIssueStatus" to ((linkedFields?.get("status") as? Map<*, *>)?.get("name") ?: "")
                )
            }

            // 2. Remote Links (외부 URL: GitLab MR, GitHub PR 등)
            val remoteLinksUrl = "$baseUrl/rest/api/3/issue/$issueKey/remotelink"
            val remoteLinks = try {
                val remoteResponse = apiGet(remoteLinksUrl)
                val links = mapper.readValue(remoteResponse, List::class.java) as? List<Map<String, Any>> ?: emptyList()
                links.map { link ->
                    val linkObject = link["object"] as? Map<String, Any> ?: emptyMap()
                    val icon = linkObject["icon"] as? Map<String, Any>
                    mapOf(
                        "type" to "remote_link",
                        "id" to (link["id"] ?: ""),
                        "globalId" to (link["globalId"] ?: ""),
                        "title" to (linkObject["title"] ?: ""),
                        "url" to (linkObject["url"] ?: ""),
                        "iconUrl" to (icon?.get("url16x16") ?: ""),
                        "summary" to (linkObject["summary"] ?: "")
                    )
                }
            } catch (e: Exception) {
                logger.warn { "Failed to get remote links for $issueKey: ${e.message}" }
                emptyList()
            }

            // MR/PR 여부 확인
            val hasMRLink = remoteLinks.any { link ->
                val url = link["url"]?.toString()?.lowercase() ?: ""
                url.contains("merge_request") || url.contains("pull") || url.contains("/mr/")
            }

            PluginResult(
                success = true,
                data = mapOf(
                    "issueKey" to issueKey,
                    "issueLinks" to formattedIssueLinks,
                    "remoteLinks" to remoteLinks,
                    "hasMRLink" to hasMRLink,
                    "totalLinks" to (formattedIssueLinks.size + remoteLinks.size)
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get issue links: $issueKey" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 이슈에 웹 링크(Remote Link) 추가
     * GitLab MR, GitHub PR 등 외부 URL을 연결
     */
    private fun addRemoteLink(issueKey: String, url: String, title: String): PluginResult {
        val remoteLinksUrl = "$baseUrl/rest/api/3/issue/$issueKey/remotelink"

        // URL에서 아이콘 URL 추론
        val iconUrl = when {
            url.contains("gitlab") -> "https://gitlab.com/favicon.ico"
            url.contains("github") -> "https://github.com/favicon.ico"
            url.contains("bitbucket") -> "https://bitbucket.org/favicon.ico"
            else -> null
        }

        // 링크 타입 추론
        val relationship = when {
            url.contains("merge_request") || url.contains("/mr/") -> "Merge Request"
            url.contains("pull") -> "Pull Request"
            url.contains("commit") -> "Commit"
            url.contains("issue") -> "Issue"
            else -> "Related Link"
        }

        val body = mutableMapOf<String, Any>(
            "globalId" to "link-${System.currentTimeMillis()}",
            "relationship" to relationship,
            "object" to mutableMapOf<String, Any>(
                "url" to url,
                "title" to title
            )
        )

        // 아이콘 추가
        if (iconUrl != null) {
            (body["object"] as MutableMap<String, Any>)["icon"] = mapOf(
                "url16x16" to iconUrl,
                "title" to relationship
            )
        }

        return try {
            val response = apiPost(remoteLinksUrl, mapper.writeValueAsString(body))
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>

            PluginResult(
                success = true,
                data = mapOf(
                    "id" to result["id"],
                    "issueKey" to issueKey,
                    "url" to url,
                    "title" to title
                ),
                message = "Remote link added to $issueKey: $title"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to add remote link to $issueKey" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 트랜지션 실행 가능 여부 사전 검증
     * ScriptRunner 등의 Validator 조건을 미리 확인
     */
    private fun checkTransitionRequirements(issueKey: String, transitionId: String): PluginResult {
        return try {
            // 1. 트랜지션 정보 조회 (필수 필드 확인)
            val transitionsUrl = "$baseUrl/rest/api/3/issue/$issueKey/transitions?expand=transitions.fields"
            val transitionsResponse = apiGet(transitionsUrl)
            val transitionsData = mapper.readValue(transitionsResponse, Map::class.java) as Map<String, Any>
            val transitions = transitionsData["transitions"] as? List<Map<String, Any>> ?: emptyList()

            val targetTransition = transitions.find { it["id"] == transitionId }
                ?: return PluginResult(false, error = "Transition $transitionId not found")

            val transitionName = targetTransition["name"] as? String ?: "Unknown"
            val requiredFields = targetTransition["fields"] as? Map<String, Any> ?: emptyMap()

            // 2. 이슈 현재 상태 확인 (링크, 필수 필드 등)
            val issueUrl = "$baseUrl/rest/api/3/issue/$issueKey?fields=issuelinks,resolution,fixVersions,assignee,reporter"
            val issueResponse = apiGet(issueUrl)
            val issueData = mapper.readValue(issueResponse, Map::class.java) as Map<String, Any>
            val fields = issueData["fields"] as? Map<String, Any> ?: emptyMap()

            // 3. Remote Links 확인 (MR/PR 연결 여부)
            val remoteLinksUrl = "$baseUrl/rest/api/3/issue/$issueKey/remotelink"
            val remoteLinks = try {
                val remoteResponse = apiGet(remoteLinksUrl)
                mapper.readValue(remoteResponse, List::class.java) as? List<Map<String, Any>> ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            val hasMRLink = remoteLinks.any { link ->
                val linkObject = link["object"] as? Map<String, Any> ?: emptyMap()
                val url = linkObject["url"]?.toString()?.lowercase() ?: ""
                url.contains("merge_request") || url.contains("pull") || url.contains("/mr/")
            }

            // 4. 검증 결과 수집
            val requirements = mutableListOf<Map<String, Any>>()
            val missingRequirements = mutableListOf<Map<String, Any>>()

            // 필수 필드 확인
            requiredFields.forEach { (fieldId, fieldConfig) ->
                val config = fieldConfig as? Map<String, Any> ?: return@forEach
                val isRequired = config["required"] as? Boolean ?: false
                val fieldName = config["name"] as? String ?: fieldId

                if (isRequired) {
                    requirements.add(mapOf(
                        "type" to "field",
                        "fieldId" to fieldId,
                        "fieldName" to fieldName,
                        "required" to true
                    ))
                }
            }

            // MR 링크 확인 (일반적인 "완료" 트랜지션에서 필요)
            // transitionName이 "End Integration", "Done", "완료" 등인 경우 MR 링크 권장
            val requiresMR = transitionName.lowercase().let {
                it.contains("integration") || it.contains("done") || it.contains("완료") || it.contains("close")
            }

            if (requiresMR) {
                requirements.add(mapOf(
                    "type" to "link",
                    "linkType" to "MR/PR",
                    "description" to "Merge Request 또는 Pull Request 연결",
                    "satisfied" to hasMRLink
                ))

                if (!hasMRLink) {
                    missingRequirements.add(mapOf(
                        "type" to "link",
                        "linkType" to "MR/PR",
                        "description" to "코드 변경 사항을 검증하기 위해 MR/PR 연결이 필요합니다"
                    ))
                }
            }

            PluginResult(
                success = true,
                data = mapOf(
                    "issueKey" to issueKey,
                    "transitionId" to transitionId,
                    "transitionName" to transitionName,
                    "canTransition" to missingRequirements.isEmpty(),
                    "requirements" to requirements,
                    "missingRequirements" to missingRequirements,
                    "hasMRLink" to hasMRLink
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to check transition requirements: $issueKey -> $transitionId" }
            PluginResult(false, error = e.message)
        }
    }
}
