package ai.claudeflow.api.rest

import ai.claudeflow.api.slack.SlackSocketModeBridge
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * System monitoring and health check API
 */
@RestController
@RequestMapping("/api/v1/system")
class SystemController(
    private val slackSocketModeBridge: SlackSocketModeBridge,
    @Value("\${claude-flow.env-file:docker-compose/.env}") private val envFilePath: String
) {

    // 허용된 환경변수 키 목록 (보안)
    private val allowedEnvKeys = setOf(
        "SLACK_APP_TOKEN",
        "SLACK_BOT_TOKEN",
        "CLAUDE_MODEL",
        "CLAUDE_TIMEOUT",
        "WORKSPACE_PATH",
        "GITLAB_URL",
        "GITLAB_TOKEN",
        "JIRA_URL",
        "JIRA_EMAIL",
        "JIRA_API_TOKEN",
        "N8N_DEFAULT_EMAIL",
        "N8N_DEFAULT_PASSWORD"
    )
    /**
     * Comprehensive health check
     */
    @GetMapping("/health")
    fun health(): Mono<ResponseEntity<SystemHealthResponse>> = mono {
        val slackStatus = slackSocketModeBridge.getStatus()
        val slackHealthy = slackSocketModeBridge.isHealthy()

        val overallHealthy = slackHealthy

        ResponseEntity.ok(SystemHealthResponse(
            status = if (overallHealthy) "healthy" else "degraded",
            components = mapOf(
                "slack" to ComponentHealth(
                    status = if (slackHealthy) "healthy" else "unhealthy",
                    details = slackStatus
                )
            )
        ))
    }

    /**
     * Slack connection status
     */
    @GetMapping("/slack/status")
    fun slackStatus(): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val status = slackSocketModeBridge.getStatus()
        ResponseEntity.ok(status)
    }

    /**
     * Slack connection health check
     */
    @GetMapping("/slack/health")
    fun slackHealth(): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val healthy = slackSocketModeBridge.isHealthy()
        val status = slackSocketModeBridge.getStatus()

        ResponseEntity.ok(mapOf(
            "healthy" to healthy,
            "state" to (status["state"] ?: "unknown"),
            "uptime" to (status["uptime"] ?: 0),
            "totalReconnects" to (status["totalReconnects"] ?: 0),
            "failedMessageQueueSize" to (status["failedMessageQueueSize"] ?: 0)
        ))
    }

    /**
     * Force Slack reconnection (for debugging)
     */
    @PostMapping("/slack/reconnect")
    fun forceReconnect(): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.warn { "Force reconnect requested" }

        try {
            slackSocketModeBridge.stop()
            Thread.sleep(1000)
            slackSocketModeBridge.start()
            ResponseEntity.ok(mapOf("success" to true, "message" to "Reconnection initiated"))
        } catch (e: Exception) {
            logger.error(e) { "Failed to force reconnect" }
            ResponseEntity.ok(mapOf("success" to false, "error" to (e.message ?: "Unknown error")))
        }
    }

    // ==================== Environment Configuration API ====================

    /**
     * 환경변수 설정 조회
     */
    @GetMapping("/env")
    fun getEnvConfig(): Mono<ResponseEntity<EnvConfigResponse>> = mono {
        try {
            val envFile = findEnvFile()
            if (envFile == null) {
                return@mono ResponseEntity.ok(EnvConfigResponse(
                    success = false,
                    path = null,
                    exists = false,
                    variables = emptyList(),
                    message = "Environment file not found"
                ))
            }

            val variables = parseEnvFile(envFile)

            ResponseEntity.ok(EnvConfigResponse(
                success = true,
                path = envFile.absolutePath,
                exists = true,
                variables = variables,
                message = null
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to read env config" }
            ResponseEntity.ok(EnvConfigResponse(
                success = false,
                path = null,
                exists = false,
                variables = emptyList(),
                message = e.message
            ))
        }
    }

    /**
     * 환경변수 설정 저장
     */
    @PutMapping("/env")
    fun saveEnvConfig(@RequestBody request: SaveEnvRequest): Mono<ResponseEntity<SaveEnvResponse>> = mono {
        try {
            val envFile = findEnvFile() ?: createEnvFile()

            // 허용된 키만 필터링
            val filteredVariables = request.variables.filter { it.key in allowedEnvKeys }

            // 기존 파일 읽기 (허용되지 않은 키 보존)
            val existingContent = if (envFile.exists()) envFile.readText() else ""
            val existingVars = parseEnvFileRaw(existingContent)

            // 새 값으로 업데이트
            val updatedVars = existingVars.toMutableMap()
            for (variable in filteredVariables) {
                if (variable.value.isNotBlank()) {
                    updatedVars[variable.key] = variable.value
                } else {
                    updatedVars.remove(variable.key)
                }
            }

            // 파일 쓰기 (그룹별로 정렬)
            val content = buildEnvFileContent(updatedVars)
            envFile.writeText(content)

            logger.info { "Environment config saved: ${filteredVariables.size} variables updated" }

            ResponseEntity.ok(SaveEnvResponse(
                success = true,
                message = "Configuration saved. Restart required to apply changes.",
                path = envFile.absolutePath
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to save env config" }
            ResponseEntity.ok(SaveEnvResponse(
                success = false,
                message = e.message ?: "Failed to save",
                path = null
            ))
        }
    }

    /**
     * 환경변수 스키마 조회 (UI 렌더링용)
     */
    @GetMapping("/env/schema")
    fun getEnvSchema(): Mono<ResponseEntity<EnvSchemaResponse>> = mono {
        val schema = listOf(
            EnvVarSchema(
                key = "SLACK_APP_TOKEN",
                label = "Slack App Token",
                description = "Socket Mode app-level token (xapp-...)",
                group = "slack",
                required = true,
                sensitive = true,
                placeholder = "xapp-1-..."
            ),
            EnvVarSchema(
                key = "SLACK_BOT_TOKEN",
                label = "Slack Bot Token",
                description = "Bot user OAuth token (xoxb-...)",
                group = "slack",
                required = true,
                sensitive = true,
                placeholder = "xoxb-..."
            ),
            EnvVarSchema(
                key = "WORKSPACE_PATH",
                label = "Workspace Path",
                description = "Base directory for project workspaces",
                group = "general",
                required = false,
                sensitive = false,
                placeholder = "/Users/username/workspace"
            ),
            EnvVarSchema(
                key = "CLAUDE_MODEL",
                label = "Claude Model",
                description = "Default Claude model for executions",
                group = "claude",
                required = false,
                sensitive = false,
                placeholder = "claude-sonnet-4-20250514"
            ),
            EnvVarSchema(
                key = "CLAUDE_TIMEOUT",
                label = "Claude Timeout",
                description = "Execution timeout in seconds",
                group = "claude",
                required = false,
                sensitive = false,
                placeholder = "300"
            ),
            EnvVarSchema(
                key = "GITLAB_URL",
                label = "GitLab URL",
                description = "GitLab instance URL for MR reviews",
                group = "gitlab",
                required = false,
                sensitive = false,
                placeholder = "https://gitlab.example.com"
            ),
            EnvVarSchema(
                key = "GITLAB_TOKEN",
                label = "GitLab Token",
                description = "Personal access token (glpat-...)",
                group = "gitlab",
                required = false,
                sensitive = true,
                placeholder = "glpat-..."
            ),
            EnvVarSchema(
                key = "JIRA_URL",
                label = "Jira URL",
                description = "Jira/Atlassian instance URL",
                group = "jira",
                required = false,
                sensitive = false,
                placeholder = "https://your-company.atlassian.net"
            ),
            EnvVarSchema(
                key = "JIRA_EMAIL",
                label = "Jira Email",
                description = "Jira account email",
                group = "jira",
                required = false,
                sensitive = false,
                placeholder = "your-email@company.com"
            ),
            EnvVarSchema(
                key = "JIRA_API_TOKEN",
                label = "Jira API Token",
                description = "Jira API token for authentication",
                group = "jira",
                required = false,
                sensitive = true,
                placeholder = ""
            )
        )

        val groups = listOf(
            EnvGroup("slack", "Slack Integration", "Required for Slack bot functionality", true),
            EnvGroup("general", "General", "General configuration", false),
            EnvGroup("claude", "Claude", "Claude AI settings", false),
            EnvGroup("gitlab", "GitLab", "GitLab MR review integration", false),
            EnvGroup("jira", "Jira", "Jira integration", false)
        )

        ResponseEntity.ok(EnvSchemaResponse(schema = schema, groups = groups))
    }

    // ==================== Helper Functions ====================

    private fun findEnvFile(): File? {
        // 프로젝트 루트 디렉토리 찾기
        val userDir = System.getProperty("user.dir")
        val projectRoot = findProjectRoot(File(userDir))

        // 여러 위치에서 .env 파일 탐색
        val candidates = mutableListOf<File>()

        // 1. 명시적으로 설정된 경로
        candidates.add(File(envFilePath))

        // 2. 프로젝트 루트 기준 경로들
        if (projectRoot != null) {
            candidates.add(File(projectRoot, "docker-compose/.env"))
            candidates.add(File(projectRoot, ".env"))
        }

        // 3. 현재 디렉토리 기준 경로들
        candidates.add(File(userDir, "docker-compose/.env"))
        candidates.add(File(userDir, "../docker-compose/.env"))
        candidates.add(File("docker-compose/.env"))
        candidates.add(File("../.env"))
        candidates.add(File(".env"))

        val found = candidates.firstOrNull { it.exists() }
        logger.debug { "Searching for .env file. Found: ${found?.absolutePath ?: "none"}" }
        logger.debug { "Candidates checked: ${candidates.map { "${it.absolutePath} (exists=${it.exists()})" }}" }

        return found
    }

    private fun findProjectRoot(startDir: File): File? {
        var current: File? = startDir
        while (current != null) {
            // claude-flow 프로젝트 루트 마커 확인
            if (File(current, "docker-compose").exists() &&
                File(current, "claude-flow-core").exists()) {
                return current
            }
            // .git 폴더가 있으면 프로젝트 루트
            if (File(current, ".git").exists() &&
                File(current, "docker-compose").exists()) {
                return current
            }
            current = current.parentFile
        }
        return null
    }

    private fun createEnvFile(): File {
        // 프로젝트 루트 기준으로 생성
        val userDir = System.getProperty("user.dir")
        val projectRoot = findProjectRoot(File(userDir))

        val file = if (projectRoot != null) {
            File(projectRoot, "docker-compose/.env")
        } else {
            File(envFilePath)
        }

        file.parentFile?.mkdirs()
        file.createNewFile()
        logger.info { "Created new .env file at: ${file.absolutePath}" }
        return file
    }

    private fun parseEnvFile(file: File): List<EnvVariable> {
        if (!file.exists()) return emptyList()

        return file.readLines()
            .filter { line -> line.isNotBlank() && !line.trimStart().startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    if (key in allowedEnvKeys) {
                        EnvVariable(key = key, value = value)
                    } else null
                } else null
            }
    }

    private fun parseEnvFileRaw(content: String): Map<String, String> {
        return content.lines()
            .filter { line -> line.isNotBlank() && !line.trimStart().startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0].trim() to parts[1].trim()
                } else null
            }
            .toMap()
    }

    private fun buildEnvFileContent(vars: Map<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine("# Claude Flow Environment Configuration")
        sb.appendLine("# Generated by Claude Flow Dashboard")
        sb.appendLine()

        // 그룹별로 정렬
        val groups = mapOf(
            "Slack Integration" to listOf("SLACK_APP_TOKEN", "SLACK_BOT_TOKEN"),
            "General" to listOf("WORKSPACE_PATH"),
            "Claude Configuration" to listOf("CLAUDE_MODEL", "CLAUDE_TIMEOUT"),
            "GitLab Integration" to listOf("GITLAB_URL", "GITLAB_TOKEN"),
            "Jira Integration" to listOf("JIRA_URL", "JIRA_EMAIL", "JIRA_API_TOKEN"),
            "n8n Configuration" to listOf("N8N_DEFAULT_EMAIL", "N8N_DEFAULT_PASSWORD")
        )

        val usedKeys = mutableSetOf<String>()

        for ((groupName, keys) in groups) {
            val groupVars = keys.filter { it in vars }
            if (groupVars.isNotEmpty()) {
                sb.appendLine("# $groupName")
                for (key in groupVars) {
                    sb.appendLine("$key=${vars[key]}")
                    usedKeys.add(key)
                }
                sb.appendLine()
            }
        }

        // 그룹에 속하지 않은 변수들
        val remainingVars = vars.filterKeys { it !in usedKeys }
        if (remainingVars.isNotEmpty()) {
            sb.appendLine("# Other")
            for ((key, value) in remainingVars) {
                sb.appendLine("$key=$value")
            }
        }

        return sb.toString().trimEnd() + "\n"
    }
}

data class SystemHealthResponse(
    val status: String,
    val components: Map<String, ComponentHealth>
)

data class ComponentHealth(
    val status: String,
    val details: Map<String, Any>
)

// Environment Configuration DTOs
data class EnvConfigResponse(
    val success: Boolean,
    val path: String?,
    val exists: Boolean,
    val variables: List<EnvVariable>,
    val message: String?
)

data class EnvVariable(
    val key: String,
    val value: String
)

data class SaveEnvRequest(
    val variables: List<EnvVariable>
)

data class SaveEnvResponse(
    val success: Boolean,
    val message: String,
    val path: String?
)

data class EnvSchemaResponse(
    val schema: List<EnvVarSchema>,
    val groups: List<EnvGroup>
)

data class EnvVarSchema(
    val key: String,
    val label: String,
    val description: String,
    val group: String,
    val required: Boolean,
    val sensitive: Boolean,
    val placeholder: String
)

data class EnvGroup(
    val id: String,
    val name: String,
    val description: String,
    val required: Boolean
)
