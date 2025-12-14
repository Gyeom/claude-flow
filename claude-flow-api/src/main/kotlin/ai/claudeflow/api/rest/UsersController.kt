package ai.claudeflow.api.rest

import ai.claudeflow.core.storage.Storage
import ai.claudeflow.core.storage.UserContext
import ai.claudeflow.core.storage.UserContextResponse
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * 사용자 관리 REST API ()
 *
 * 대비 개선점:
 * - 사용자 규칙 관리 (rules)
 * - 자동 요약 지원 (분산 잠금)
 * - 사용자 컨텍스트 포맷팅
 * - 대화 이력 조회
 */
@RestController
@RequestMapping("/api/v1/users")
@CrossOrigin(origins = ["http://localhost:3000", "http://localhost:3001"])
class UsersController(
    private val storage: Storage
) {
    /**
     * 사용자 컨텍스트 조회 ()
     *
     * GET /api/v1/users/{userId}/context
     * ?acquire_lock=true&lock_id=xxx (선택: 요약 잠금 획득)
     */
    @GetMapping("/{userId}/context")
    fun getUserContext(
        @PathVariable userId: String,
        @RequestParam(name = "acquire_lock", defaultValue = "false") acquireLock: Boolean,
        @RequestParam(name = "lock_id", required = false) lockId: String?
    ): Mono<ResponseEntity<UserContextResponse>> = mono {
        logger.info { "Get user context: $userId (acquireLock=$acquireLock)" }

        val response = storage.getUserContextResponse(userId, acquireLock, lockId)
        ResponseEntity.ok(response)
    }

    /**
     * 사용자 요약 저장
     *
     * PUT /api/v1/users/{userId}/context
     */
    @PutMapping("/{userId}/context")
    fun saveUserContext(
        @PathVariable userId: String,
        @RequestBody request: SaveUserContextRequest
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Save user context: $userId" }

        request.summary?.let {
            storage.saveUserSummary(userId, it)
        }

        request.displayName?.let { name ->
            val existing = storage.getUserContext(userId)
            if (existing != null) {
                storage.saveUserContext(existing.copy(displayName = name))
            }
        }

        ResponseEntity.ok(mapOf("success" to true))
    }

    /**
     * 사용자 요약 잠금 해제
     *
     * DELETE /api/v1/users/{userId}/context/lock
     */
    @DeleteMapping("/{userId}/context/lock")
    fun releaseSummaryLock(
        @PathVariable userId: String,
        @RequestParam(name = "lock_id") lockId: String
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Release summary lock: $userId (lockId=$lockId)" }

        val released = storage.releaseSummaryLock(userId, lockId)
        ResponseEntity.ok(mapOf("success" to released))
    }

    /**
     * 사용자 규칙 조회
     *
     * GET /api/v1/users/{userId}/rules
     */
    @GetMapping("/{userId}/rules")
    fun getUserRules(@PathVariable userId: String): Mono<ResponseEntity<UserRulesResponse>> = mono {
        logger.info { "Get user rules: $userId" }

        val rules = storage.getUserRules(userId)
        ResponseEntity.ok(UserRulesResponse(userId = userId, rules = rules))
    }

    /**
     * 사용자 규칙 추가
     *
     * POST /api/v1/users/{userId}/rules
     */
    @PostMapping("/{userId}/rules")
    fun addUserRule(
        @PathVariable userId: String,
        @RequestBody request: AddUserRuleRequest
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Add user rule: $userId - ${request.rule}" }

        val added = storage.addUserRule(userId, request.rule)
        if (added) {
            ResponseEntity.ok(mapOf("success" to true, "rule" to request.rule))
        } else {
            ResponseEntity.ok(mapOf("success" to false, "error" to "Rule already exists"))
        }
    }

    /**
     * 사용자 규칙 삭제
     *
     * DELETE /api/v1/users/{userId}/rules
     */
    @DeleteMapping("/{userId}/rules")
    fun deleteUserRule(
        @PathVariable userId: String,
        @RequestBody request: DeleteUserRuleRequest
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Delete user rule: $userId - ${request.rule}" }

        val deleted = storage.deleteUserRule(userId, request.rule)
        ResponseEntity.ok(mapOf("success" to deleted))
    }

    /**
     * 모든 사용자 목록 조회
     *
     * GET /api/v1/users
     */
    @GetMapping
    fun listUsers(): Mono<ResponseEntity<List<UserSummaryDto>>> = mono {
        logger.info { "List all users" }

        val users = storage.getAllUserContexts().map { ctx ->
            UserSummaryDto(
                userId = ctx.userId,
                displayName = ctx.displayName,
                totalInteractions = ctx.totalInteractions,
                lastSeen = ctx.lastSeen.toString(),
                hasSummary = !ctx.summary.isNullOrBlank()
            )
        }
        ResponseEntity.ok(users)
    }

    /**
     * 사용자 상세 조회
     *
     * GET /api/v1/users/{userId}
     */
    @GetMapping("/{userId}")
    fun getUser(@PathVariable userId: String): Mono<ResponseEntity<UserDetailDto>> = mono {
        logger.info { "Get user: $userId" }

        val context = storage.getUserContext(userId)
        if (context != null) {
            ResponseEntity.ok(UserDetailDto(
                userId = context.userId,
                displayName = context.displayName,
                preferredLanguage = context.preferredLanguage,
                domain = context.domain,
                totalInteractions = context.totalInteractions,
                totalChars = context.totalChars,
                lastSeen = context.lastSeen.toString(),
                summary = context.summary,
                summaryUpdatedAt = context.summaryUpdatedAt?.toString()
            ))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * 사용자 컨텍스트를 Markdown으로 포맷팅
     *
     * GET /api/v1/users/{userId}/context/formatted
     */
    @GetMapping("/{userId}/context/formatted")
    fun getFormattedContext(@PathVariable userId: String): Mono<ResponseEntity<FormattedContextResponse>> = mono {
        logger.info { "Get formatted context: $userId" }

        val contextResponse = storage.getUserContextResponse(userId)
        val formatted = formatUserContext(userId, contextResponse)

        ResponseEntity.ok(FormattedContextResponse(
            userId = userId,
            formattedContext = formatted,
            totalRules = contextResponse.rules.size,
            totalConversations = contextResponse.totalConversationCount
        ))
    }

    /**
     * 사용자 컨텍스트를 Markdown으로 포맷팅 ()
     */
    private fun formatUserContext(userId: String, context: UserContextResponse): String {
        val sb = StringBuilder()

        // 사용자 규칙
        if (context.rules.isNotEmpty()) {
            sb.appendLine("## User Rules")
            context.rules.forEach { rule ->
                sb.appendLine("• $rule")
            }
            sb.appendLine()
        }

        // 대화 요약
        if (!context.summary.isNullOrBlank()) {
            sb.appendLine("## User Summary")
            sb.appendLine(context.summary)
            sb.appendLine()
        }

        // 최근 대화 (간략히)
        if (context.recentConversations.isNotEmpty()) {
            sb.appendLine("## Recent Conversations (${context.recentConversations.size} of ${context.totalConversationCount})")
            context.recentConversations.take(3).forEach { conv ->
                sb.appendLine("- User: ${conv.userMessage.take(100)}...")
                conv.response?.let { resp ->
                    sb.appendLine("  Assistant: ${resp.take(100)}...")
                }
            }
        }

        return sb.toString().trim()
    }
}

// ==================== Request/Response DTOs ====================

data class SaveUserContextRequest(
    val summary: String? = null,
    val displayName: String? = null
)

data class AddUserRuleRequest(
    val rule: String
)

data class DeleteUserRuleRequest(
    val rule: String
)

data class UserRulesResponse(
    val userId: String,
    val rules: List<String>
)

data class UserSummaryDto(
    val userId: String,
    val displayName: String?,
    val totalInteractions: Int,
    val lastSeen: String,
    val hasSummary: Boolean
)

data class UserDetailDto(
    val userId: String,
    val displayName: String?,
    val preferredLanguage: String,
    val domain: String?,
    val totalInteractions: Int,
    val totalChars: Long,
    val lastSeen: String,
    val summary: String?,
    val summaryUpdatedAt: String?
)

data class FormattedContextResponse(
    val userId: String,
    val formattedContext: String,
    val totalRules: Int,
    val totalConversations: Int
)
