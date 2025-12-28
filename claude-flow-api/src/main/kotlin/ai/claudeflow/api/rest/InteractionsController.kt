package ai.claudeflow.api.rest

import ai.claudeflow.core.storage.*
import ai.claudeflow.core.storage.repository.SourceStats
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * 통합 Interactions API
 *
 * Slack, Chat, MR Review, API 등 모든 상호작용을 통합 조회
 */
@RestController
@RequestMapping("/api/v1/interactions")
class InteractionsController(
    private val storage: Storage
) {
    /**
     * 통합 조회 (필터링 지원)
     *
     * @param sources 소스 필터 (쉼표 구분: slack,chat,mr_review,other)
     * @param search 검색어 (prompt, result에서 검색)
     * @param days 조회 기간 (일)
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지 크기
     */
    @GetMapping
    fun getInteractions(
        @RequestParam(required = false) sources: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "30") days: Int,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int
    ): Mono<ResponseEntity<InteractionsResponse>> = mono {
        logger.info { "Get interactions: sources=$sources, search=$search, days=$days, page=$page, size=$size" }

        val sourceList = sources?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val dateRange = DateRange.lastDays(days)
        val pageRequest = PageRequest(page, size)

        val items = storage.executionRepository.findByFilters(
            sources = sourceList,
            search = search,
            dateRange = dateRange,
            pageRequest = pageRequest
        )

        val totalCount = storage.executionRepository.countByFilters(
            sources = sourceList,
            search = search,
            dateRange = dateRange
        )

        // Batch fetch feedbacks for all execution IDs
        val executionIds = items.map { it.id }
        val feedbacksByExecutionId = storage.feedbackRepository.findByExecutionIds(executionIds).toMutableMap()

        // MR 리뷰 실행의 경우 GitLab 피드백도 조회 (gitlabPath + mrIid 기반)
        // Chat에서 MR 리뷰 요청 시 prompt에 GitLab path와 MR IID가 포함되어 있음
        val mrReviewItems = items.filter {
            (it.source == "mr_review" || it.source == "chat") &&
            (it.mrIid != null || it.prompt.contains("**MR**:"))
        }
        if (mrReviewItems.isNotEmpty()) {
            // 프로젝트 ID -> GitLab path 매핑 캐시
            val projectGitlabPaths = mutableMapOf<String, String>()
            mrReviewItems.mapNotNull { it.projectId }.distinct().forEach { projectId ->
                storage.projectRepository.findById(projectId)?.gitlabPath?.let { path ->
                    projectGitlabPaths[projectId] = path
                }
            }

            // GitLab path + MR IID 쌍 추출
            val projectMrPairs = mrReviewItems.mapNotNull { exec ->
                val gitlabPath = exec.projectId?.let { projectGitlabPaths[it] }
                    ?: extractGitlabPathFromPrompt(exec.prompt)
                val mrIid = exec.mrIid ?: extractMrIidFromPrompt(exec.prompt)
                if (gitlabPath != null && mrIid != null) {
                    Pair(gitlabPath, mrIid)
                } else null
            }.distinct()

            if (projectMrPairs.isNotEmpty()) {
                val gitlabFeedbacks = storage.feedbackRepository.findGitLabFeedbackByProjectMrs(projectMrPairs)

                // GitLab 피드백을 execution ID 기준으로 매핑
                mrReviewItems.forEach { exec ->
                    val gitlabPath = exec.projectId?.let { projectGitlabPaths[it] }
                        ?: extractGitlabPathFromPrompt(exec.prompt)
                    val mrIid = exec.mrIid ?: extractMrIidFromPrompt(exec.prompt)
                    if (gitlabPath != null && mrIid != null) {
                        val key = "$gitlabPath:$mrIid"
                        val gitlabFbs = gitlabFeedbacks[key] ?: emptyList()
                        if (gitlabFbs.isNotEmpty()) {
                            val existing = feedbacksByExecutionId[exec.id] ?: emptyList()
                            feedbacksByExecutionId[exec.id] = existing + gitlabFbs
                        }
                    }
                }
            }
        }

        val response = InteractionsResponse(
            items = items.map { it.toDto(feedbacksByExecutionId[it.id]) },
            totalCount = totalCount,
            page = page,
            size = size,
            totalPages = ((totalCount + size - 1) / size).toInt()
        )

        ResponseEntity.ok(response)
    }

    /**
     * 단건 조회
     */
    @GetMapping("/{id}")
    fun getInteraction(@PathVariable id: String): Mono<ResponseEntity<InteractionDto>> = mono {
        logger.info { "Get interaction: $id" }

        val record = storage.executionRepository.findById(id)
            ?: return@mono ResponseEntity.notFound().build()

        // 피드백 조회
        val feedbacks = storage.feedbackRepository.findByExecutionId(id)

        ResponseEntity.ok(record.toDto(feedbacks))
    }

    /**
     * Source별 통계
     */
    @GetMapping("/stats/by-source")
    fun getStatsBySource(
        @RequestParam(defaultValue = "30") days: Int
    ): Mono<ResponseEntity<List<InteractionSourceStats>>> = mono {
        logger.info { "Get stats by source for $days days" }

        val dateRange = DateRange.lastDays(days)
        val stats = storage.executionRepository.getStatsBySource(dateRange)

        ResponseEntity.ok(stats.map { InteractionSourceStats(
            source = it.source,
            displayName = ExecutionRecord.getSourceDisplayName(it.source),
            icon = ExecutionRecord.getSourceIcon(it.source),
            count = it.requests,
            successRate = it.successRate
        )})
    }

    /**
     * ExecutionRecord를 DTO로 변환
     */
    private fun ExecutionRecord.toDto(feedbacks: List<FeedbackRecord>? = null): InteractionDto {
        val feedbackList = feedbacks ?: emptyList()
        val positiveCount = feedbackList.count {
            it.reaction in listOf("thumbsup", "+1", "heart", "tada")
        }
        val negativeCount = feedbackList.count {
            it.reaction in listOf("thumbsdown", "-1")
        }

        return InteractionDto(
            id = id,
            source = source ?: "other",
            sourceDisplayName = ExecutionRecord.getSourceDisplayName(source),
            sourceIcon = ExecutionRecord.getSourceIcon(source),
            prompt = prompt,
            result = result,
            status = status,
            agentId = agentId,
            projectId = projectId,
            userId = userId,
            channel = channel,
            model = model,
            durationMs = durationMs,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cost = cost,
            error = error,
            // MR 관련
            mrIid = mrIid,
            gitlabNoteId = gitlabNoteId,
            mrContext = mrContext,
            // 피드백
            feedbackPositive = positiveCount,
            feedbackNegative = negativeCount,
            feedbacks = feedbackList.map { FeedbackDto(
                id = it.id,
                userId = it.userId,
                reaction = it.reaction,
                source = it.source,
                isVerified = it.isVerified,
                createdAt = it.createdAt.toString()
            )},
            createdAt = createdAt.toString()
        )
    }

    /**
     * 프롬프트에서 GitLab path 추출
     * 예: "**프로젝트**: group/subgroup/project-name" -> "group/subgroup/project-name"
     */
    private fun extractGitlabPathFromPrompt(prompt: String): String? {
        // **프로젝트**: group/subgroup/xxx 패턴
        val projectPattern = Regex("""\*\*프로젝트\*\*:\s*([^\s\n]+)""")
        projectPattern.find(prompt)?.groupValues?.get(1)?.let { return it }

        // gitlab.xxx/path 패턴
        val gitlabUrlPattern = Regex("""gitlab\.[^/]+/([^/]+/[^/]+/[^\s\n/-]+)""")
        gitlabUrlPattern.find(prompt)?.groupValues?.get(1)?.let { return it }

        return null
    }

    /**
     * 프롬프트에서 MR IID 추출
     * 예: "**MR**: !1 - refactor: ..." -> 1
     */
    private fun extractMrIidFromPrompt(prompt: String): Int? {
        // **MR**: !N 패턴
        val mrPattern = Regex("""\*\*MR\*\*:\s*!(\d+)""")
        mrPattern.find(prompt)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        // merge_requests/N 패턴
        val urlPattern = Regex("""merge_requests/(\d+)""")
        urlPattern.find(prompt)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        return null
    }
}

// ==================== DTOs ====================

data class InteractionsResponse(
    val items: List<InteractionDto>,
    val totalCount: Long,
    val page: Int,
    val size: Int,
    val totalPages: Int
)

data class InteractionDto(
    val id: String,
    val source: String,
    val sourceDisplayName: String,
    val sourceIcon: String,
    val prompt: String,
    val result: String?,
    val status: String,
    val agentId: String,
    val projectId: String?,
    val userId: String?,
    val channel: String?,
    val model: String?,
    val durationMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val cost: Double?,
    val error: String?,
    // MR 관련
    val mrIid: Int?,
    val gitlabNoteId: Int?,
    val mrContext: String?,
    // 피드백
    val feedbackPositive: Int,
    val feedbackNegative: Int,
    val feedbacks: List<FeedbackDto>,
    val createdAt: String
)

data class FeedbackDto(
    val id: String,
    val userId: String,
    val reaction: String,
    val source: String,
    val isVerified: Boolean,
    val createdAt: String
)

data class InteractionSourceStats(
    val source: String,
    val displayName: String,
    val icon: String,
    val count: Long,
    val successRate: Double
)
