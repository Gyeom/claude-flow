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

    private fun encodeProject(project: String): String {
        return java.net.URLEncoder.encode(project, "UTF-8")
    }
}
