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
 * GitHub 플러그인
 *
 * GitHub API를 통한 PR, 이슈, Actions 관리
 */
class GitHubPlugin : BasePlugin() {
    override val id = "github"
    override val name = "GitHub"
    override val description = "GitHub PR, 이슈, Actions 관리"

    override val commands = listOf(
        PluginCommand(
            name = "pr-list",
            description = "오픈된 PR 목록 조회",
            usage = "/github pr-list <owner/repo>",
            examples = listOf("/github pr-list octocat/Hello-World")
        ),
        PluginCommand(
            name = "pr-info",
            description = "PR 상세 정보 조회",
            usage = "/github pr-info <owner/repo> <pr_number>",
            examples = listOf("/github pr-info octocat/Hello-World 123")
        ),
        PluginCommand(
            name = "issues",
            description = "이슈 목록 조회",
            usage = "/github issues <owner/repo> [state]",
            examples = listOf("/github issues octocat/Hello-World open")
        ),
        PluginCommand(
            name = "issue-info",
            description = "이슈 상세 정보 조회",
            usage = "/github issue-info <owner/repo> <issue_number>",
            examples = listOf("/github issue-info octocat/Hello-World 42")
        ),
        PluginCommand(
            name = "actions",
            description = "GitHub Actions 워크플로우 조회",
            usage = "/github actions <owner/repo>",
            examples = listOf("/github actions octocat/Hello-World")
        ),
        PluginCommand(
            name = "repos",
            description = "사용자/조직의 리포지토리 목록",
            usage = "/github repos [owner]",
            examples = listOf("/github repos", "/github repos octocat")
        )
    )

    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private lateinit var token: String
    private val baseUrl = "https://api.github.com"

    override suspend fun initialize(config: Map<String, String>) {
        super.initialize(config)
        token = requireConfig("GITHUB_TOKEN")
        logger.info { "GitHub plugin initialized" }
    }

    override fun shouldHandle(message: String): Boolean {
        val lower = message.lowercase()
        return lower.startsWith("/github") ||
                lower.contains("github") && (lower.contains("pr") || lower.contains("이슈") || lower.contains("issue")) ||
                lower.contains("pull request") ||
                lower.matches(Regex(".*#\\d+.*")) && lower.contains("github")
    }

    override suspend fun execute(command: String, args: Map<String, Any>): PluginResult {
        return when (command) {
            "pr-list" -> listPullRequests(
                args["repo"] as? String ?: return PluginResult(false, error = "Repository required (owner/repo)")
            )
            "pr-info" -> getPullRequestInfo(
                args["repo"] as? String ?: return PluginResult(false, error = "Repository required"),
                args["pr_number"] as? Int ?: return PluginResult(false, error = "PR number required")
            )
            "issues" -> listIssues(
                args["repo"] as? String ?: return PluginResult(false, error = "Repository required"),
                args["state"] as? String ?: "open"
            )
            "issue-info" -> getIssueInfo(
                args["repo"] as? String ?: return PluginResult(false, error = "Repository required"),
                args["issue_number"] as? Int ?: return PluginResult(false, error = "Issue number required")
            )
            "actions" -> listWorkflowRuns(
                args["repo"] as? String ?: return PluginResult(false, error = "Repository required")
            )
            "repos" -> listRepositories(args["owner"] as? String)
            else -> PluginResult(false, error = "Unknown command: $command")
        }
    }

    private fun listPullRequests(repo: String): PluginResult {
        val url = "$baseUrl/repos/$repo/pulls?state=open&per_page=10"

        return try {
            val response = apiGet(url)
            val prs = mapper.readValue(response, List::class.java) as List<Map<String, Any>>

            val formatted = prs.map { pr ->
                mapOf(
                    "number" to pr["number"],
                    "title" to pr["title"],
                    "state" to pr["state"],
                    "user" to (pr["user"] as? Map<*, *>)?.get("login"),
                    "head" to (pr["head"] as? Map<*, *>)?.get("ref"),
                    "base" to (pr["base"] as? Map<*, *>)?.get("ref"),
                    "html_url" to pr["html_url"],
                    "created_at" to pr["created_at"],
                    "draft" to pr["draft"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${prs.size} open pull requests"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list PRs for $repo" }
            PluginResult(false, error = e.message)
        }
    }

    private fun getPullRequestInfo(repo: String, prNumber: Int): PluginResult {
        val url = "$baseUrl/repos/$repo/pulls/$prNumber"

        return try {
            val response = apiGet(url)
            val pr = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val info = mapOf(
                "number" to pr["number"],
                "title" to pr["title"],
                "body" to (pr["body"] as? String)?.take(500),
                "state" to pr["state"],
                "user" to (pr["user"] as? Map<*, *>)?.get("login"),
                "head" to (pr["head"] as? Map<*, *>)?.get("ref"),
                "base" to (pr["base"] as? Map<*, *>)?.get("ref"),
                "mergeable" to pr["mergeable"],
                "mergeable_state" to pr["mergeable_state"],
                "additions" to pr["additions"],
                "deletions" to pr["deletions"],
                "changed_files" to pr["changed_files"],
                "html_url" to pr["html_url"],
                "created_at" to pr["created_at"],
                "updated_at" to pr["updated_at"]
            )

            PluginResult(success = true, data = info)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get PR info: $repo#$prNumber" }
            PluginResult(false, error = e.message)
        }
    }

    private fun listIssues(repo: String, state: String): PluginResult {
        val url = "$baseUrl/repos/$repo/issues?state=$state&per_page=10"

        return try {
            val response = apiGet(url)
            val issues = mapper.readValue(response, List::class.java) as List<Map<String, Any>>

            // PR 제외 (GitHub API는 PR도 issue로 반환)
            val filtered = issues.filter { it["pull_request"] == null }

            val formatted = filtered.map { issue ->
                mapOf(
                    "number" to issue["number"],
                    "title" to issue["title"],
                    "state" to issue["state"],
                    "user" to (issue["user"] as? Map<*, *>)?.get("login"),
                    "labels" to (issue["labels"] as? List<Map<*, *>>)?.mapNotNull { it["name"] },
                    "html_url" to issue["html_url"],
                    "created_at" to issue["created_at"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${filtered.size} issues"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list issues for $repo" }
            PluginResult(false, error = e.message)
        }
    }

    private fun getIssueInfo(repo: String, issueNumber: Int): PluginResult {
        val url = "$baseUrl/repos/$repo/issues/$issueNumber"

        return try {
            val response = apiGet(url)
            val issue = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val info = mapOf(
                "number" to issue["number"],
                "title" to issue["title"],
                "body" to (issue["body"] as? String)?.take(500),
                "state" to issue["state"],
                "user" to (issue["user"] as? Map<*, *>)?.get("login"),
                "assignees" to (issue["assignees"] as? List<Map<*, *>>)?.mapNotNull { it["login"] },
                "labels" to (issue["labels"] as? List<Map<*, *>>)?.mapNotNull { it["name"] },
                "comments" to issue["comments"],
                "html_url" to issue["html_url"],
                "created_at" to issue["created_at"],
                "updated_at" to issue["updated_at"]
            )

            PluginResult(success = true, data = info)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get issue info: $repo#$issueNumber" }
            PluginResult(false, error = e.message)
        }
    }

    private fun listWorkflowRuns(repo: String): PluginResult {
        val url = "$baseUrl/repos/$repo/actions/runs?per_page=10"

        return try {
            val response = apiGet(url)
            val result = mapper.readValue(response, Map::class.java) as Map<String, Any>
            val runs = result["workflow_runs"] as? List<Map<String, Any>> ?: emptyList()

            val formatted = runs.map { run ->
                mapOf(
                    "id" to run["id"],
                    "name" to run["name"],
                    "status" to run["status"],
                    "conclusion" to run["conclusion"],
                    "branch" to run["head_branch"],
                    "event" to run["event"],
                    "html_url" to run["html_url"],
                    "created_at" to run["created_at"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${runs.size} workflow runs"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list workflow runs for $repo" }
            PluginResult(false, error = e.message)
        }
    }

    private fun listRepositories(owner: String?): PluginResult {
        val url = if (owner != null) {
            "$baseUrl/users/$owner/repos?per_page=20&sort=updated"
        } else {
            "$baseUrl/user/repos?per_page=20&sort=updated"
        }

        return try {
            val response = apiGet(url)
            val repos = mapper.readValue(response, List::class.java) as List<Map<String, Any>>

            val formatted = repos.map { repo ->
                mapOf(
                    "name" to repo["name"],
                    "full_name" to repo["full_name"],
                    "description" to repo["description"],
                    "private" to repo["private"],
                    "language" to repo["language"],
                    "stars" to repo["stargazers_count"],
                    "forks" to repo["forks_count"],
                    "html_url" to repo["html_url"],
                    "updated_at" to repo["updated_at"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${repos.size} repositories"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list repositories" }
            PluginResult(false, error = e.message)
        }
    }

    private fun apiGet(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("GitHub API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }
}
