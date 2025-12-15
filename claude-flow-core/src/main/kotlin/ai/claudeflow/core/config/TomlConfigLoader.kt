package ai.claudeflow.core.config

import ai.claudeflow.core.model.Agent
import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * TOML 설정 파일 로더
 *
 * config.toml과 agents.toml 파일을 파싱하여 설정을 로드합니다.
 */
object TomlConfigLoader {

    private val toml = Toml(
        inputConfig = TomlInputConfig(
            ignoreUnknownNames = true,
            allowEmptyValues = true
        )
    )

    /**
     * agents.toml 파일에서 에이전트 목록 로드
     */
    fun loadAgents(filePath: String): List<Agent> {
        val file = File(filePath)
        if (!file.exists()) {
            logger.info { "Agent config file not found: $filePath, using defaults" }
            return emptyList()
        }

        return try {
            val content = file.readText()
            val config = toml.decodeFromString<AgentConfigFile>(content)

            config.agents.map { agentDef ->
                Agent(
                    id = agentDef.id,
                    name = agentDef.name,
                    description = agentDef.description,
                    keywords = agentDef.keywords,
                    systemPrompt = agentDef.system_prompt,
                    model = agentDef.model,
                    allowedTools = agentDef.allowed_tools,
                    enabled = agentDef.enabled,
                    priority = agentDef.priority,
                    examples = agentDef.examples
                )
            }.also {
                logger.info { "Loaded ${it.size} agents from $filePath" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse agents config: $filePath" }
            emptyList()
        }
    }

    /**
     * config.toml 파일에서 메인 설정 로드
     */
    fun loadConfig(filePath: String): TomlConfig? {
        val file = File(filePath)
        if (!file.exists()) {
            logger.info { "Config file not found: $filePath" }
            return null
        }

        return try {
            val content = file.readText()
            toml.decodeFromString<TomlConfig>(content).also {
                logger.info { "Loaded config from $filePath" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse config: $filePath" }
            null
        }
    }
}

// ==================== Serializable Config Classes ====================

@Serializable
data class AgentConfigFile(
    val agents: List<AgentDefinition> = emptyList()
)

@Serializable
data class AgentDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val priority: Int = 50,
    val model: String = "claude-sonnet-4-20250514",
    val keywords: List<String> = emptyList(),
    val allowed_tools: List<String> = emptyList(),
    val system_prompt: String = "",
    val examples: List<String> = emptyList(),
    val working_directory: String? = null,
    val project_id: String? = null
)

@Serializable
data class TomlConfig(
    val server: ServerConfig = ServerConfig(),
    val slack: SlackConfigToml = SlackConfigToml(),
    val claude: ClaudeConfigToml = ClaudeConfigToml(),
    val workspace: WorkspaceConfig = WorkspaceConfig(),
    val webhook: WebhookConfigToml = WebhookConfigToml(),
    val vector_db: VectorDbConfig = VectorDbConfig(),
    val embedding: EmbeddingConfig = EmbeddingConfig(),
    val integrations: IntegrationsConfig = IntegrationsConfig(),
    val rate_limit: RateLimitConfig = RateLimitConfig(),
    val session: SessionConfig = SessionConfig()
)

@Serializable
data class ServerConfig(
    val port: Int = 8080,
    val host: String = "0.0.0.0"
)

@Serializable
data class SlackConfigToml(
    val app_token: String = "",
    val bot_token: String = "",
    val signing_secret: String = ""
)

@Serializable
data class ClaudeConfigToml(
    val model: String = "claude-sonnet-4-20250514",
    val max_tokens: Int = 4096,
    val timeout_seconds: Int = 300
)

@Serializable
data class WorkspaceConfig(
    val path: String = "/workspace"
)

@Serializable
data class WebhookConfigToml(
    val base_url: String = "http://localhost:5678",
    val mention_endpoint: String = "/webhook/slack-mention",
    val message_endpoint: String = "/webhook/slack-message",
    val reaction_endpoint: String = "/webhook/slack-reaction",
    val feedback_endpoint: String = "/webhook/slack-feedback"
)

@Serializable
data class VectorDbConfig(
    val enabled: Boolean = false,
    val qdrant_url: String = "http://localhost:6333",
    val collection: String = "claude-flow-agents"
)

@Serializable
data class EmbeddingConfig(
    val enabled: Boolean = false,
    val ollama_url: String = "http://localhost:11434",
    val model: String = "nomic-embed-text"
)

@Serializable
data class IntegrationsConfig(
    val gitlab: GitLabConfigToml = GitLabConfigToml(),
    val github: GitHubConfigToml = GitHubConfigToml(),
    val jira: JiraConfigToml = JiraConfigToml()
)

@Serializable
data class GitLabConfigToml(
    val enabled: Boolean = false,
    val url: String = "https://gitlab.com",
    val token: String = ""
)

@Serializable
data class GitHubConfigToml(
    val enabled: Boolean = false,
    val token: String = ""
)

@Serializable
data class JiraConfigToml(
    val enabled: Boolean = false,
    val url: String = "",
    val email: String = "",
    val api_token: String = ""
)

@Serializable
data class RateLimitConfig(
    val default_rpm: Int = 60,
    val burst_size: Int = 10
)

@Serializable
data class SessionConfig(
    val ttl_minutes: Int = 60,
    val max_sessions: Int = 1000
)
