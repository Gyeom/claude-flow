package ai.claudeflow.core.rag

import ai.claudeflow.core.storage.AdminFeedback
import ai.claudeflow.core.storage.repository.AdminFeedbackRepository
import ai.claudeflow.core.storage.repository.ExecutionRepository
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Few-Shot 예제 주입 서비스
 *
 * 관리자가 마킹한 우수 사례를 프롬프트에 동적으로 주입
 */
class FewShotInjectionService(
    private val adminFeedbackRepository: AdminFeedbackRepository,
    private val executionRepository: ExecutionRepository
) {

    /**
     * 에이전트별 Few-Shot 예제 생성
     *
     * @param agentId 에이전트 ID
     * @param maxExamples 최대 예제 수
     * @return Few-shot 프롬프트 문자열
     */
    fun buildFewShotPrompt(
        agentId: String,
        maxExamples: Int = 3
    ): String? {
        val exemplary = adminFeedbackRepository.findExemplaryByAgent(agentId, maxExamples)

        if (exemplary.isEmpty()) {
            logger.debug { "No exemplary feedbacks found for agent: $agentId" }
            return null
        }

        val examples = exemplary.mapNotNull { feedback ->
            val execution = executionRepository.findById(feedback.executionId)
            if (execution != null) {
                FewShotExample(
                    query = execution.prompt,
                    response = execution.result ?: "",
                    comment = feedback.comment
                )
            } else null
        }

        if (examples.isEmpty()) {
            return null
        }

        logger.info { "Built ${examples.size} few-shot examples for agent: $agentId" }

        return buildString {
            appendLine("## 과거 좋은 평가를 받은 응답 예시")
            appendLine()
            examples.forEachIndexed { index, example ->
                appendLine("### 예시 ${index + 1}")
                appendLine("**질문**: ${example.query.take(200)}${if (example.query.length > 200) "..." else ""}")
                appendLine()
                appendLine("**좋은 응답**:")
                appendLine("```")
                val truncatedResponse = example.response.take(500)
                appendLine(truncatedResponse + if (example.response.length > 500) "..." else "")
                appendLine("```")
                if (example.comment != null) {
                    appendLine("*평가: ${example.comment}*")
                }
                appendLine()
            }
            appendLine("---")
            appendLine("위 예시를 참고하여 유사한 품질의 응답을 제공하세요.")
        }
    }

    /**
     * Gold Response 기반 학습 데이터 조회
     *
     * @param limit 최대 개수
     * @return (원본 프롬프트, 원본 응답, 이상적 응답) 튜플 목록
     */
    fun getTrainingPairs(limit: Int = 50): List<TrainingPair> {
        val feedbacks = adminFeedbackRepository.findWithGoldResponse(limit)

        return feedbacks.mapNotNull { feedback ->
            val execution = executionRepository.findById(feedback.executionId)
            val goldResponse = feedback.goldResponse

            if (execution != null && goldResponse != null) {
                TrainingPair(
                    prompt = execution.prompt,
                    originalResponse = execution.result ?: "",
                    goldResponse = goldResponse,
                    agentId = execution.agentId,
                    issues = feedback.issues.map { it.name }
                )
            } else null
        }
    }

    /**
     * Anti-Pattern 프롬프트 생성
     *
     * 부정 피드백에서 자주 발생하는 문제 유형을 경고로 추가
     */
    fun buildAntiPatternPrompt(agentId: String): String? {
        // 에이전트의 전체 평균 점수와 이슈 분포 조회
        val stats = adminFeedbackRepository.getStats()

        if (stats.issueDistribution.isEmpty()) {
            return null
        }

        // 가장 빈번한 이슈 3개 추출
        val topIssues = stats.issueDistribution.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }

        if (topIssues.isEmpty()) {
            return null
        }

        logger.info { "Built anti-pattern prompt for agent: $agentId with ${topIssues.size} issues" }

        return buildString {
            appendLine("## 피해야 할 응답 패턴")
            appendLine()
            topIssues.forEach { issue ->
                appendLine("- **${issue.displayName}**: ${issue.description}")
            }
            appendLine()
            appendLine("위 문제들을 피하고, 구체적이고 실행 가능한 응답을 제공하세요.")
        }
    }

    /**
     * 전체 컨텍스트 증강 (Few-shot + Anti-pattern)
     */
    fun buildFullAugmentation(agentId: String): String? {
        val fewShot = buildFewShotPrompt(agentId)
        val antiPattern = buildAntiPatternPrompt(agentId)

        if (fewShot == null && antiPattern == null) {
            return null
        }

        return buildString {
            if (fewShot != null) {
                appendLine(fewShot)
                appendLine()
            }
            if (antiPattern != null) {
                appendLine(antiPattern)
            }
        }
    }
}

/**
 * Few-shot 예제
 */
data class FewShotExample(
    val query: String,
    val response: String,
    val comment: String?
)

/**
 * 학습 데이터 쌍
 */
data class TrainingPair(
    val prompt: String,
    val originalResponse: String,
    val goldResponse: String,
    val agentId: String?,
    val issues: List<String>
)
