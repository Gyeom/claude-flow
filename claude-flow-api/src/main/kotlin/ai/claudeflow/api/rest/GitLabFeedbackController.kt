package ai.claudeflow.api.rest

import ai.claudeflow.core.rag.FeedbackLearningService
import ai.claudeflow.core.storage.GitLabFeedbackType
import ai.claudeflow.core.storage.GitLabReviewRecord
import ai.claudeflow.core.storage.Storage
import ai.claudeflow.core.storage.DateRange
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * GitLab 피드백 수집 API
 *
 * n8n 워크플로우에서 호출하여:
 * 1. AI 리뷰 코멘트 기록 저장
 * 2. 이모지 피드백 (👍/👎) 처리
 * 3. 답글 피드백 처리
 */
@RestController
@RequestMapping("/api/v1/feedback")
class GitLabFeedbackController(
    private val storage: Storage,
    private val feedbackLearningService: FeedbackLearningService?
) {

    /**
     * AI 리뷰 코멘트 기록 저장
     * 스케줄 MR 리뷰 후 호출
     */
    @PostMapping("/gitlab-review")
    fun saveGitLabReview(@RequestBody request: GitLabReviewRequest): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Save GitLab review: project=${request.projectId}, mr=${request.mrIid}, note=${request.noteId}" }

        val record = GitLabReviewRecord(
            id = UUID.randomUUID().toString(),
            projectId = request.projectId,
            mrIid = request.mrIid,
            noteId = request.noteId,
            discussionId = request.discussionId,
            reviewContent = request.reviewContent,
            mrContext = request.mrContext
        )

        storage.feedbackRepository.saveReviewRecord(record)

        ResponseEntity.ok(mapOf(
            "success" to true,
            "id" to record.id,
            "noteId" to request.noteId
        ))
    }

    /**
     * AI 리뷰 코멘트 조회 (note_id로)
     */
    @GetMapping("/gitlab-review")
    fun getGitLabReview(@RequestParam noteId: Int): Mono<ResponseEntity<Any>> = mono {
        logger.debug { "Get GitLab review: noteId=$noteId" }

        val record = storage.feedbackRepository.findReviewByNoteId(noteId)
        if (record != null) {
            ResponseEntity.ok(mapOf(
                "found" to true,
                "id" to record.id,
                "projectId" to record.projectId,
                "mrIid" to record.mrIid,
                "noteId" to record.noteId,
                "mrContext" to record.mrContext
            ) as Any)
        } else {
            ResponseEntity.ok(mapOf(
                "found" to false,
                "noteId" to noteId
            ) as Any)
        }
    }

    /**
     * GitLab 이모지 피드백 처리 (👍/👎)
     */
    @PostMapping("/gitlab-emoji")
    fun handleGitLabEmoji(@RequestBody request: GitLabEmojiRequest): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "GitLab emoji feedback: noteId=${request.noteId}, emoji=${request.emoji}, action=${request.action}" }

        // AI 코멘트인지 확인
        val reviewRecord = storage.feedbackRepository.findReviewByNoteId(request.noteId)
        if (reviewRecord == null) {
            return@mono ResponseEntity.ok(mapOf(
                "status" to "ignored",
                "reason" to "not_ai_comment"
            ))
        }

        // 유효한 피드백 이모지인지 확인
        val feedbackType = when (request.emoji) {
            "thumbsup", "+1" -> GitLabFeedbackType.POSITIVE
            "thumbsdown", "-1" -> GitLabFeedbackType.NEGATIVE
            else -> {
                return@mono ResponseEntity.ok(mapOf(
                    "status" to "ignored",
                    "reason" to "not_feedback_emoji",
                    "emoji" to request.emoji
                ))
            }
        }

        // 피드백 저장 (action이 created인 경우만)
        if (request.action == "created" || request.action == "award") {
            // 중복 체크 - 동일한 사용자가 동일한 코멘트에 동일한 이모지를 이미 추가했는지 확인
            val alreadyExists = storage.feedbackRepository.existsGitLabFeedback(
                noteId = request.noteId,
                userId = request.userId.toString(),
                reaction = request.emoji
            )

            if (alreadyExists) {
                logger.debug { "Duplicate GitLab emoji feedback ignored: noteId=${request.noteId}, userId=${request.userId}, emoji=${request.emoji}" }
                return@mono ResponseEntity.ok(mapOf(
                    "status" to "ignored",
                    "reason" to "duplicate_feedback",
                    "noteId" to request.noteId
                ))
            }

            storage.feedbackRepository.saveGitLabFeedback(
                id = UUID.randomUUID().toString(),
                gitlabProjectId = request.projectId,
                mrIid = reviewRecord.mrIid,
                noteId = request.noteId,
                reaction = request.emoji,
                userId = request.userId.toString(),
                source = "gitlab_emoji"
            )

            // 학습 서비스에 전달
            feedbackLearningService?.let { service ->
                try {
                    service.learnFromGitLabFeedback(
                        mrContext = reviewRecord.mrContext ?: "MR #${reviewRecord.mrIid}",
                        reviewContent = reviewRecord.reviewContent,
                        feedbackType = feedbackType,
                        userId = request.userId.toString()
                    )
                } catch (e: Exception) {
                    logger.warn { "Failed to learn from GitLab feedback: ${e.message}" }
                }
            }
        }

        ResponseEntity.ok(mapOf(
            "status" to "processed",
            "feedbackType" to feedbackType.name,
            "reviewId" to reviewRecord.id
        ))
    }

    /**
     * GitLab 피드백 통계 조회
     */
    @GetMapping("/gitlab-stats")
    fun getGitLabFeedbackStats(
        @RequestParam(required = false) days: Int?
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val dateRange: DateRange? = days?.let { d ->
            DateRange.lastDays(d)
        }

        val stats = storage.feedbackRepository.getGitLabFeedbackStats(dateRange)

        ResponseEntity.ok(mapOf(
            "positive" to stats.positive,
            "negative" to stats.negative,
            "satisfactionRate" to stats.satisfactionRate,
            "total" to (stats.positive + stats.negative)
        ))
    }

    /**
     * GitLab 리뷰 목록 조회 (대시보드용)
     */
    @GetMapping("/gitlab-reviews")
    fun getGitLabReviews(
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) days: Int?
    ): Mono<ResponseEntity<List<Map<String, Any?>>>> = mono {
        logger.info { "Get GitLab reviews: projectId=$projectId, days=$days" }

        val reviews = if (projectId != null) {
            storage.feedbackRepository.findReviewsByProject(projectId)
        } else {
            storage.feedbackRepository.findAllReviews(days ?: 30)
        }

        val result = reviews.map { review ->
            val feedback = storage.feedbackRepository.findGitLabFeedbackByNoteId(review.noteId)
            mapOf(
                "id" to review.id,
                "projectId" to review.projectId,
                "mrIid" to review.mrIid,
                "noteId" to review.noteId,
                "discussionId" to review.discussionId,
                "reviewContent" to review.reviewContent,
                "mrContext" to review.mrContext,
                "createdAt" to review.createdAt.toString(),
                "feedback" to feedback.map { fb ->
                    mapOf(
                        "id" to fb.id,
                        "noteId" to review.noteId,
                        "reaction" to fb.reaction,
                        "userId" to fb.userId,
                        "source" to "gitlab",
                        "comment" to null,
                        "createdAt" to fb.createdAt.toString()
                    )
                }
            )
        }

        ResponseEntity.ok(result)
    }
}

// ==================== Request DTOs ====================

data class GitLabReviewRequest(
    val projectId: String,
    val mrIid: Int,
    val noteId: Int,
    val discussionId: String? = null,
    val reviewContent: String,
    val mrContext: String? = null
)

data class GitLabEmojiRequest(
    val projectId: String,
    val noteId: Int,
    val emoji: String,      // thumbsup, thumbsdown, +1, -1
    val userId: Int,
    val action: String      // created, deleted, award
)
