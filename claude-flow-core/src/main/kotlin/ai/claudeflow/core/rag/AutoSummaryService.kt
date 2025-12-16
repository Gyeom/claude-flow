package ai.claudeflow.core.rag

import ai.claudeflow.core.storage.UserContext
import ai.claudeflow.core.storage.ExecutionRecord
import ai.claudeflow.core.storage.repository.UserContextRepository
import ai.claudeflow.core.storage.repository.ExecutionRepository
import mu.KotlinLogging
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * 자동 요약 생성 서비스
 *
 * 사용자 대화가 일정 수준 이상 쌓이면 자동으로 요약 생성
 * 분산 환경에서의 동시성 제어를 위해 분산 잠금 사용
 */
class AutoSummaryService(
    private val userContextRepository: UserContextRepository,
    private val executionRepository: ExecutionRepository,
    private val summaryGenerator: SummaryGenerator = ExtractivesSummaryGenerator()
) {
    companion object {
        // 요약 트리거 조건
        const val MIN_CONVERSATIONS = 5          // 최소 대화 수
        const val MIN_CHARS = 5000L              // 최소 문자 수
        const val MIN_INTERVAL_HOURS = 6L        // 최소 요약 간격 (시간)
        const val LOCK_TTL_MINUTES = 5L          // 잠금 유지 시간
        const val MAX_CONVERSATIONS_TO_SUMMARIZE = 50  // 요약할 최대 대화 수
    }

    /**
     * 사용자 요약 필요 여부 확인
     */
    fun needsSummary(userId: String): SummaryNeedResult {
        val userContext = userContextRepository.findById(userId)
            ?: return SummaryNeedResult(false, "사용자 컨텍스트 없음")

        val conversationCount = executionRepository.countByUserId(userId).toInt()

        // 최소 대화 수 체크
        if (conversationCount < MIN_CONVERSATIONS) {
            return SummaryNeedResult(false, "대화 수 부족: $conversationCount < $MIN_CONVERSATIONS")
        }

        // 최소 문자 수 체크
        if (userContext.totalChars < MIN_CHARS) {
            return SummaryNeedResult(false, "문자 수 부족: ${userContext.totalChars} < $MIN_CHARS")
        }

        // 요약 간격 체크
        userContext.summaryUpdatedAt?.let { lastUpdate ->
            val hoursSince = ChronoUnit.HOURS.between(lastUpdate, Instant.now())
            if (hoursSince < MIN_INTERVAL_HOURS) {
                return SummaryNeedResult(false, "간격 부족: ${hoursSince}시간 < ${MIN_INTERVAL_HOURS}시간")
            }
        }

        // 이미 잠금 중인지 체크
        if (isLocked(userContext)) {
            return SummaryNeedResult(false, "다른 프로세스가 요약 생성 중")
        }

        return SummaryNeedResult(
            needed = true,
            reason = "요약 필요: ${conversationCount}개 대화, ${userContext.totalChars}자"
        )
    }

    /**
     * 사용자 요약 생성 (비동기 안전)
     */
    fun generateSummary(userId: String): SummaryGenerationResult {
        val lockId = UUID.randomUUID().toString()

        // 1. 잠금 획득 시도
        if (!userContextRepository.acquireSummaryLock(userId, lockId)) {
            return SummaryGenerationResult(
                success = false,
                error = "잠금 획득 실패 - 다른 프로세스가 처리 중"
            )
        }

        try {
            // 2. 최근 대화 조회
            val recentConversations = executionRepository.findByUserId(userId)
                .filter { it.status == "SUCCESS" && !it.result.isNullOrBlank() }
                .take(MAX_CONVERSATIONS_TO_SUMMARIZE)

            if (recentConversations.isEmpty()) {
                return SummaryGenerationResult(success = false, error = "요약할 대화 없음")
            }

            // 3. 기존 요약 조회
            val existingSummary = userContextRepository.findById(userId)?.summary

            // 4. 요약 생성
            val startTime = System.currentTimeMillis()
            val summary = summaryGenerator.generateSummary(
                conversations = recentConversations,
                existingSummary = existingSummary
            )
            val generationTimeMs = System.currentTimeMillis() - startTime

            // 5. 요약 저장
            userContextRepository.saveUserSummary(userId, summary)

            logger.info { "Generated summary for user $userId: ${summary.length} chars in ${generationTimeMs}ms" }

            return SummaryGenerationResult(
                success = true,
                summary = summary,
                conversationCount = recentConversations.size,
                generationTimeMs = generationTimeMs
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate summary for user $userId" }
            return SummaryGenerationResult(success = false, error = e.message)
        } finally {
            // 6. 잠금 해제
            userContextRepository.releaseSummaryLock(userId, lockId)
        }
    }

    /**
     * 요약이 필요한 모든 사용자 찾기
     */
    fun findUsersNeedingSummary(): List<String> {
        return userContextRepository.findAll()
            .filter { needsSummary(it.userId).needed }
            .map { it.userId }
    }

    /**
     * 배치 요약 생성 (스케줄러용)
     */
    fun batchGenerateSummaries(maxUsers: Int = 10): BatchSummaryResult {
        val usersNeedingSummary = findUsersNeedingSummary().take(maxUsers)
        var successCount = 0
        var failCount = 0
        val errors = mutableListOf<String>()

        for (userId in usersNeedingSummary) {
            val result = generateSummary(userId)
            if (result.success) {
                successCount++
            } else {
                failCount++
                result.error?.let { errors.add("$userId: $it") }
            }
        }

        logger.info { "Batch summary generation: $successCount success, $failCount failed" }

        return BatchSummaryResult(
            totalProcessed = usersNeedingSummary.size,
            successCount = successCount,
            failCount = failCount,
            errors = errors
        )
    }

    private fun isLocked(userContext: UserContext): Boolean {
        val lockAt = userContext.summaryLockAt ?: return false
        val minutesSinceLock = ChronoUnit.MINUTES.between(lockAt, Instant.now())
        return minutesSinceLock < LOCK_TTL_MINUTES
    }
}

/**
 * 요약 필요 여부 결과
 */
data class SummaryNeedResult(
    val needed: Boolean,
    val reason: String
)

/**
 * 요약 생성 결과
 */
data class SummaryGenerationResult(
    val success: Boolean,
    val summary: String? = null,
    val conversationCount: Int = 0,
    val generationTimeMs: Long = 0,
    val error: String? = null
)

/**
 * 배치 요약 결과
 */
data class BatchSummaryResult(
    val totalProcessed: Int,
    val successCount: Int,
    val failCount: Int,
    val errors: List<String>
)

/**
 * 요약 생성기 인터페이스
 */
interface SummaryGenerator {
    fun generateSummary(
        conversations: List<ExecutionRecord>,
        existingSummary: String?
    ): String
}

/**
 * 추출 기반 요약 생성기 (LLM 없이 키워드/주제 추출)
 */
class ExtractivesSummaryGenerator : SummaryGenerator {
    override fun generateSummary(
        conversations: List<ExecutionRecord>,
        existingSummary: String?
    ): String {
        // 1. 주요 주제 추출
        val topics = extractTopics(conversations)

        // 2. 자주 사용하는 에이전트 파악
        val agentUsage = conversations
            .groupBy { it.agentId }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(3)

        // 3. 활동 패턴 분석
        val activityPattern = analyzeActivityPattern(conversations)

        // 4. 요약 생성
        return buildString {
            appendLine("## 사용자 대화 요약")
            appendLine()
            appendLine("### 주요 관심사")
            topics.take(5).forEach { (topic, count) ->
                appendLine("- $topic (${count}회)")
            }
            appendLine()
            appendLine("### 자주 사용하는 기능")
            agentUsage.forEach { (agent, count) ->
                appendLine("- $agent: ${count}회 사용")
            }
            appendLine()
            appendLine("### 활동 패턴")
            appendLine(activityPattern)
            appendLine()
            appendLine("### 대화 통계")
            appendLine("- 총 대화: ${conversations.size}개")
            appendLine("- 분석 기간: ${getDateRange(conversations)}")

            // 기존 요약 참조
            existingSummary?.let { prev ->
                appendLine()
                appendLine("### 이전 요약 참조")
                appendLine(prev.take(500))
            }
        }
    }

    private fun extractTopics(conversations: List<ExecutionRecord>): List<Pair<String, Int>> {
        val stopwords = setOf(
            "이", "가", "을", "를", "의", "에", "에서", "로", "으로", "와", "과", "도", "만",
            "그", "저", "이것", "저것", "뭐", "어떻게", "왜", "언제", "어디",
            "the", "a", "an", "is", "are", "was", "were", "be", "have", "has", "do", "does",
            "can", "could", "would", "should", "will", "i", "you", "we", "they", "it",
            "해줘", "해주세요", "알려줘", "설명해줘", "부탁해", "질문"
        )

        val keywords = conversations
            .flatMap { it.prompt.split(Regex("[\\s,.!?;:()\\[\\]{}\"']+")) }
            .map { it.lowercase().trim() }
            .filter { it.length >= 2 && it !in stopwords && !it.all { c -> c.isDigit() } }
            .groupingBy { it }
            .eachCount()
            .entries
            .filter { it.value >= 2 }  // 2번 이상 등장
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key to it.value }

        return keywords
    }

    private fun analyzeActivityPattern(conversations: List<ExecutionRecord>): String {
        if (conversations.isEmpty()) return "데이터 부족"

        val hourCounts = conversations
            .groupBy { it.createdAt.atZone(java.time.ZoneId.systemDefault()).hour }
            .mapValues { it.value.size }

        val peakHour = hourCounts.maxByOrNull { it.value }?.key ?: 0
        val peakPeriod = when (peakHour) {
            in 6..11 -> "오전"
            in 12..17 -> "오후"
            in 18..23 -> "저녁"
            else -> "새벽"
        }

        val dayOfWeekCounts = conversations
            .groupBy { it.createdAt.atZone(java.time.ZoneId.systemDefault()).dayOfWeek }
            .mapValues { it.value.size }

        val peakDay = dayOfWeekCounts.maxByOrNull { it.value }?.key

        return "주로 $peakPeriod 시간대(${peakHour}시경)에 활동, ${peakDay?.let { "$it 요일" } ?: ""}에 가장 활발"
    }

    private fun getDateRange(conversations: List<ExecutionRecord>): String {
        if (conversations.isEmpty()) return "없음"

        val oldest = conversations.minOfOrNull { it.createdAt }
        val newest = conversations.maxOfOrNull { it.createdAt }

        return "${oldest?.toString()?.take(10) ?: "?"} ~ ${newest?.toString()?.take(10) ?: "?"}"
    }
}

/**
 * LLM 기반 요약 생성기 (Claude CLI 활용)
 *
 * 실제 프로덕션에서는 이 구현체 사용 권장
 */
class LlmSummaryGenerator(
    private val claudeExecutor: ((String) -> String)? = null
) : SummaryGenerator {
    override fun generateSummary(
        conversations: List<ExecutionRecord>,
        existingSummary: String?
    ): String {
        val executor = claudeExecutor
            ?: return ExtractivesSummaryGenerator().generateSummary(conversations, existingSummary)

        val conversationsText = conversations.takeLast(20).joinToString("\n---\n") { conv ->
            """
            Q: ${conv.prompt.take(300)}
            A: ${conv.result?.take(500) ?: ""}
            Agent: ${conv.agentId}
            """.trimIndent()
        }

        val prompt = """
            다음은 사용자와의 최근 대화 목록입니다. 이 대화들을 분석하여 사용자의 특성, 관심사, 작업 스타일을 요약해주세요.

            대화 목록:
            $conversationsText

            ${existingSummary?.let { "이전 요약:\n$it" } ?: ""}

            요약 형식:
            1. 주요 관심사/업무 영역
            2. 자주 묻는 질문 유형
            3. 선호하는 응답 스타일 (있다면)
            4. 특이사항 (있다면)

            500자 이내로 간결하게 요약해주세요.
        """.trimIndent()

        return executor(prompt)
    }
}
