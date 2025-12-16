package ai.claudeflow.api.rest

import ai.claudeflow.core.rag.*
import ai.claudeflow.core.storage.Storage
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * RAG (Retrieval-Augmented Generation) REST API
 *
 * 대화 벡터화, 유사 대화 검색, 코드베이스 검색 기능 제공
 */
@RestController
@RequestMapping("/api/v1/rag")
class RagController(
    private val storage: Storage
) {
    // RAG 서비스들은 지연 초기화 (의존성 주입 또는 직접 생성)
    private val embeddingCache by lazy { EmbeddingCache() }
    private val embeddingService by lazy {
        EmbeddingService(
            ollamaUrl = System.getenv("OLLAMA_URL") ?: "http://localhost:11434",
            cache = embeddingCache
        )
    }
    private val conversationVectorService by lazy {
        ConversationVectorService(
            embeddingService = embeddingService,
            qdrantUrl = System.getenv("QDRANT_URL") ?: "http://localhost:6333"
        )
    }
    private val contextAugmentationService by lazy {
        ContextAugmentationService(
            conversationVectorService = conversationVectorService,
            userContextRepository = storage.userContextRepository,
            userRuleRepository = storage.userRuleRepository
        )
    }
    private val feedbackLearningService by lazy {
        FeedbackLearningService(
            feedbackRepository = storage.feedbackRepository,
            executionRepository = storage.executionRepository,
            conversationVectorService = conversationVectorService,
            embeddingService = embeddingService
        )
    }
    private val autoSummaryService by lazy {
        AutoSummaryService(
            userContextRepository = storage.userContextRepository,
            executionRepository = storage.executionRepository
        )
    }
    private val codeKnowledgeService by lazy {
        CodeKnowledgeService(
            embeddingService = embeddingService,
            qdrantUrl = System.getenv("QDRANT_URL") ?: "http://localhost:6333"
        )
    }

    // ==================== Health & Status ====================

    /**
     * RAG 서비스 상태 확인
     */
    @GetMapping("/health")
    fun getHealth(): Mono<ResponseEntity<RagHealthResponse>> = mono {
        val embeddingAvailable = embeddingService.isAvailable()
        val vectorDbAvailable = conversationVectorService.isAvailable()

        ResponseEntity.ok(RagHealthResponse(
            status = if (embeddingAvailable && vectorDbAvailable) "healthy" else "degraded",
            embeddingService = embeddingAvailable,
            vectorDatabase = vectorDbAvailable,
            message = when {
                !embeddingAvailable -> "Ollama 서비스 연결 실패"
                !vectorDbAvailable -> "Qdrant 서비스 연결 실패"
                else -> "모든 서비스 정상"
            }
        ))
    }

    /**
     * RAG 통계 조회
     */
    @GetMapping("/stats")
    fun getStats(): Mono<ResponseEntity<RagStatsResponse>> = mono {
        val vectorStats = conversationVectorService.getStats()
        val cacheStats = embeddingCache.stats()
        val learningStats = feedbackLearningService.getLearningStats()

        ResponseEntity.ok(RagStatsResponse(
            conversationsIndexed = vectorStats.totalVectors,
            embeddingCacheHitRate = cacheStats.hitRate,
            embeddingCacheSize = cacheStats.estimatedSize,
            learningUsers = learningStats.totalUsers,
            totalFeedback = learningStats.totalFeedback,
            lastIndexedAt = vectorStats.lastIndexedAt
        ))
    }

    // ==================== Conversation Vector ====================

    /**
     * 유사 대화 검색
     */
    @PostMapping("/search")
    fun searchSimilarConversations(
        @RequestBody request: SearchConversationsRequest
    ): Mono<ResponseEntity<SearchConversationsResponse>> = mono {
        logger.info { "Search similar conversations: ${request.query.take(50)}..." }

        val results = conversationVectorService.findSimilarConversations(
            query = request.query,
            userId = request.userId,
            topK = request.topK ?: 5,
            minScore = request.minScore ?: 0.6f
        )

        ResponseEntity.ok(SearchConversationsResponse(
            results = results,
            totalFound = results.size,
            searchTimeMs = 0  // TODO: 실제 측정
        ))
    }

    /**
     * 실행 기록 인덱싱
     */
    @PostMapping("/index")
    fun indexExecution(
        @RequestBody request: IndexExecutionRequest
    ): Mono<ResponseEntity<IndexExecutionResponse>> = mono {
        logger.info { "Index execution: ${request.executionId}" }

        val execution = storage.executionRepository.findById(request.executionId)
            ?: return@mono ResponseEntity.notFound().build<IndexExecutionResponse>()

        val success = conversationVectorService.indexExecution(execution)

        if (success) {
            ResponseEntity.accepted().body(IndexExecutionResponse(
                success = true,
                message = "인덱싱 완료"
            ))
        } else {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(IndexExecutionResponse(
                success = false,
                message = "인덱싱 실패"
            ))
        }
    }

    /**
     * 배치 인덱싱 (최근 N개)
     */
    @PostMapping("/index/batch")
    fun batchIndex(
        @RequestBody request: BatchIndexRequest
    ): Mono<ResponseEntity<BatchIndexResponse>> = mono {
        logger.info { "Batch index: ${request.limit} executions" }

        val executions = storage.executionRepository.findRecent(request.limit)
        val indexed = conversationVectorService.indexExecutions(executions)

        ResponseEntity.ok(BatchIndexResponse(
            totalProcessed = executions.size,
            successfullyIndexed = indexed
        ))
    }

    // ==================== Context Augmentation ====================

    /**
     * 증강된 컨텍스트 빌드
     */
    @PostMapping("/augment")
    fun buildAugmentedContext(
        @RequestBody request: AugmentContextRequest
    ): Mono<ResponseEntity<AugmentContextResponse>> = mono {
        logger.info { "Build augmented context for user: ${request.userId}" }

        val options = AugmentationOptions(
            includeSimilarConversations = request.includeSimilarConversations ?: true,
            includeUserRules = request.includeUserRules ?: true,
            includeUserSummary = request.includeUserSummary ?: true,
            maxSimilarConversations = request.maxSimilarConversations ?: 3,
            minSimilarityScore = request.minSimilarityScore ?: 0.65f,
            userScopedSearch = request.userScopedSearch ?: false
        )

        val context = contextAugmentationService.buildAugmentedContext(
            userId = request.userId,
            message = request.message,
            options = options
        )

        ResponseEntity.ok(AugmentContextResponse(
            systemPrompt = context.systemPrompt,
            relevantConversationsCount = context.relevantConversations.size,
            userRulesCount = context.userRules.size,
            hasSummary = context.userSummary != null,
            metadata = context.metadata
        ))
    }

    // ==================== Feedback Learning ====================

    /**
     * 피드백 기록
     */
    @PostMapping("/feedback")
    fun recordFeedback(
        @RequestBody request: RecordFeedbackRequest
    ): Mono<ResponseEntity<RecordFeedbackResponse>> = mono {
        logger.info { "Record feedback for execution: ${request.executionId}" }

        val success = feedbackLearningService.recordFeedback(
            executionId = request.executionId,
            userId = request.userId,
            isPositive = request.isPositive
        )

        ResponseEntity.ok(RecordFeedbackResponse(success = success))
    }

    /**
     * 에이전트 추천 (유사 쿼리 기반)
     */
    @PostMapping("/recommend-agent")
    fun recommendAgent(
        @RequestBody request: RecommendAgentRequest
    ): Mono<ResponseEntity<RecommendAgentResponse>> = mono {
        logger.info { "Recommend agent for query: ${request.query.take(50)}..." }

        val recommendation = feedbackLearningService.recommendAgentFromSimilar(
            query = request.query,
            userId = request.userId,
            topK = request.topK ?: 5
        )

        if (recommendation != null) {
            ResponseEntity.ok(RecommendAgentResponse(
                recommended = true,
                agentId = recommendation.agentId,
                confidence = recommendation.confidence,
                reason = recommendation.reason
            ))
        } else {
            ResponseEntity.ok(RecommendAgentResponse(
                recommended = false,
                agentId = null,
                confidence = 0f,
                reason = "추천 가능한 에이전트 없음"
            ))
        }
    }

    /**
     * 학습 통계
     */
    @GetMapping("/learning/stats")
    fun getLearningStats(
        @RequestParam userId: String?
    ): Mono<ResponseEntity<LearningStats>> = mono {
        val stats = feedbackLearningService.getLearningStats(userId)
        ResponseEntity.ok(stats)
    }

    // ==================== Auto Summary ====================

    /**
     * 요약 필요 여부 확인
     */
    @GetMapping("/summary/check/{userId}")
    fun checkSummaryNeeded(
        @PathVariable userId: String
    ): Mono<ResponseEntity<SummaryNeedResult>> = mono {
        val result = autoSummaryService.needsSummary(userId)
        ResponseEntity.ok(result)
    }

    /**
     * 요약 생성
     */
    @PostMapping("/summary/generate/{userId}")
    fun generateSummary(
        @PathVariable userId: String
    ): Mono<ResponseEntity<SummaryGenerationResult>> = mono {
        logger.info { "Generate summary for user: $userId" }
        val result = autoSummaryService.generateSummary(userId)

        if (result.success) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result)
        }
    }

    /**
     * 배치 요약 생성
     */
    @PostMapping("/summary/batch")
    fun batchGenerateSummaries(
        @RequestBody request: BatchSummaryRequest
    ): Mono<ResponseEntity<BatchSummaryResult>> = mono {
        logger.info { "Batch generate summaries: max ${request.maxUsers} users" }
        val result = autoSummaryService.batchGenerateSummaries(request.maxUsers)
        ResponseEntity.ok(result)
    }

    // ==================== Code Knowledge ====================

    /**
     * 로컬 디렉토리 인덱싱
     */
    @PostMapping("/knowledge/index")
    fun indexCodebase(
        @RequestBody request: IndexCodebaseRequest
    ): Mono<ResponseEntity<IndexingResult>> = mono {
        logger.info { "Index codebase: ${request.projectId} from ${request.directory}" }

        val result = codeKnowledgeService.indexLocalDirectory(
            projectId = request.projectId,
            directory = request.directory,
            filePatterns = request.filePatterns ?: listOf("**/*")
        )

        if (result.error != null) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result)
        } else {
            ResponseEntity.ok(result)
        }
    }

    /**
     * 코드 검색
     */
    @PostMapping("/knowledge/search")
    fun searchCode(
        @RequestBody request: SearchCodeRequest
    ): Mono<ResponseEntity<SearchCodeResponse>> = mono {
        logger.info { "Search code: ${request.query.take(50)}..." }

        val results = codeKnowledgeService.findRelevantCode(
            query = request.query,
            projectId = request.projectId,
            filePatterns = request.filePatterns ?: emptyList(),
            topK = request.topK ?: 5,
            minScore = request.minScore ?: 0.6f
        )

        ResponseEntity.ok(SearchCodeResponse(
            results = results,
            totalFound = results.size
        ))
    }

    /**
     * 프로젝트 통계
     */
    @GetMapping("/knowledge/stats/{projectId}")
    fun getKnowledgeStats(
        @PathVariable projectId: String
    ): Mono<ResponseEntity<KnowledgeStats>> = mono {
        val stats = codeKnowledgeService.getProjectStats(projectId)
        ResponseEntity.ok(stats)
    }

    /**
     * 프로젝트 삭제
     */
    @DeleteMapping("/knowledge/{projectId}")
    fun deleteProjectKnowledge(
        @PathVariable projectId: String
    ): Mono<ResponseEntity<DeleteProjectResponse>> = mono {
        logger.info { "Delete knowledge for project: $projectId" }
        val success = codeKnowledgeService.deleteProject(projectId)
        ResponseEntity.ok(DeleteProjectResponse(success = success))
    }

    // ==================== Collection Management ====================

    /**
     * 컬렉션 초기화
     */
    @PostMapping("/collections/init")
    fun initCollections(): Mono<ResponseEntity<InitCollectionsResponse>> = mono {
        logger.info { "Initialize RAG collections" }

        val conversationsOk = conversationVectorService.initCollection()
        val knowledgeOk = codeKnowledgeService.initCollection()

        ResponseEntity.ok(InitCollectionsResponse(
            conversationsCollection = conversationsOk,
            knowledgeCollection = knowledgeOk
        ))
    }
}

// ==================== Request/Response DTOs ====================

data class RagHealthResponse(
    val status: String,
    val embeddingService: Boolean,
    val vectorDatabase: Boolean,
    val message: String
)

data class RagStatsResponse(
    val conversationsIndexed: Long,
    val embeddingCacheHitRate: Double,
    val embeddingCacheSize: Long,
    val learningUsers: Int,
    val totalFeedback: Int,
    val lastIndexedAt: String?
)

data class SearchConversationsRequest(
    val query: String,
    val userId: String? = null,
    val topK: Int? = 5,
    val minScore: Float? = 0.6f
)

data class SearchConversationsResponse(
    val results: List<SimilarConversation>,
    val totalFound: Int,
    val searchTimeMs: Long
)

data class IndexExecutionRequest(
    val executionId: String
)

data class IndexExecutionResponse(
    val success: Boolean,
    val message: String
)

data class BatchIndexRequest(
    val limit: Int = 100
)

data class BatchIndexResponse(
    val totalProcessed: Int,
    val successfullyIndexed: Int
)

data class AugmentContextRequest(
    val userId: String,
    val message: String,
    val includeSimilarConversations: Boolean? = true,
    val includeUserRules: Boolean? = true,
    val includeUserSummary: Boolean? = true,
    val maxSimilarConversations: Int? = 3,
    val minSimilarityScore: Float? = 0.65f,
    val userScopedSearch: Boolean? = false
)

data class AugmentContextResponse(
    val systemPrompt: String,
    val relevantConversationsCount: Int,
    val userRulesCount: Int,
    val hasSummary: Boolean,
    val metadata: AugmentationMetadata
)

data class RecordFeedbackRequest(
    val executionId: String,
    val userId: String,
    val isPositive: Boolean
)

data class RecordFeedbackResponse(
    val success: Boolean
)

data class RecommendAgentRequest(
    val query: String,
    val userId: String? = null,
    val topK: Int? = 5
)

data class RecommendAgentResponse(
    val recommended: Boolean,
    val agentId: String?,
    val confidence: Float,
    val reason: String
)

data class BatchSummaryRequest(
    val maxUsers: Int = 10
)

data class IndexCodebaseRequest(
    val projectId: String,
    val directory: String,
    val filePatterns: List<String>? = null
)

data class SearchCodeRequest(
    val query: String,
    val projectId: String? = null,
    val filePatterns: List<String>? = null,
    val topK: Int? = 5,
    val minScore: Float? = 0.6f
)

data class SearchCodeResponse(
    val results: List<CodeChunk>,
    val totalFound: Int
)

data class DeleteProjectResponse(
    val success: Boolean
)

data class InitCollectionsResponse(
    val conversationsCollection: Boolean,
    val knowledgeCollection: Boolean
)
