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
     * GitLab 답글 피드백 처리
     */
    @PostMapping("/gitlab-note")
    fun handleGitLabNote(@RequestBody request: GitLabNoteRequest): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "GitLab note feedback: noteId=${request.noteId}, parentNoteId=${request.parentNoteId}" }

        // 부모 코멘트가 AI 리뷰인지 확인
        val reviewRecord = if (request.parentNoteId != null) {
            storage.feedbackRepository.findReviewByDiscussionId(request.parentNoteId)
                ?: storage.feedbackRepository.findReviewByNoteId(request.parentNoteId.toIntOrNull() ?: 0)
        } else {
            null
        }

        if (reviewRecord == null) {
            return@mono ResponseEntity.ok(mapOf(
                "status" to "ignored",
                "reason" to "not_ai_comment_reply"
            ))
        }

        // 답글 감정 분석
        val sentiment = analyzeCommentSentiment(request.content)

        // 피드백 저장
        storage.feedbackRepository.saveGitLabFeedback(
            id = UUID.randomUUID().toString(),
            gitlabProjectId = request.projectId,
            mrIid = reviewRecord.mrIid,
            noteId = request.noteId,
            reaction = sentiment.reaction,
            userId = request.userId.toString(),
            source = "gitlab_note",
            comment = request.content
        )

        // 학습 서비스에 전달
        feedbackLearningService?.let { service ->
            try {
                service.learnFromComment(
                    mrContext = reviewRecord.mrContext ?: "MR #${reviewRecord.mrIid}",
                    reviewContent = reviewRecord.reviewContent,
                    userComment = request.content,
                    userId = request.userId.toString()
                )
            } catch (e: Exception) {
                logger.warn { "Failed to learn from GitLab note: ${e.message}" }
            }
        }

        ResponseEntity.ok(mapOf(
            "status" to "processed",
            "sentiment" to sentiment.type.name,
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

    /**
     * GitLab 리뷰에 대시보드 코멘트 추가
     */
    @PostMapping("/gitlab-review/{reviewId}/comment")
    fun addDashboardComment(
        @PathVariable reviewId: String,
        @RequestBody request: DashboardCommentRequest
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Add dashboard comment to review $reviewId" }

        storage.feedbackRepository.saveGitLabFeedback(
            id = UUID.randomUUID().toString(),
            gitlabProjectId = "",
            mrIid = 0,
            noteId = 0,
            reaction = "comment",
            userId = "dashboard",
            source = "dashboard",
            comment = request.comment
        )

        ResponseEntity.ok(mapOf("success" to true))
    }

    /**
     * 간단한 감정 분석
     */
    private fun analyzeCommentSentiment(content: String): SentimentResult {
        val lowerContent = content.lowercase()

        val positiveKeywords = listOf(
            "좋아요", "감사", "도움", "정확", "훌륭", "좋습니다", "맞아요",
            "good", "thanks", "helpful", "correct", "great", "agree", "nice"
        )
        val negativeKeywords = listOf(
            "틀렸", "아니", "잘못", "부정확", "오류", "버그", "문제",
            "wrong", "incorrect", "no", "error", "bug", "issue", "disagree"
        )

        return when {
            positiveKeywords.any { it in lowerContent } -> SentimentResult(GitLabFeedbackType.POSITIVE, "thumbsup")
            negativeKeywords.any { it in lowerContent } -> SentimentResult(GitLabFeedbackType.NEGATIVE, "thumbsdown")
            else -> SentimentResult(GitLabFeedbackType.NEUTRAL, "neutral")
        }
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

data class GitLabNoteRequest(
    val projectId: String,
    val noteId: Int,
    val parentNoteId: String?,  // discussion_id 또는 parent note_id
    val content: String,
    val userId: Int
)

data class DashboardCommentRequest(
    val comment: String
)

// ==================== Internal DTOs ====================

private data class SentimentResult(
    val type: GitLabFeedbackType,
    val reaction: String
)
