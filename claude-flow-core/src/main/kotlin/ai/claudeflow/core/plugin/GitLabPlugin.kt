package ai.claudeflow.core.plugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
 */
class GitLabPlugin : BasePlugin() {
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
        )
    )

    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private lateinit var baseUrl: String
    private lateinit var token: String

    override suspend fun initialize(config: Map<String, String>) {
        super.initialize(config)
        baseUrl = requireConfig("GITLAB_URL").trimEnd('/')
        token = requireConfig("GITLAB_TOKEN")
        logger.info { "GitLab plugin initialized: $baseUrl" }
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
            "mr-info" -> getMergeRequestInfo(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                args["mr_id"] as? Int ?: return PluginResult(false, error = "MR ID required")
            )
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
                    "created_at" to mr["created_at"]
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

    private fun encodeProject(project: String): String {
        return java.net.URLEncoder.encode(project, "UTF-8")
    }
}
