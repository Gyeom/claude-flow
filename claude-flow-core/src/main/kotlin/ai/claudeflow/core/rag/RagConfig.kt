package ai.claudeflow.core.rag

/**
 * RAG 시스템 설정
 */
data class RagConfig(
    // 활성화 여부
    val enabled: Boolean = true,

    // Qdrant 설정
    val qdrantUrl: String = "http://localhost:6333",
    val conversationsCollection: String = "claude-flow-conversations",
    val knowledgeCollection: String = "claude-flow-knowledge",

    // Ollama 설정
    val ollamaUrl: String = "http://localhost:11434",
    val embeddingModel: String = "nomic-embed-text",

    // 검색 설정
    val minSimilarityScore: Float = 0.65f,
    val maxSimilarConversations: Int = 3,
    val maxCodeChunks: Int = 5,

    // 캐시 설정
    val embeddingCacheSize: Long = 10_000,
    val embeddingCacheExpireMinutes: Long = 60,

    // 자동 인덱싱 설정
    val autoIndexEnabled: Boolean = true,
    val autoSummaryEnabled: Boolean = true,
    val autoSummaryMinConversations: Int = 5,
    val autoSummaryMinChars: Long = 5000,

    // 피드백 학습 설정
    val feedbackLearningEnabled: Boolean = true,
    val feedbackCacheMinutes: Long = 30,

    // 코드 인덱싱 설정
    val codeIndexEnabled: Boolean = false,
    val codeIndexProjects: List<String> = emptyList(),
    val codeChunkMaxSize: Int = 1500,
    val codeChunkOverlap: Int = 100
) {
    companion object {
        /**
         * 환경 변수에서 설정 로드
         */
        fun fromEnvironment(): RagConfig {
            return RagConfig(
                enabled = System.getenv("RAG_ENABLED")?.toBoolean() ?: true,
                qdrantUrl = System.getenv("QDRANT_URL") ?: "http://localhost:6333",
                ollamaUrl = System.getenv("OLLAMA_URL") ?: "http://localhost:11434",
                embeddingModel = System.getenv("OLLAMA_EMBEDDING_MODEL") ?: "nomic-embed-text",
                minSimilarityScore = System.getenv("RAG_MIN_SIMILARITY_SCORE")?.toFloatOrNull() ?: 0.65f,
                maxSimilarConversations = System.getenv("RAG_MAX_SIMILAR_CONVERSATIONS")?.toIntOrNull() ?: 3,
                autoIndexEnabled = System.getenv("RAG_AUTO_INDEX")?.toBoolean() ?: true,
                autoSummaryEnabled = System.getenv("RAG_AUTO_SUMMARY")?.toBoolean() ?: true,
                codeIndexEnabled = System.getenv("CODE_INDEX_ENABLED")?.toBoolean() ?: false,
                codeIndexProjects = System.getenv("CODE_INDEX_PROJECTS")?.split(",")?.map { it.trim() } ?: emptyList()
            )
        }

        /**
         * 기본 설정
         */
        val DEFAULT = RagConfig()

        /**
         * 개발용 설정 (캐시 비활성화, 디버그 모드)
         */
        val DEVELOPMENT = RagConfig(
            embeddingCacheSize = 100,
            embeddingCacheExpireMinutes = 5,
            minSimilarityScore = 0.5f
        )

        /**
         * 프로덕션 설정 (성능 최적화)
         */
        val PRODUCTION = RagConfig(
            embeddingCacheSize = 50_000,
            embeddingCacheExpireMinutes = 120,
            minSimilarityScore = 0.7f,
            autoIndexEnabled = true,
            autoSummaryEnabled = true
        )
    }
}

/**
 * RAG 서비스 팩토리
 *
 * 설정 기반으로 RAG 서비스들을 생성
 */
class RagServiceFactory(
    private val config: RagConfig
) {
    private var _embeddingCache: EmbeddingCache? = null
    private var _embeddingService: EmbeddingService? = null
    private var _conversationVectorService: ConversationVectorService? = null
    private var _codeKnowledgeService: CodeKnowledgeService? = null

    val embeddingCache: EmbeddingCache
        get() = _embeddingCache ?: EmbeddingCache(
            maxSize = config.embeddingCacheSize,
            expireAfterWrite = java.time.Duration.ofMinutes(config.embeddingCacheExpireMinutes)
        ).also { _embeddingCache = it }

    val embeddingService: EmbeddingService
        get() = _embeddingService ?: EmbeddingService(
            ollamaUrl = config.ollamaUrl,
            model = config.embeddingModel,
            cache = embeddingCache
        ).also { _embeddingService = it }

    val conversationVectorService: ConversationVectorService
        get() = _conversationVectorService ?: ConversationVectorService(
            embeddingService = embeddingService,
            qdrantUrl = config.qdrantUrl,
            collectionName = config.conversationsCollection
        ).also { _conversationVectorService = it }

    val codeKnowledgeService: CodeKnowledgeService
        get() = _codeKnowledgeService ?: CodeKnowledgeService(
            embeddingService = embeddingService,
            codeChunker = CodeChunker(
                maxChunkSize = config.codeChunkMaxSize,
                overlapSize = config.codeChunkOverlap
            ),
            qdrantUrl = config.qdrantUrl,
            collectionName = config.knowledgeCollection
        ).also { _codeKnowledgeService = it }

    /**
     * 모든 서비스 초기화 (컬렉션 생성 포함)
     */
    fun initialize(): InitializationResult {
        if (!config.enabled) {
            return InitializationResult(
                success = true,
                message = "RAG disabled by configuration"
            )
        }

        val errors = mutableListOf<String>()

        // 임베딩 서비스 확인
        if (!embeddingService.isAvailable()) {
            errors.add("Ollama embedding service not available at ${config.ollamaUrl}")
        }

        // 대화 컬렉션 초기화
        if (!conversationVectorService.initCollection()) {
            errors.add("Failed to initialize conversations collection")
        }

        // 코드 컬렉션 초기화 (활성화된 경우)
        if (config.codeIndexEnabled && !codeKnowledgeService.initCollection()) {
            errors.add("Failed to initialize knowledge collection")
        }

        return if (errors.isEmpty()) {
            InitializationResult(success = true, message = "RAG services initialized successfully")
        } else {
            InitializationResult(success = false, message = errors.joinToString("; "))
        }
    }
}

data class InitializationResult(
    val success: Boolean,
    val message: String
)
