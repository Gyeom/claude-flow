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
 */
class JiraPlugin : BasePlugin() {
    override val id = "jira"
    override val name = "Jira"
    override val description = "Jira 이슈 조회 및 관리"

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
            "sprint" -> getSprintIssues(args["board_id"] as? Int)
            "search" -> searchIssues(
                args["jql"] as? String ?: return PluginResult(false, error = "JQL required")
            )
            "transition" -> transitionIssue(
                args["issue_key"] as? String ?: return PluginResult(false, error = "Issue key required"),
                args["status"] as? String ?: return PluginResult(false, error = "Status required")
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

    private fun getSprintIssues(boardId: Int?): PluginResult {
        // 보드 ID가 없으면 현재 스프린트의 모든 이슈 검색
        val jql = "sprint in openSprints() AND resolution=Unresolved ORDER BY rank ASC"
        return searchIssues(jql)
    }

    private fun searchIssues(jql: String): PluginResult {
        val encodedJql = java.net.URLEncoder.encode(jql, "UTF-8")
        val url = "$baseUrl/rest/api/3/search?jql=$encodedJql&maxResults=20&fields=key,summary,status,assignee,priority,issuetype"

        return try {
            val response = apiGet(url)
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

    private fun transitionIssue(issueKey: String, targetStatus: String): PluginResult {
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

            // 전환 실행
            val body = mapOf("transition" to mapOf("id" to transition["id"]))
            apiPost(transitionsUrl, mapper.writeValueAsString(body))

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
}
