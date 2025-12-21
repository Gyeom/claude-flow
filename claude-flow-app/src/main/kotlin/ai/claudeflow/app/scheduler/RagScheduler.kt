package ai.claudeflow.app.scheduler

import ai.claudeflow.core.rag.AutoSummaryService
import ai.claudeflow.core.rag.ConversationVectorService
import ai.claudeflow.core.rag.RagConfig
import ai.claudeflow.executor.ClaudeExecutor
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * RAG 시스템 스케줄러
 *
 * 주기적인 RAG 관련 작업 수행:
 * - 자동 요약 생성
 * - 세션 정리
 * - 캐시 관리
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = ["rag.enabled"], havingValue = "true", matchIfMissing = true)
class RagScheduler(
    private val autoSummaryService: AutoSummaryService? = null,
    private val conversationVectorService: ConversationVectorService? = null,
    private val claudeExecutor: ClaudeExecutor? = null,
    private val ragConfig: RagConfig = RagConfig.fromEnvironment()
) {

    /**
     * 자동 요약 생성 (매시간)
     *
     * 조건을 만족하는 사용자의 대화 요약을 자동 생성
     * - 최소 5개 대화
     * - 최소 5000자
     * - 마지막 요약 후 6시간 경과
     */
    @Scheduled(cron = "0 0 * * * *")  // 매시간 정각
    fun autoGenerateSummaries() {
        if (!ragConfig.autoSummaryEnabled || autoSummaryService == null) {
            return
        }

        logger.info { "Starting scheduled auto-summary generation" }
        try {
            val result = autoSummaryService.batchGenerateSummaries(maxUsers = 50)
            logger.info {
                "Auto-summary completed: ${result.successCount} generated, ${result.failCount} failed, total: ${result.totalProcessed}"
            }
        } catch (e: Exception) {
            logger.error(e) { "Auto-summary generation failed" }
        }
    }

    /**
     * Claude 세션 정리 (10분마다)
     *
     * 만료된 세션 캐시 정리
     */
    @Scheduled(fixedRate = 600_000)  // 10분
    fun cleanupExpiredSessions() {
        claudeExecutor?.cleanupExpiredSessions()
    }

    /**
     * RAG 상태 로깅 (6시간마다)
     *
     * 벡터 DB 통계 및 상태 로깅
     */
    @Scheduled(cron = "0 0 */6 * * *")  // 6시간마다
    fun logRagStats() {
        if (conversationVectorService == null) return

        try {
            val stats = conversationVectorService.getStats()
            logger.info {
                "RAG Stats - Vectors: ${stats.totalVectors}, Collection: ${stats.collectionName}, " +
                "Available: ${conversationVectorService.isAvailable()}"
            }
        } catch (e: Exception) {
            logger.warn { "Failed to get RAG stats: ${e.message}" }
        }
    }

    /**
     * 벡터 DB 헬스체크 (5분마다)
     */
    @Scheduled(fixedRate = 300_000)  // 5분
    fun checkRagHealth() {
        if (conversationVectorService == null) return

        val available = conversationVectorService.isAvailable()
        if (!available) {
            logger.warn { "RAG services unavailable - Qdrant or Ollama may be down" }
        }
    }
}
