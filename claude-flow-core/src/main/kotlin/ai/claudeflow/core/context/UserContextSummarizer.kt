package ai.claudeflow.core.context

import ai.claudeflow.core.storage.RecentConversation
import ai.claudeflow.core.storage.UserContext
import ai.claudeflow.core.storage.UserContextResponse
import ai.claudeflow.core.storage.repository.ExecutionRepository
import ai.claudeflow.core.storage.repository.UserContextRepository
import ai.claudeflow.core.storage.repository.UserRuleRepository
import mu.KotlinLogging
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 사용자 컨텍스트 자동 요약 서비스
 *
 * 임계값 기반 자동 요약 트리거:
 * - 문자 수 임계값 (8KB)
 * - 대화 수 임계값 (5회)
 *
 * 분산 락을 통한 중복 요약 방지
 */
class UserContextSummarizer(
    private val userContextRepository: UserContextRepository,
    private val userRuleRepository: UserRuleRepository,
    private val executionRepository: ExecutionRepository,
    private val summaryGenerator: SummaryGenerator? = null
) {
    // 진행 중인 요약 작업 추적
    private val activeSummaries = ConcurrentHashMap<String, SummaryTask>()

    /**
     * 사용자 컨텍스트 조회 및 요약 필요 여부 확인
     *
     * @param userId 사용자 ID
     * @param acquireLock 요약 락 획득 시도 여부
     * @return 사용자 컨텍스트 응답 (요약 필요 여부 포함)
     */
    fun getUserContext(userId: String, acquireLock: Boolean = false): UserContextResponse {
        val lockId = if (acquireLock) UUID.randomUUID().toString() else null
        return userContextRepository.getUserContextResponse(
            userId = userId,
            acquireLock = acquireLock,
            lockId = lockId,
            userRuleRepository = userRuleRepository,
            executionRepository = executionRepository
        )
    }

    /**
     * 요약 필요 여부 확인 (간단 버전)
     */
    fun needsSummary(userId: String): SummaryStatus {
        val context = userContextRepository.findById(userId) ?: return SummaryStatus.NOT_NEEDED
        val conversationCount = executionRepository.countByUserId(userId).toInt()

        val needsSummary = UserContextResponse.needsSummary(
            context.totalChars,
            conversationCount,
            context.summaryUpdatedAt,
            context.summary
        )

        if (!needsSummary) return SummaryStatus.NOT_NEEDED

        // 락 확인
        val isLocked = context.summaryLockId != null && context.summaryLockAt?.let {
            Instant.now().epochSecond - it.epochSecond < UserContextResponse.SUMMARY_LOCK_TTL_SECS
        } ?: false

        return if (isLocked) SummaryStatus.LOCKED else SummaryStatus.NEEDED
    }

    /**
     * 요약 시작 (락 획득)
     *
     * @return 락 ID (성공 시) 또는 null (실패 시)
     */
    fun startSummary(userId: String): String? {
        val lockId = UUID.randomUUID().toString()
        val acquired = userContextRepository.acquireSummaryLock(userId, lockId)

        if (acquired) {
            activeSummaries[userId] = SummaryTask(
                lockId = lockId,
                startedAt = Instant.now(),
                userId = userId
            )
            logger.info { "Summary lock acquired for user $userId (lockId: $lockId)" }
            return lockId
        }

        logger.warn { "Failed to acquire summary lock for user $userId" }
        return null
    }

    /**
     * 요약 완료 및 저장
     *
     * @param userId 사용자 ID
     * @param lockId 락 ID (검증용)
     * @param summary 생성된 요약
     * @return 성공 여부
     */
    fun completeSummary(userId: String, lockId: String, summary: String): Boolean {
        // 락 검증
        val task = activeSummaries[userId]
        if (task?.lockId != lockId) {
            logger.warn { "Summary lock mismatch for user $userId" }
            return false
        }

        // 요약 저장 및 락 해제
        val success = userContextRepository.saveUserSummary(userId, summary)
        if (success) {
            activeSummaries.remove(userId)
            logger.info { "Summary saved for user $userId (${summary.length} chars)" }
        }

        return success
    }

    /**
     * 요약 취소 및 락 해제
     */
    fun cancelSummary(userId: String, lockId: String): Boolean {
        val released = userContextRepository.releaseSummaryLock(userId, lockId)
        if (released) {
            activeSummaries.remove(userId)
            logger.info { "Summary cancelled for user $userId" }
        }
        return released
    }

    /**
     * 자동 요약 실행 (SummaryGenerator가 설정된 경우)
     *
     * @return 생성된 요약 또는 null
     */
    suspend fun autoSummarize(userId: String): String? {
        val generator = summaryGenerator ?: run {
            logger.debug { "No summary generator configured" }
            return null
        }

        // 요약 필요 여부 확인
        val status = needsSummary(userId)
        if (status != SummaryStatus.NEEDED) {
            logger.debug { "Summary not needed for user $userId: $status" }
            return null
        }

        // 락 획득
        val lockId = startSummary(userId) ?: return null

        return try {
            // 최근 대화 조회
            val conversations = userContextRepository.getRecentConversations(
                userId, executionRepository, 20
            )

            // 기존 요약 조회
            val existingSummary = userContextRepository.findById(userId)?.summary

            // 요약 생성
            val summary = generator.generateSummary(
                userId = userId,
                existingSummary = existingSummary,
                recentConversations = conversations
            )

            // 요약 저장
            if (completeSummary(userId, lockId, summary)) {
                summary
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate summary for user $userId" }
            cancelSummary(userId, lockId)
            null
        }
    }

    /**
     * 만료된 락 정리
     */
    fun cleanupExpiredLocks() {
        val now = Instant.now()
        val expiredTasks = activeSummaries.filter { (_, task) ->
            now.epochSecond - task.startedAt.epochSecond > UserContextResponse.SUMMARY_LOCK_TTL_SECS
        }

        expiredTasks.forEach { (userId, task) ->
            userContextRepository.releaseSummaryLock(userId, task.lockId)
            activeSummaries.remove(userId)
            logger.info { "Cleaned up expired summary lock for user $userId" }
        }

        if (expiredTasks.isNotEmpty()) {
            logger.info { "Cleaned up ${expiredTasks.size} expired summary locks" }
        }
    }

    /**
     * 사용자 상호작용 기록 (요약 트리거 체크)
     *
     * @return 요약이 필요해지면 true
     */
    fun recordInteraction(userId: String, promptLength: Int, responseLength: Int): Boolean {
        userContextRepository.updateUserInteraction(userId, promptLength, responseLength)

        // 요약 필요 여부 확인
        return needsSummary(userId) == SummaryStatus.NEEDED
    }
}

/**
 * 요약 생성기 인터페이스
 */
interface SummaryGenerator {
    /**
     * 사용자 대화 요약 생성
     *
     * @param userId 사용자 ID
     * @param existingSummary 기존 요약 (있으면)
     * @param recentConversations 최근 대화 목록
     * @return 생성된 요약
     */
    suspend fun generateSummary(
        userId: String,
        existingSummary: String?,
        recentConversations: List<RecentConversation>
    ): String
}

/**
 * 요약 상태
 */
enum class SummaryStatus {
    NOT_NEEDED,  // 요약 불필요
    NEEDED,      // 요약 필요
    LOCKED       // 다른 프로세스가 요약 중
}

/**
 * 진행 중인 요약 작업
 */
data class SummaryTask(
    val lockId: String,
    val startedAt: Instant,
    val userId: String
)

/**
 * 요약 결과
 */
data class SummaryResult(
    val userId: String,
    val summary: String,
    val conversationCount: Int,
    val generatedAt: Instant = Instant.now()
)
