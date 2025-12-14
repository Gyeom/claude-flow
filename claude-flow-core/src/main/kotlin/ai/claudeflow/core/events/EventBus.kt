package ai.claudeflow.core.events

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import mu.KotlinLogging
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Event-Driven 아키텍처를 위한 이벤트 버스
 *
 * 기능:
 * - 비동기 이벤트 발행/구독
 * - 이벤트 타입별 필터링
 * - 핸들러 병렬 실행
 * - 재시도 정책
 * - 이벤트 로깅/추적
 *
 * 확장:
 * - Redis Pub/Sub 연동 가능 (분산 환경)
 * - Kafka 연동 가능 (대규모 처리)
 */
class EventBus(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    // 이벤트 채널 (버퍼 크기 제한)
    private val eventChannel = Channel<ClaudeFlowEvent>(Channel.BUFFERED)

    // 이벤트 타입별 핸들러
    private val handlers = ConcurrentHashMap<String, MutableList<EventHandler<*>>>()

    // 이벤트 히스토리 (최근 1000개)
    private val eventHistory = ConcurrentHashMap<String, ClaudeFlowEvent>()
    private val maxHistorySize = 1000

    // 통계
    private var publishedCount = 0L
    private var processedCount = 0L
    private var failedCount = 0L

    init {
        // 이벤트 처리 루프 시작
        startEventProcessor()
    }

    /**
     * 이벤트 발행
     */
    suspend fun publish(event: ClaudeFlowEvent) {
        logger.debug { "Publishing event: ${event.type} (${event.id})" }
        eventChannel.send(event)
        publishedCount++

        // 히스토리에 추가
        if (eventHistory.size >= maxHistorySize) {
            // 가장 오래된 이벤트 제거 (간단한 구현)
            eventHistory.keys.firstOrNull()?.let { eventHistory.remove(it) }
        }
        eventHistory[event.id] = event
    }

    /**
     * 동기 이벤트 발행 (non-suspend 컨텍스트에서 사용)
     */
    fun publishSync(event: ClaudeFlowEvent) {
        scope.launch {
            publish(event)
        }
    }

    /**
     * 이벤트 핸들러 등록
     */
    inline fun <reified T : ClaudeFlowEvent> subscribe(handler: EventHandler<T>) {
        val eventType = T::class.simpleName ?: "Unknown"
        subscribe(eventType, handler as EventHandler<*>)
    }

    fun subscribe(eventType: String, handler: EventHandler<*>) {
        handlers.getOrPut(eventType) { mutableListOf() }.add(handler)
        logger.info { "Handler registered for event type: $eventType" }
    }

    /**
     * 이벤트 핸들러 제거
     */
    fun unsubscribe(eventType: String, handler: EventHandler<*>) {
        handlers[eventType]?.remove(handler)
        logger.info { "Handler unregistered for event type: $eventType" }
    }

    /**
     * 이벤트 처리 루프
     */
    private fun startEventProcessor() {
        scope.launch {
            for (event in eventChannel) {
                processEvent(event)
            }
        }
    }

    /**
     * 이벤트 처리
     */
    private suspend fun processEvent(event: ClaudeFlowEvent) {
        val eventType = event::class.simpleName ?: "Unknown"
        val eventHandlers = handlers[eventType] ?: return

        logger.debug { "Processing event: $eventType (${event.id}) with ${eventHandlers.size} handlers" }

        // 모든 핸들러를 병렬로 실행
        val jobs = eventHandlers.map { handler ->
            scope.async {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (handler as EventHandler<ClaudeFlowEvent>).handle(event)
                    true
                } catch (e: Exception) {
                    logger.error(e) { "Handler failed for event ${event.id}: ${e.message}" }
                    failedCount++
                    false
                }
            }
        }

        // 모든 핸들러 완료 대기
        jobs.awaitAll()
        processedCount++
    }

    /**
     * 통계 조회
     */
    fun getStats(): EventBusStats {
        return EventBusStats(
            publishedCount = publishedCount,
            processedCount = processedCount,
            failedCount = failedCount,
            pendingCount = eventChannel.isEmpty.not().let { if (it) 1 else 0 }.toLong(),
            handlerCount = handlers.values.sumOf { it.size }.toLong(),
            eventTypes = handlers.keys.toList()
        )
    }

    /**
     * 이벤트 히스토리 조회
     */
    fun getHistory(limit: Int = 100): List<ClaudeFlowEvent> {
        return eventHistory.values.sortedByDescending { it.timestamp }.take(limit)
    }

    /**
     * 특정 이벤트 조회
     */
    fun getEvent(eventId: String): ClaudeFlowEvent? {
        return eventHistory[eventId]
    }

    /**
     * 이벤트 버스 종료
     */
    fun shutdown() {
        eventChannel.close()
        scope.cancel()
        logger.info { "EventBus shutdown" }
    }
}

/**
 * 이벤트 핸들러 인터페이스
 */
fun interface EventHandler<T : ClaudeFlowEvent> {
    suspend fun handle(event: T)
}

/**
 * 이벤트 베이스 클래스
 */
sealed class ClaudeFlowEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Instant = Instant.now()
) {
    abstract val type: String
    open val metadata: Map<String, String> = emptyMap()
}

// ==================== 이벤트 타입들 ====================

/**
 * 실행 요청 이벤트
 */
data class ExecutionRequestedEvent(
    val requestId: String,
    val prompt: String,
    val userId: String?,
    val channel: String?,
    val threadTs: String?,
    val projectId: String?,
    val agentId: String?,
    override val metadata: Map<String, String> = emptyMap()
) : ClaudeFlowEvent() {
    override val type = "ExecutionRequested"
}

/**
 * 실행 완료 이벤트
 */
data class ExecutionCompletedEvent(
    val requestId: String,
    val status: String,  // SUCCESS, ERROR, TIMEOUT
    val result: String?,
    val error: String?,
    val durationMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val cost: Double?,
    val sessionId: String?,
    override val metadata: Map<String, String> = emptyMap()
) : ClaudeFlowEvent() {
    override val type = "ExecutionCompleted"
}

/**
 * 라우팅 결정 이벤트
 */
data class RoutingDecisionEvent(
    val requestId: String,
    val prompt: String,
    val agentId: String,
    val method: String,  // keyword, pattern, semantic, llm, fallback
    val confidence: Double,
    val reasoning: String?,
    override val metadata: Map<String, String> = emptyMap()
) : ClaudeFlowEvent() {
    override val type = "RoutingDecision"
}

/**
 * 피드백 수신 이벤트
 */
data class FeedbackReceivedEvent(
    val executionId: String,
    val userId: String,
    val reaction: String,
    val isPositive: Boolean,
    override val metadata: Map<String, String> = emptyMap()
) : ClaudeFlowEvent() {
    override val type = "FeedbackReceived"
}

/**
 * 사용자 컨텍스트 업데이트 이벤트
 */
data class UserContextUpdatedEvent(
    val userId: String,
    val updateType: String,  // summary, rule_added, rule_removed
    val details: String?,
    override val metadata: Map<String, String> = emptyMap()
) : ClaudeFlowEvent() {
    override val type = "UserContextUpdated"
}

/**
 * 에이전트 설정 변경 이벤트
 */
data class AgentConfigChangedEvent(
    val agentId: String,
    val projectId: String?,
    val changeType: String,  // created, updated, deleted, enabled, disabled
    override val metadata: Map<String, String> = emptyMap()
) : ClaudeFlowEvent() {
    override val type = "AgentConfigChanged"
}

/**
 * 시스템 알림 이벤트
 */
data class SystemAlertEvent(
    val level: String,  // info, warning, error, critical
    val message: String,
    val source: String,
    override val metadata: Map<String, String> = emptyMap()
) : ClaudeFlowEvent() {
    override val type = "SystemAlert"
}

/**
 * 이벤트 버스 통계
 */
data class EventBusStats(
    val publishedCount: Long,
    val processedCount: Long,
    val failedCount: Long,
    val pendingCount: Long,
    val handlerCount: Long,
    val eventTypes: List<String>
) {
    val successRate: Double get() {
        val total = processedCount + failedCount
        return if (total > 0) processedCount.toDouble() / total else 1.0
    }
}
