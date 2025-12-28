package ai.claudeflow.api.rest

import ai.claudeflow.core.storage.*
import ai.claudeflow.core.storage.repository.AdminFeedbackRepository
import ai.claudeflow.core.storage.repository.ExecutionRepository
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * 관리자 상세 피드백 API
 *
 * 관리자가 직접 평가하는 고품질 피드백 관리
 */
@RestController
@RequestMapping("/api/v1/admin/feedback")
class AdminFeedbackController(
    private val storage: Storage? = null
) {
    private val adminFeedbackRepository: AdminFeedbackRepository?
        get() = storage?.adminFeedbackRepository

    private val executionRepository: ExecutionRepository?
        get() = storage?.executionRepository

    /**
     * 관리자 피드백 저장/수정
     */
    @PostMapping
    fun saveAdminFeedback(
        @RequestBody request: AdminFeedbackRequest
    ): Mono<ResponseEntity<AdminFeedbackResponse>> = mono {
        val repo = adminFeedbackRepository
            ?: return@mono ResponseEntity.ok(
                AdminFeedbackResponse(success = false, error = "Storage not configured")
            )

        try {
            // 기존 피드백 확인
            val existing = repo.findByExecutionId(request.executionId)
            val now = Instant.now()

            val feedback = AdminFeedback(
                id = existing?.id ?: UUID.randomUUID().toString(),
                executionId = request.executionId,
                adminId = request.adminId,
                quickRating = request.quickRating,
                correctness = request.correctness,
                helpfulness = request.helpfulness,
                verbosity = request.verbosity,
                issues = request.issues,
                comment = request.comment,
                isExemplary = request.isExemplary,
                goldResponse = request.goldResponse,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )

            repo.save(feedback)

            logger.info {
                "Admin feedback saved: ${request.executionId} " +
                "(rating=${request.quickRating}, exemplary=${request.isExemplary})"
            }

            ResponseEntity.ok(AdminFeedbackResponse(
                success = true,
                feedback = feedback.toDto()
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to save admin feedback" }
            ResponseEntity.ok(AdminFeedbackResponse(success = false, error = e.message))
        }
    }

    /**
     * execution_id로 관리자 피드백 조회
     */
    @GetMapping("/execution/{executionId}")
    fun getByExecutionId(
        @PathVariable executionId: String
    ): Mono<ResponseEntity<AdminFeedbackDto?>> = mono {
        val feedback = adminFeedbackRepository?.findByExecutionId(executionId)
        ResponseEntity.ok(feedback?.toDto())
    }

    /**
     * 우수 사례 목록 조회 (Few-shot 예제용)
     */
    @GetMapping("/exemplary")
    fun getExemplary(
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(required = false) agentId: String?
    ): Mono<ResponseEntity<List<ExemplaryFeedbackDto>>> = mono {
        val repo = adminFeedbackRepository
            ?: return@mono ResponseEntity.ok(emptyList())

        val exemplary = if (agentId != null) {
            repo.findExemplaryByAgent(agentId, limit)
        } else {
            repo.findExemplary(limit)
        }

        // execution 정보와 함께 반환
        val result = exemplary.mapNotNull { feedback ->
            val execution = executionRepository?.findById(feedback.executionId)
            if (execution != null) {
                ExemplaryFeedbackDto(
                    id = feedback.id,
                    executionId = feedback.executionId,
                    prompt = execution.prompt,
                    response = execution.result ?: "",
                    agentId = execution.agentId,
                    comment = feedback.comment,
                    correctness = feedback.correctness,
                    helpfulness = feedback.helpfulness
                )
            } else null
        }

        ResponseEntity.ok(result)
    }

    /**
     * Gold Response 목록 조회 (학습 데이터용)
     */
    @GetMapping("/gold-responses")
    fun getGoldResponses(
        @RequestParam(defaultValue = "50") limit: Int
    ): Mono<ResponseEntity<List<GoldResponseDto>>> = mono {
        val repo = adminFeedbackRepository
            ?: return@mono ResponseEntity.ok(emptyList())

        val feedbacks = repo.findWithGoldResponse(limit)

        val result = feedbacks.mapNotNull { feedback ->
            val execution = executionRepository?.findById(feedback.executionId)
            val goldResp = feedback.goldResponse
            if (execution != null && goldResp != null) {
                GoldResponseDto(
                    id = feedback.id,
                    executionId = feedback.executionId,
                    prompt = execution.prompt,
                    originalResponse = execution.result ?: "",
                    goldResponse = goldResp,
                    agentId = execution.agentId,
                    issues = feedback.issues.map { it.name }
                )
            } else null
        }

        ResponseEntity.ok(result)
    }

    /**
     * 미평가 execution 목록 조회 (관리자 작업 대기열)
     */
    @GetMapping("/pending")
    fun getPendingExecutions(
        @RequestParam(defaultValue = "50") limit: Int
    ): Mono<ResponseEntity<List<PendingExecutionDto>>> = mono {
        val repo = adminFeedbackRepository
            ?: return@mono ResponseEntity.ok(emptyList())
        val execRepo = executionRepository
            ?: return@mono ResponseEntity.ok(emptyList())

        val pendingIds = repo.findPendingExecutions(limit)

        val result = pendingIds.mapNotNull { id ->
            val execution = execRepo.findById(id)
            if (execution != null) {
                PendingExecutionDto(
                    executionId = execution.id,
                    prompt = execution.prompt,
                    response = execution.result ?: "",
                    agentId = execution.agentId,
                    createdAt = execution.createdAt.toString()
                )
            } else null
        }

        ResponseEntity.ok(result)
    }

    /**
     * 관리자 피드백 통계
     */
    @GetMapping("/stats")
    fun getStats(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?
    ): Mono<ResponseEntity<AdminFeedbackStatsDto>> = mono {
        val repo = adminFeedbackRepository
            ?: return@mono ResponseEntity.ok(AdminFeedbackStatsDto.empty())

        val dateRange = if (from != null && to != null) {
            DateRange(Instant.parse(from), Instant.parse(to))
        } else null

        val stats = repo.getStats(dateRange)

        ResponseEntity.ok(AdminFeedbackStatsDto(
            totalReviewed = stats.totalReviewed,
            positiveCount = stats.positiveCount,
            negativeCount = stats.negativeCount,
            pendingCount = stats.pendingCount,
            exemplaryCount = stats.exemplaryCount,
            goldResponseCount = stats.goldResponseCount,
            avgCorrectness = stats.avgCorrectness,
            avgHelpfulness = stats.avgHelpfulness,
            avgVerbosity = stats.avgVerbosity,
            issueDistribution = stats.issueDistribution.map { (issue, count) ->
                IssueCount(issue.name, issue.displayName, count)
            }
        ))
    }

    /**
     * 에이전트별 평균 점수
     */
    @GetMapping("/agent-scores")
    fun getAgentScores(): Mono<ResponseEntity<Map<String, Double>>> = mono {
        val scores = adminFeedbackRepository?.getAgentScores() ?: emptyMap()
        ResponseEntity.ok(scores)
    }

    /**
     * 피드백 이슈 타입 목록
     */
    @GetMapping("/issue-types")
    fun getIssueTypes(): Mono<ResponseEntity<List<IssueTypeDto>>> = mono {
        val types = FeedbackIssue.entries.map { issue ->
            IssueTypeDto(
                name = issue.name,
                displayName = issue.displayName,
                description = issue.description
            )
        }
        ResponseEntity.ok(types)
    }
}

// ==================== Request/Response DTOs ====================

data class AdminFeedbackRequest(
    val executionId: String,
    val adminId: String,
    val quickRating: QuickRating,
    val correctness: Int? = null,
    val helpfulness: Int? = null,
    val verbosity: Int? = null,
    val issues: List<FeedbackIssue> = emptyList(),
    val comment: String? = null,
    val isExemplary: Boolean = false,
    val goldResponse: String? = null
)

data class AdminFeedbackResponse(
    val success: Boolean,
    val error: String? = null,
    val feedback: AdminFeedbackDto? = null
)

data class AdminFeedbackDto(
    val id: String,
    val executionId: String,
    val adminId: String,
    val quickRating: String,
    val correctness: Int?,
    val helpfulness: Int?,
    val verbosity: Int?,
    val issues: List<String>,
    val comment: String?,
    val isExemplary: Boolean,
    val goldResponse: String?,
    val createdAt: String,
    val updatedAt: String
)

data class ExemplaryFeedbackDto(
    val id: String,
    val executionId: String,
    val prompt: String,
    val response: String,
    val agentId: String?,
    val comment: String?,
    val correctness: Int?,
    val helpfulness: Int?
)

data class GoldResponseDto(
    val id: String,
    val executionId: String,
    val prompt: String,
    val originalResponse: String,
    val goldResponse: String,
    val agentId: String?,
    val issues: List<String>
)

data class PendingExecutionDto(
    val executionId: String,
    val prompt: String,
    val response: String,
    val agentId: String?,
    val createdAt: String
)

data class AdminFeedbackStatsDto(
    val totalReviewed: Long,
    val positiveCount: Long,
    val negativeCount: Long,
    val pendingCount: Long,
    val exemplaryCount: Long,
    val goldResponseCount: Long,
    val avgCorrectness: Double?,
    val avgHelpfulness: Double?,
    val avgVerbosity: Double?,
    val issueDistribution: List<IssueCount>
) {
    companion object {
        fun empty() = AdminFeedbackStatsDto(0, 0, 0, 0, 0, 0, null, null, null, emptyList())
    }
}

data class IssueCount(
    val name: String,
    val displayName: String,
    val count: Long
)

data class IssueTypeDto(
    val name: String,
    val displayName: String,
    val description: String
)

// Extension function
private fun AdminFeedback.toDto() = AdminFeedbackDto(
    id = id,
    executionId = executionId,
    adminId = adminId,
    quickRating = quickRating.name,
    correctness = correctness,
    helpfulness = helpfulness,
    verbosity = verbosity,
    issues = issues.map { it.name },
    comment = comment,
    isExemplary = isExemplary,
    goldResponse = goldResponse,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)
