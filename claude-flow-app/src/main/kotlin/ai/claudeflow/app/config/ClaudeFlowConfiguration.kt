package ai.claudeflow.app.config

import ai.claudeflow.api.command.CommandHandler
import ai.claudeflow.api.rest.ClaudeFlowController
import ai.claudeflow.api.slack.SlackMessageSender
import ai.claudeflow.api.slack.SlackSocketModeBridge
import ai.claudeflow.api.slack.WebhookSender
import ai.claudeflow.core.config.SlackConfig
import ai.claudeflow.core.config.WebhookConfig
import ai.claudeflow.core.config.WebhookEndpoints
import ai.claudeflow.core.model.Project
import ai.claudeflow.core.ratelimit.RateLimiter
import ai.claudeflow.core.rag.EmbeddingService
import ai.claudeflow.core.rag.KnowledgeVectorService
import ai.claudeflow.core.registry.ProjectRegistry
import ai.claudeflow.core.routing.AgentRouter
import ai.claudeflow.core.routing.SemanticRouter
import ai.claudeflow.core.session.SessionManager
import ai.claudeflow.core.plugin.PluginManager
import ai.claudeflow.core.plugin.GitLabPlugin
import ai.claudeflow.core.plugin.GitHubPlugin
import ai.claudeflow.core.plugin.JiraPlugin
import ai.claudeflow.core.storage.Storage
import ai.claudeflow.executor.ClaudeExecutor
import kotlinx.coroutines.runBlocking
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private val logger = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "claude-flow")
data class ClaudeFlowProperties(
    val slack: SlackProperties = SlackProperties(),
    val webhook: WebhookProperties = WebhookProperties(),
    val claude: ClaudeProperties = ClaudeProperties(),
    val workspace: WorkspaceProperties = WorkspaceProperties(),
    val qdrant: QdrantProperties = QdrantProperties(),
    val ollama: OllamaProperties = OllamaProperties(),
    val gitlab: GitLabProperties = GitLabProperties(),
    val github: GitHubProperties = GitHubProperties(),
    val jira: JiraProperties = JiraProperties()
)

data class SlackProperties(
    val appToken: String = "",
    val botToken: String = "",
    val signingSecret: String = ""
)

data class WebhookProperties(
    val baseUrl: String = "http://localhost:5678",
    val mentionEndpoint: String = "/webhook/slack-mention",
    val messageEndpoint: String = "/webhook/slack-message",
    val reactionEndpoint: String = "/webhook/slack-reaction",
    val feedbackEndpoint: String = "/webhook/slack-feedback"
)

data class ClaudeProperties(
    val model: String = "claude-sonnet-4-20250514",
    val maxTokens: Int = 4096,
    val timeoutSeconds: Int = 300
)

data class WorkspaceProperties(
    val path: String = System.getProperty("user.dir")  // 현재 작업 디렉토리
)

data class QdrantProperties(
    val url: String = "",  // 비어있으면 SemanticRouter 비활성화
    val collection: String = "claude-flow-agents"
)

data class OllamaProperties(
    val url: String = "",  // 비어있으면 SemanticRouter 비활성화
    val model: String = "nomic-embed-text"
)

data class GitLabProperties(
    val url: String = "",
    val token: String = ""
)

data class JiraProperties(
    val url: String = "",
    val email: String = "",
    val apiToken: String = ""
)

data class GitHubProperties(
    val token: String = ""
)

@Configuration
@EnableConfigurationProperties(ClaudeFlowProperties::class)
class ClaudeFlowConfiguration(
    private val properties: ClaudeFlowProperties
) {
    private var socketModeBridge: SlackSocketModeBridge? = null

    @Bean
    fun slackConfig(): SlackConfig = SlackConfig(
        appToken = properties.slack.appToken,
        botToken = properties.slack.botToken,
        signingSecret = properties.slack.signingSecret
    )

    @Bean
    fun webhookConfig(): WebhookConfig = WebhookConfig(
        baseUrl = properties.webhook.baseUrl,
        endpoints = WebhookEndpoints(
            mention = properties.webhook.mentionEndpoint,
            message = properties.webhook.messageEndpoint,
            reaction = properties.webhook.reactionEndpoint,
            feedback = properties.webhook.feedbackEndpoint
        )
    )

    @Bean
    fun webhookSender(): WebhookSender = WebhookSender()

    @Bean
    fun slackMessageSender(slackConfig: SlackConfig): SlackMessageSender =
        SlackMessageSender(slackConfig.botToken)

    @Bean
    fun embeddingService(): EmbeddingService? {
        val ollamaUrl = properties.ollama.url
        if (ollamaUrl.isEmpty()) {
            logger.info { "EmbeddingService disabled (Ollama not configured)" }
            return null
        }

        logger.info { "Initializing EmbeddingService: $ollamaUrl with model ${properties.ollama.model}" }
        return EmbeddingService(
            ollamaUrl = ollamaUrl,
            model = properties.ollama.model
        )
    }

    @Bean
    fun knowledgeVectorService(embeddingService: EmbeddingService?): KnowledgeVectorService? {
        if (embeddingService == null) {
            logger.info { "KnowledgeVectorService disabled (EmbeddingService not available)" }
            return null
        }

        val qdrantUrl = properties.qdrant.url
        if (qdrantUrl.isEmpty()) {
            logger.info { "KnowledgeVectorService disabled (Qdrant not configured)" }
            return null
        }

        logger.info { "Initializing KnowledgeVectorService: $qdrantUrl" }
        val service = KnowledgeVectorService(
            embeddingService = embeddingService,
            qdrantUrl = qdrantUrl,
            collectionName = "claude-flow-knowledge"
        )

        // 컬렉션 초기화
        try {
            if (service.initCollection()) {
                logger.info { "Knowledge vector collection initialized" }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to initialize knowledge collection: ${e.message}" }
        }

        return service
    }

    @Bean
    fun projectRegistry(
        storage: Storage,
        knowledgeVectorService: KnowledgeVectorService?
    ): ProjectRegistry {
        // config/projects.json에서 프로젝트가 로드됨 (Storage에서 처리)
        // 여기서는 빈 초기 프로젝트로 레지스트리만 생성
        val registry = ProjectRegistry(
            projectRepository = storage.projectRepository,
            knowledgeVectorService = knowledgeVectorService,
            initialProjects = emptyList()
        )

        val projectCount = storage.projectRepository.findAll().size
        logger.info { "ProjectRegistry initialized with $projectCount projects from config" }

        // 기존 프로젝트들을 RAG에 인덱싱
        if (knowledgeVectorService != null && projectCount > 0) {
            try {
                val projects = storage.projectRepository.findAll()
                val result = knowledgeVectorService.indexAllProjects(projects)
                logger.info { "Indexed ${result.successCount} projects to RAG knowledge base" }
            } catch (e: Exception) {
                logger.warn { "Failed to index projects to RAG: ${e.message}" }
            }
        }

        return registry
    }

    @Bean
    fun claudeExecutor(): ClaudeExecutor = ClaudeExecutor()

    @Bean
    fun storage(): Storage {
        // 환경변수 또는 현재 디렉토리 기준으로 data 디렉토리 찾기
        val dbPath = System.getenv("CLAUDE_FLOW_DB")
            ?: findDataDirectory()?.let { "$it/claude-flow.db" }
            ?: "./data/claude-flow.db"

        val dataDir = java.io.File(dbPath).parentFile
        if (!dataDir.exists()) {
            dataDir.mkdirs()
            logger.info { "Created data directory: ${dataDir.absolutePath}" }
        }
        logger.info { "Initializing SQLite storage: $dbPath" }
        return Storage(dbPath)
    }

    private fun findDataDirectory(): String? {
        // 현재 디렉토리부터 상위로 올라가며 data 디렉토리 찾기
        var dir = java.io.File(System.getProperty("user.dir"))
        repeat(5) {
            val dataDir = java.io.File(dir, "data")
            if (dataDir.exists() && dataDir.isDirectory) {
                return dataDir.absolutePath
            }
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Bean
    fun rateLimiter(): RateLimiter {
        logger.info { "Initializing rate limiter (60 rpm default)" }
        return RateLimiter(defaultRpm = 60)
    }

    @Bean
    fun semanticRouter(): SemanticRouter? {
        val qdrantUrl = properties.qdrant.url
        val ollamaUrl = properties.ollama.url

        if (qdrantUrl.isEmpty() || ollamaUrl.isEmpty()) {
            logger.info { "Semantic router disabled (Qdrant/Ollama not configured)" }
            return null
        }

        logger.info { "Initializing SemanticRouter: Qdrant=$qdrantUrl, Ollama=$ollamaUrl" }
        val router = SemanticRouter(
            embeddingServiceUrl = ollamaUrl,
            vectorDbUrl = qdrantUrl,
            collectionName = properties.qdrant.collection
        )

        // 에이전트 예제 인덱싱
        val agentExamples = mapOf(
            "code-reviewer" to listOf(
                "이 코드 리뷰해줘",
                "MR 좀 봐줘",
                "코드 검토 부탁해",
                "PR 리뷰 해줘",
                "이 변경사항 확인해줘",
                "코드 품질 체크해줘"
            ),
            "bug-fixer" to listOf(
                "버그 수정해줘",
                "에러 발생하는데 고쳐줘",
                "오류 해결해줘",
                "이거 왜 안되지",
                "NullPointerException 발생",
                "테스트 실패 원인 찾아줘"
            ),
            "general" to listOf(
                "이거 어떻게 하는거야",
                "설명해줘",
                "뭐야 이거",
                "도움이 필요해",
                "구현 방법 알려줘",
                "아키텍처 설명해줘"
            )
        )

        try {
            router.indexAgentExamples(
                ai.claudeflow.core.routing.AgentRouter.defaultAgents(),
                agentExamples
            )
            logger.info { "Agent examples indexed to vector DB" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to index agent examples (vector DB may not be ready)" }
        }

        return router
    }

    @Bean
    fun agentRouter(semanticRouter: SemanticRouter?): AgentRouter {
        logger.info { "Initializing AgentRouter with default agents" }
        return AgentRouter(
            initialAgents = AgentRouter.defaultAgents(),
            semanticRouter = semanticRouter
        )
    }

    @Bean
    fun commandHandler(projectRegistry: ProjectRegistry): CommandHandler {
        logger.info { "Initializing CommandHandler" }
        return CommandHandler(projectRegistry)
    }

    @Bean
    fun sessionManager(): SessionManager {
        logger.info { "Initializing SessionManager (TTL: 60 min)" }
        return SessionManager(sessionTtlMinutes = 60, maxSessions = 1000)
    }

    @Bean
    fun pluginManager(): PluginManager {
        val manager = PluginManager()

        // GitLab 플러그인 등록
        if (properties.gitlab.url.isNotEmpty() && properties.gitlab.token.isNotEmpty()) {
            val gitlabConfig = mapOf(
                "GITLAB_URL" to properties.gitlab.url,
                "GITLAB_TOKEN" to properties.gitlab.token
            )
            manager.register(GitLabPlugin(), gitlabConfig)
            logger.info { "GitLab plugin registered: ${properties.gitlab.url}" }
        }

        // GitHub 플러그인 등록
        if (properties.github.token.isNotEmpty()) {
            val githubConfig = mapOf(
                "GITHUB_TOKEN" to properties.github.token
            )
            manager.register(GitHubPlugin(), githubConfig)
            logger.info { "GitHub plugin registered" }
        }

        // Jira 플러그인 등록
        if (properties.jira.url.isNotEmpty() && properties.jira.email.isNotEmpty()) {
            val jiraConfig = mapOf(
                "JIRA_URL" to properties.jira.url,
                "JIRA_EMAIL" to properties.jira.email,
                "JIRA_API_TOKEN" to properties.jira.apiToken
            )
            manager.register(JiraPlugin(), jiraConfig)
            logger.info { "Jira plugin registered: ${properties.jira.url}" }
        }

        // 플러그인 초기화
        runBlocking {
            manager.initializeAll()
        }

        return manager
    }

    @Bean
    fun claudeFlowController(
        claudeExecutor: ClaudeExecutor,
        slackMessageSender: SlackMessageSender,
        projectRegistry: ProjectRegistry,
        enrichmentPipeline: ai.claudeflow.core.enrichment.ContextEnrichmentPipeline,
        agentRouter: AgentRouter,
        commandHandler: CommandHandler,
        storage: Storage,
        rateLimiter: RateLimiter
    ): ClaudeFlowController = ClaudeFlowController(
        claudeExecutor = claudeExecutor,
        slackMessageSender = slackMessageSender,
        projectRegistry = projectRegistry,
        enrichmentPipeline = enrichmentPipeline,
        agentRouter = agentRouter,
        commandHandler = commandHandler,
        storage = storage,
        rateLimiter = rateLimiter
    )

    @Bean
    fun slackSocketModeBridge(
        slackConfig: SlackConfig,
        webhookConfig: WebhookConfig,
        webhookSender: WebhookSender
    ): SlackSocketModeBridge {
        logger.info { "Creating SlackSocketModeBridge..." }
        logger.info { "App Token present: ${slackConfig.appToken.isNotEmpty()}, length: ${slackConfig.appToken.length}" }
        logger.info { "Bot Token present: ${slackConfig.botToken.isNotEmpty()}, length: ${slackConfig.botToken.length}" }

        val bridge = SlackSocketModeBridge(
            slackConfig = slackConfig,
            webhookConfig = webhookConfig,
            webhookSender = webhookSender
        )

        // Bean 생성 시 바로 시작
        if (slackConfig.appToken.isNotEmpty() && slackConfig.botToken.isNotEmpty()) {
            logger.info { "Starting Slack Socket Mode Bridge..." }
            try {
                bridge.start()
                logger.info { "Slack Socket Mode Bridge started" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to start Slack Socket Mode Bridge" }
            }
        } else {
            logger.warn { "Slack tokens not configured, Socket Mode Bridge not started" }
        }

        socketModeBridge = bridge
        return bridge
    }

    @PreDestroy
    fun stopSlackBridge() {
        logger.info { "Stopping Slack Socket Mode Bridge..." }
        socketModeBridge?.stop()
    }
}
