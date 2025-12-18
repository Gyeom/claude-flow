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
            examples = listOf("/jira issue CCDC-123")
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
            examples = listOf("/jira search project=CCDC AND status=Open")
        ),
        PluginCommand(
            name = "transition",
            description = "이슈 상태 변경",
            usage = "/jira transition <issue-key> <status>",
            examples = listOf("/jira transition CCDC-123 Done")
        ),
        PluginCommand(
            name = "create",
            description = "새 이슈 생성",
            usage = "/jira create <project> <summary> [description]",
            examples = listOf("/jira create CCDC \"버그 수정 필요\" \"로그인 실패 이슈\"")
        ),
        PluginCommand(
            name = "comment",
            description = "이슈에 댓글 추가",
            usage = "/jira comment <issue-key> <comment>",
            examples = listOf("/jira comment CCDC-123 \"분석 완료, PR 준비중\"")
        ),
        PluginCommand(
            name = "comments",
            description = "이슈 댓글 조회",
            usage = "/jira comments <issue-key>",
            examples = listOf("/jira comments CCDC-123")
        ),
        PluginCommand(
            name = "assign",
            description = "이슈 담당자 변경",
            usage = "/jira assign <issue-key> <account-id|email>",
            examples = listOf("/jira assign CCDC-123 user@example.com")
        ),
        PluginCommand(
            name = "labels",
            description = "이슈 라벨 추가/제거",
            usage = "/jira labels <issue-key> <add|remove> <label>",
            examples = listOf("/jira labels CCDC-123 add ai:analyzed")
        ),
        PluginCommand(
            name = "link",
            description = "이슈 링크 생성 (relates to, blocks 등)",
            usage = "/jira link <issue-key> <link-type> <target-issue>",
            examples = listOf("/jira link CCDC-123 blocks CCDC-456")
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
            examples = listOf("/jira boards", "/jira boards CCDC")
        ),
        PluginCommand(
            name = "sprints",
            description = "보드의 스프린트 목록 조회",
            usage = "/jira sprints <board-id>",
            examples = listOf("/jira sprints 123")
        )
    )

    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private lateinit var baseUrl: String
    private lateinit var authHeader: String

    override suspend fun initialize(config: Map<String, String>) {
        super.initialize(config)
        baseUrl = requireConfig("JIRA_URL").trimEnd('/')
        val email = requireConfig("JIRA_EMAIL")
        val apiToken = requireConfig("JIRA_API_TOKEN")

        // Basic Auth 헤더 생성
        val credentials = "$email:$apiToken"
        authHeader = "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}"

        logger.info { "Jira plugin initialized: $baseUrl" }
    }

    override fun shouldHandle(message: String): Boolean {
        val lower = message.lowercase()
        return lower.startsWith("/jira") ||
                lower.matches(Regex(".*[A-Z]+-\\d+.*")) ||  // 이슈 키 패턴 (CCDC-123)
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
                args["start_date"] as? String
            )
            "create" -> createIssue(
                args["project"] as? String ?: return PluginResult(false, error = "Project key required"),
                args["summary"] as? String ?: return PluginResult(false, error = "Summary required"),
                args["description"] as? String,
                args["issue_type"] as? String ?: "Task"
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
            else -> PluginResult(false, error = "Unknown command: $command")
        }
    }

    private fun getIssue(issueKey: String): PluginResult {
        val url = "$baseUrl/rest/api/3/issue/$issueKey"

        return try {
            val response = apiGet(url)
            val issue = mapper.readValue(response, Map::class.java) as Map<String, Any>
            val fields = issue["fields"] as Map<String, Any>

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
                "url" to "$baseUrl/browse/$issueKey"
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

    private fun transitionIssue(
        issueKey: String,
        targetStatus: String,
        dueDate: String? = null,
        startDate: String? = null
    ): PluginResult {
        // 먼저 가능한 전환 조회
        val transitionsUrl = "$baseUrl/rest/api/3/issue/$issueKey/transitions"

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

    // ==================== 새로운 기능 구현 ====================

    /**
     * 새 이슈 생성
     */
    private fun createIssue(
        projectKey: String,
        summary: String,
        description: String?,
        issueType: String
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

        if (descriptionAdf != null) {
            fields["description"] = descriptionAdf
        }

        val body = mapOf("fields" to fields)

        return try {
            val response = apiPost(url, mapper.writeValueAsString(body))
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val issueKey = result["key"] as? String
            PluginResult(
                success = true,
                data = mapOf(
                    "key" to issueKey,
                    "id" to result["id"],
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
}
