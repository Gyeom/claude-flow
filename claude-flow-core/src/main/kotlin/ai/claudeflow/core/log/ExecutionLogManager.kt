package ai.claudeflow.core.log

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

/**
 * 실행 로그 이벤트
 */
data class LogEvent(
    val executionId: String,
    val timestamp: Instant = Instant.now(),
    val level: LogLevel,
    val message: String,
    val details: Map<String, Any?> = emptyMap()
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, TOOL_START, TOOL_END, AGENT_START, AGENT_END
}

/**
 * 실행 로그 관리자
 *
 * - 실행별 로그 저장
 * - 실시간 스트리밍 (SharedFlow)
 * - 최근 로그 조회
 */
class ExecutionLogManager {
    // 실행별 로그 저장 (최근 100개 실행만 유지)
    private val executionLogs = ConcurrentHashMap<String, CopyOnWriteArrayList<LogEvent>>()
    private val executionOrder = CopyOnWriteArrayList<String>()
    private val maxExecutions = 100

    // 실시간 스트리밍용 Flow
    private val _logFlow = MutableSharedFlow<LogEvent>(
        replay = 50,  // 최근 50개 이벤트 캐시
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logFlow: SharedFlow<LogEvent> = _logFlow.asSharedFlow()

    /**
     * 로그 이벤트 추가
     */
    fun log(executionId: String, level: LogLevel, message: String, details: Map<String, Any?> = emptyMap()) {
        val event = LogEvent(
            executionId = executionId,
            level = level,
            message = message,
            details = details
        )

        // 실행별 로그 저장
        executionLogs.computeIfAbsent(executionId) {
            trackExecution(executionId)
            CopyOnWriteArrayList()
        }.add(event)

        // 실시간 스트리밍
        _logFlow.tryEmit(event)

        // 콘솔 로그도 출력
        when (level) {
            LogLevel.ERROR -> logger.error { "[$executionId] $message" }
            LogLevel.WARN -> logger.warn { "[$executionId] $message" }
            else -> logger.info { "[$executionId] $message" }
        }
    }

    /**
     * 편의 메서드들
     */
    fun info(executionId: String, message: String, details: Map<String, Any?> = emptyMap()) =
        log(executionId, LogLevel.INFO, message, details)

    fun error(executionId: String, message: String, details: Map<String, Any?> = emptyMap()) =
        log(executionId, LogLevel.ERROR, message, details)

    fun toolStart(executionId: String, toolName: String, args: Map<String, Any?> = emptyMap()) =
        log(executionId, LogLevel.TOOL_START, "Tool started: $toolName", args + ("tool" to toolName))

    fun toolEnd(executionId: String, toolName: String, success: Boolean, durationMs: Long) =
        log(executionId, LogLevel.TOOL_END, "Tool completed: $toolName (${if (success) "success" else "failed"}, ${durationMs}ms)",
            mapOf("tool" to toolName, "success" to success, "durationMs" to durationMs))

    fun agentStart(executionId: String, agentId: String, prompt: String) =
        log(executionId, LogLevel.AGENT_START, "Agent started: $agentId",
            mapOf("agentId" to agentId, "prompt" to prompt.take(100)))

    fun agentEnd(executionId: String, agentId: String, status: String, durationMs: Long) =
        log(executionId, LogLevel.AGENT_END, "Agent completed: $agentId ($status, ${durationMs}ms)",
            mapOf("agentId" to agentId, "status" to status, "durationMs" to durationMs))

    /**
     * 특정 실행의 로그 조회
     */
    fun getExecutionLogs(executionId: String): List<LogEvent> =
        executionLogs[executionId]?.toList() ?: emptyList()

    /**
     * 최근 로그 조회 (모든 실행)
     */
    fun getRecentLogs(limit: Int = 100): List<LogEvent> {
        return executionLogs.values
            .flatten()
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    /**
     * 활성 실행 ID 목록
     */
    fun getActiveExecutions(): List<String> = executionOrder.toList().takeLast(20)

    /**
     * 실행 추적 및 오래된 로그 정리
     */
    private fun trackExecution(executionId: String) {
        executionOrder.add(executionId)

        // 오래된 실행 로그 정리
        while (executionOrder.size > maxExecutions) {
            val oldestId = executionOrder.removeAt(0)
            executionLogs.remove(oldestId)
        }
    }

    /**
     * 로그 클리어
     */
    fun clear() {
        executionLogs.clear()
        executionOrder.clear()
    }

    companion object {
        // 싱글톤 인스턴스
        val instance = ExecutionLogManager()
    }
}
