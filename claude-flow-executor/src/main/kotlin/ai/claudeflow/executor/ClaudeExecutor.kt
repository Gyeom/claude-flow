package ai.claudeflow.executor

import ai.claudeflow.core.log.ExecutionLogManager
import ai.claudeflow.core.model.ClaudeConfig
import ai.claudeflow.core.model.OutputFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import mu.KotlinLogging
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Claude CLI 실행기
 *
 * Claude Code CLI를 래핑하여 프로그래매틱하게 실행
 *
 * 주요 기능:
 * - Session 지속성: --resume 플래그로 대화 이어가기 (토큰 30-40% 절감)
 * - 비동기 실행: Coroutine 기반 non-blocking 실행
 * - Session 캐시: 사용자/스레드별 세션 ID 관리
 */
class ClaudeExecutor(
    private val defaultConfig: ClaudeConfig = ClaudeConfig()
) {
    private val objectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    // 로그 매니저
    private val logManager = ExecutionLogManager.instance

    // Session 캐시: key = userId:threadTs, value = sessionId
    private val sessionCache = ConcurrentHashMap<String, SessionInfo>()

    // Session TTL: 30분
    private val sessionTtlMs = 30 * 60 * 1000L

    data class SessionInfo(
        val sessionId: String,
        val createdAt: Long = System.currentTimeMillis(),
        val lastUsedAt: Long = System.currentTimeMillis()
    ) {
        fun isExpired(ttlMs: Long): Boolean =
            System.currentTimeMillis() - lastUsedAt > ttlMs
    }

    /**
     * Claude CLI 실행 (블로킹)
     */
    fun execute(request: ExecutionRequest): ExecutionResult {
        return runBlocking {
            executeAsync(request)
        }
    }

    /**
     * Claude CLI 비동기 실행 (Coroutine 기반)
     *
     * 장점:
     * - Non-blocking: 스레드 풀 효율적 사용
     * - 타임아웃 지원: withTimeoutOrNull로 안전한 취소
     * - 동시성: 여러 요청 병렬 처리 가능
     */
    suspend fun executeAsync(request: ExecutionRequest): ExecutionResult = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        logManager.agentStart(requestId, request.agentId ?: "unknown", request.prompt)
        logManager.info(requestId, "Starting Claude execution", mapOf(
            "prompt" to request.prompt.take(100),
            "workingDirectory" to request.workingDirectory,
            "model" to request.model,
            "maxTurns" to request.maxTurns
        ))

        // 작업 디렉토리 검증
        val workingDir = request.workingDirectory?.let { File(it) }
        if (workingDir != null && !workingDir.exists()) {
            logManager.error(requestId, "Working directory does not exist: ${request.workingDirectory}")
            return@withContext ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.ERROR,
                error = "Working directory does not exist: ${request.workingDirectory}",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // Session 조회 또는 생성
        val sessionKey = buildSessionKey(request)
        val existingSession = sessionKey?.let { getValidSession(it) }

        // CLI 인자 구성
        val args = buildArgs(request, existingSession?.sessionId)
        logManager.info(requestId, "CLI args prepared", mapOf("args" to args.take(10).joinToString(" ")))
        if (existingSession != null) {
            logManager.info(requestId, "Resuming session: ${existingSession.sessionId}")
        }

        val timeoutSeconds = (request.config?.timeoutSeconds ?: defaultConfig.timeoutSeconds).toLong()
        logManager.info(requestId, "Timeout set to ${timeoutSeconds}s")

        try {
            val result = withTimeoutOrNull(timeoutSeconds * 1000L) {
                runProcessAsync(args, workingDir, requestId, timeoutSeconds)
            } ?: run {
                logManager.error(requestId, "Execution timed out after ${timeoutSeconds}s")
                ExecutionResult(
                    requestId = requestId,
                    status = ExecutionStatus.TIMEOUT,
                    error = "Execution timed out after ${timeoutSeconds} seconds"
                )
            }

            // Session ID 저장 (성공 시)
            if (result.status == ExecutionStatus.SUCCESS && result.sessionId != null && sessionKey != null) {
                sessionCache[sessionKey] = SessionInfo(result.sessionId)
                logManager.info(requestId, "Session cached: ${result.sessionId}")
            }

            val finalResult = result.copy(durationMs = System.currentTimeMillis() - startTime)
            logManager.agentEnd(requestId, request.agentId ?: "unknown", finalResult.status.name, finalResult.durationMs)
            finalResult
        } catch (e: Exception) {
            logManager.error(requestId, "Execution failed: ${e.message}")
            val errorResult = ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.ERROR,
                error = "Execution failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime
            )
            logManager.agentEnd(requestId, request.agentId ?: "unknown", "ERROR", errorResult.durationMs)
            errorResult
        }
    }

    /**
     * Session 키 생성: userId:threadTs 조합
     */
    private fun buildSessionKey(request: ExecutionRequest): String? {
        val userId = request.userId ?: return null
        val threadTs = request.threadTs ?: return null
        return "$userId:$threadTs"
    }

    /**
     * 유효한 Session 조회 (만료되지 않은 경우)
     */
    private fun getValidSession(key: String): SessionInfo? {
        val session = sessionCache[key] ?: return null
        if (session.isExpired(sessionTtlMs)) {
            sessionCache.remove(key)
            return null
        }
        // 마지막 사용 시간 갱신
        sessionCache[key] = session.copy(lastUsedAt = System.currentTimeMillis())
        return session
    }

    /**
     * Session 캐시 정리 (만료된 세션 제거)
     */
    fun cleanupExpiredSessions() {
        val expiredKeys = sessionCache.entries
            .filter { it.value.isExpired(sessionTtlMs) }
            .map { it.key }
        expiredKeys.forEach { sessionCache.remove(it) }
        if (expiredKeys.isNotEmpty()) {
            logger.info { "Cleaned up ${expiredKeys.size} expired sessions" }
        }
    }

    /**
     * Claude CLI 스트리밍 실행 (Flow 기반)
     *
     * 실시간으로 StreamingEvent를 emit하여 SSE 스트리밍 지원
     * - text: 어시스턴트 텍스트 청크
     * - tool_start: 도구 호출 시작
     * - tool_end: 도구 호출 완료
     * - done: 실행 완료
     * - error: 에러 발생
     */
    fun executeStreaming(request: ExecutionRequest): Flow<StreamingEvent> = channelFlow {
        val requestId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        logManager.agentStart(requestId, request.agentId ?: "unknown", request.prompt)

        // 작업 디렉토리 검증
        val workingDir = request.workingDirectory?.let { File(it) }
        if (workingDir != null && !workingDir.exists()) {
            send(StreamingEvent.Error(
                requestId = requestId,
                message = "Working directory does not exist: ${request.workingDirectory}"
            ))
            return@channelFlow
        }

        // Session 조회 또는 생성
        val sessionKey = buildSessionKey(request)
        val existingSession = sessionKey?.let { getValidSession(it) }

        // CLI 인자 구성 (항상 stream-json 사용)
        val args = buildArgsForStreaming(request, existingSession?.sessionId)

        val timeoutSeconds = (request.config?.timeoutSeconds ?: defaultConfig.timeoutSeconds).toLong()

        try {
            val claudePath = findClaudePath()
            logger.info { "[$requestId] Starting streaming execution: $claudePath" }

            val processBuilder = ProcessBuilder(listOf(claudePath) + args)
                .apply {
                    workingDir?.let { directory(it) }
                    redirectErrorStream(false)
                    environment().putAll(System.getenv())
                }

            val process = processBuilder.start()
            process.outputStream.close()

            var sessionId: String? = null
            var usage: TokenUsage? = null
            var cost: Double? = null
            val assistantMessages = StringBuilder()

            // stderr 별도 처리
            val stderrJob = CoroutineScope(Dispatchers.IO).async {
                process.errorStream.bufferedReader().use { it.readText() }
            }

            // stdout 스트리밍 처리
            withContext(Dispatchers.IO) {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue

                        try {
                            val event = objectMapper.readValue<StreamEvent>(line)

                            when (event.type) {
                                "assistant" -> {
                                    event.message?.content?.forEach { content ->
                                        if (content.type == "text" && !content.text.isNullOrEmpty()) {
                                            assistantMessages.append(content.text)
                                            send(StreamingEvent.Text(
                                                requestId = requestId,
                                                content = content.text
                                            ))
                                        }
                                    }
                                }
                                "tool_use" -> {
                                    val toolId = event.id ?: UUID.randomUUID().toString()
                                    val toolName = event.name ?: "unknown"
                                    val toolInput = event.input?.let {
                                        when (it) {
                                            is Map<*, *> -> it.mapKeys { k -> k.key.toString() }
                                                .mapValues { v -> v.value }
                                            else -> mapOf("input" to it.toString())
                                        }
                                    } ?: emptyMap()

                                    send(StreamingEvent.ToolStart(
                                        requestId = requestId,
                                        toolId = toolId,
                                        toolName = toolName,
                                        input = toolInput
                                    ))
                                    logManager.toolStart(requestId, toolName, toolInput.mapValues { it.value })
                                }
                                "tool_result" -> {
                                    val toolId = event.id ?: ""
                                    val toolName = event.name ?: "unknown"
                                    val success = event.isError != true

                                    send(StreamingEvent.ToolEnd(
                                        requestId = requestId,
                                        toolId = toolId,
                                        toolName = toolName,
                                        result = event.content?.toString()?.take(500),
                                        success = success
                                    ))
                                    logManager.toolEnd(requestId, toolName, success, 0L)
                                }
                                "result" -> {
                                    sessionId = event.sessionId
                                    usage = event.usage
                                    cost = event.cost
                                }
                                "system" -> {
                                    sessionId = event.sessionId ?: sessionId
                                }
                            }
                        } catch (e: Exception) {
                            logger.debug { "[$requestId] Failed to parse stream line: ${line.take(100)}" }
                        }
                    }
                }
            }

            // 프로세스 완료 대기
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val stderr = stderrJob.await()

            if (!completed) {
                process.destroyForcibly()
                send(StreamingEvent.Error(
                    requestId = requestId,
                    message = "Execution timed out after $timeoutSeconds seconds"
                ))
                return@channelFlow
            }

            val exitCode = process.exitValue()
            val durationMs = System.currentTimeMillis() - startTime

            if (exitCode != 0) {
                send(StreamingEvent.Error(
                    requestId = requestId,
                    message = stderr.ifEmpty { "Claude CLI exited with code $exitCode" }
                ))
                return@channelFlow
            }

            // Session 캐시 저장
            if (sessionId != null && sessionKey != null) {
                sessionCache[sessionKey] = SessionInfo(sessionId)
            }

            // 완료 이벤트
            send(StreamingEvent.Done(
                requestId = requestId,
                sessionId = sessionId,
                durationMs = durationMs,
                usage = usage,
                cost = cost,
                result = assistantMessages.toString().ifEmpty { null }
            ))

            logManager.agentEnd(requestId, request.agentId ?: "unknown", "SUCCESS", durationMs)

        } catch (e: Exception) {
            logger.error(e) { "[$requestId] Streaming execution failed" }
            send(StreamingEvent.Error(
                requestId = requestId,
                message = "Execution failed: ${e.message}"
            ))
            logManager.agentEnd(requestId, request.agentId ?: "unknown", "ERROR", System.currentTimeMillis() - startTime)
        }
    }

    /**
     * 스트리밍용 CLI 인자 구성 (항상 stream-json)
     */
    private fun buildArgsForStreaming(request: ExecutionRequest, resumeSessionId: String? = null): List<String> {
        val config = request.config ?: defaultConfig
        val args = mutableListOf<String>()

        // Session 재개
        if (resumeSessionId != null) {
            args.addAll(listOf("--resume", resumeSessionId))
        } else {
            args.add("-p")
        }

        // 항상 stream-json
        args.addAll(listOf("--output-format", "stream-json"))
        args.add("--verbose")

        // 모델
        if (resumeSessionId == null) {
            args.addAll(listOf("--model", request.model ?: config.model))
        }

        // 권한 모드
        args.add("--dangerously-skip-permissions")

        // max-turns
        request.maxTurns?.let {
            args.addAll(listOf("--max-turns", it.toString()))
        }

        // 도구 설정 (새 세션일 때만)
        if (resumeSessionId == null) {
            val allowedTools = request.allowedTools ?: config.allowedTools
            if (allowedTools.isNotEmpty()) {
                args.addAll(listOf("--allowedTools", allowedTools.joinToString(" ")))
            }

            val deniedTools = request.deniedTools ?: config.deniedTools
            if (deniedTools.isNotEmpty()) {
                args.addAll(listOf("--disallowedTools", deniedTools.joinToString(" ")))
            }

            request.systemPrompt?.let {
                args.addAll(listOf("--system-prompt", it))
            }
        }

        args.add(request.prompt)
        return args
    }

    private fun buildArgs(request: ExecutionRequest, resumeSessionId: String? = null): List<String> {
        val config = request.config ?: defaultConfig
        val args = mutableListOf<String>()

        // Session 재개 (--resume 플래그)
        if (resumeSessionId != null) {
            args.addAll(listOf("--resume", resumeSessionId))
        } else {
            // 새 세션: 프롬프트 모드
            args.add("-p")
        }

        // 출력 형식 - stream-json으로 실시간 도구 로깅 지원
        val outputFormat = when (config.outputFormat) {
            OutputFormat.STREAM_JSON -> "stream-json"
            OutputFormat.STREAM -> "stream-json"  // STREAM도 stream-json으로 매핑
            else -> config.outputFormat.name.lowercase()
        }
        args.addAll(listOf("--output-format", outputFormat))

        // stream-json 사용 시 --verbose 필수 (--print 모드에서)
        if (outputFormat == "stream-json") {
            args.add("--verbose")
        }

        // 모델 (새 세션일 때만)
        if (resumeSessionId == null) {
            args.addAll(listOf("--model", request.model ?: config.model))
        }

        // 권한 모드 - 자동화 환경에서는 skip permissions 사용
        args.add("--dangerously-skip-permissions")

        // max-turns
        request.maxTurns?.let {
            args.addAll(listOf("--max-turns", it.toString()))
        }

        // 허용 도구 (새 세션일 때만)
        if (resumeSessionId == null) {
            val allowedTools = request.allowedTools ?: config.allowedTools
            if (allowedTools.isNotEmpty()) {
                args.addAll(listOf("--allowedTools", allowedTools.joinToString(" ")))
            }

            // 금지 도구
            val deniedTools = request.deniedTools ?: config.deniedTools
            if (deniedTools.isNotEmpty()) {
                args.addAll(listOf("--disallowedTools", deniedTools.joinToString(" ")))
            }

            // 시스템 프롬프트
            request.systemPrompt?.let {
                args.addAll(listOf("--system-prompt", it))
            }
        }

        // 프롬프트 (마지막에 추가)
        args.add(request.prompt)

        return args
    }

    private fun findClaudePath(): String {
        // 일반적인 Claude CLI 설치 경로들
        val possiblePaths = listOf(
            System.getenv("HOME") + "/.local/bin/claude",
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
            "claude"  // fallback to PATH
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                logger.info { "Found Claude CLI at: $path" }
                return path
            }
        }

        // PATH에서 찾기 시도
        return "claude"
    }

    /**
     * 비동기 프로세스 실행 (suspend 함수)
     *
     * stream-json 형식일 경우 실시간으로 도구 호출을 로깅
     */
    private suspend fun runProcessAsync(
        args: List<String>,
        workingDir: File?,
        requestId: String,
        timeoutSeconds: Long
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val claudePath = findClaudePath()
        val isStreamJson = args.contains("stream-json")
        logger.info { "[$requestId] Running (async): $claudePath ${args.take(5).joinToString(" ")}... (streaming: $isStreamJson)" }

        val processBuilder = ProcessBuilder(listOf(claudePath) + args)
            .apply {
                workingDir?.let { directory(it) }
                redirectErrorStream(false)
                // 환경변수 상속
                environment().putAll(System.getenv())
            }

        val process = processBuilder.start()

        // stdin 닫기 (non-interactive 모드)
        process.outputStream.close()

        // 스트리밍 모드: 실시간으로 읽으면서 로깅
        val stdoutDeferred = if (isStreamJson) {
            async { readStreamingOutput(process.inputStream, requestId) }
        } else {
            async { process.inputStream.bufferedReader().use { it.readText() } to null }
        }

        val stderrDeferred = async {
            process.errorStream.bufferedReader().use { it.readText() }
        }

        // 프로세스 완료 대기 (타임아웃 적용)
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (!completed) {
            process.destroyForcibly()
            stdoutDeferred.cancel()
            stderrDeferred.cancel()
            logger.warn { "[$requestId] Process timed out after ${timeoutSeconds}s" }
            return@withContext ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.TIMEOUT,
                error = "Execution timed out after ${timeoutSeconds} seconds"
            )
        }

        val (stdout, streamResult) = stdoutDeferred.await()
        val stderr = stderrDeferred.await()
        val exitCode = process.exitValue()

        logger.info { "[$requestId] Process completed with exit code: $exitCode" }
        logger.debug { "[$requestId] stdout length: ${stdout.length}, stderr length: ${stderr.length}" }

        if (exitCode == 0) {
            // 스트리밍 모드면 파싱된 결과 사용
            if (isStreamJson && streamResult != null) {
                streamResult.copy(requestId = requestId)
            } else {
                parseSuccessResponse(stdout, requestId)
            }
        } else {
            logger.error { "[$requestId] Claude CLI error: $stderr" }
            ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.ERROR,
                error = stderr.ifEmpty { "Claude CLI exited with code $exitCode" },
                rawOutput = stdout
            )
        }
    }

    /**
     * 스트리밍 출력 실시간 파싱 및 로깅
     *
     * stream-json 형식의 각 줄을 파싱하여:
     * - assistant 메시지: 최종 결과로 누적
     * - tool_use: 도구 호출 로그
     * - tool_result: 도구 결과 로그
     * - result: 최종 결과
     */
    private fun readStreamingOutput(
        inputStream: java.io.InputStream,
        requestId: String
    ): Pair<String, ExecutionResult?> {
        val allOutput = StringBuilder()
        var finalResult: String? = null
        var sessionId: String? = null
        var usage: TokenUsage? = null
        var cost: Double? = null
        val assistantMessages = StringBuilder()

        inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                allOutput.appendLine(line)

                if (line.isBlank()) continue

                try {
                    val event = objectMapper.readValue<StreamEvent>(line)

                    when (event.type) {
                        "assistant" -> {
                            // 어시스턴트 텍스트 메시지
                            event.message?.content?.forEach { content ->
                                if (content.type == "text") {
                                    assistantMessages.append(content.text ?: "")
                                    logManager.info(requestId, content.text ?: "", mapOf("type" to "assistant"))
                                }
                            }
                        }
                        "tool_use" -> {
                            // 도구 호출 시작
                            val toolName = event.name ?: "unknown"
                            val toolInput = event.input?.toString()?.take(200) ?: ""
                            logManager.toolStart(requestId, toolName, mapOf("input" to toolInput))
                        }
                        "tool_result" -> {
                            // 도구 결과
                            val toolName = event.name ?: "unknown"
                            val success = event.isError != true
                            logManager.toolEnd(requestId, toolName, success, 0L)
                        }
                        "result" -> {
                            // 최종 결과
                            finalResult = event.result
                            sessionId = event.sessionId
                            usage = event.usage
                            cost = event.cost

                            // subtype 체크 (error_max_turns 등)
                            if (event.subtype?.startsWith("error_") == true) {
                                logManager.error(requestId, "Execution ended with: ${event.subtype}")
                            }
                        }
                        "system" -> {
                            // 시스템 메시지 (init 등)
                            logManager.info(requestId, "System: ${event.subtype ?: "unknown"}",
                                mapOf("sessionId" to (event.sessionId ?: "")))
                            sessionId = event.sessionId
                        }
                    }
                } catch (e: Exception) {
                    // 파싱 실패 시 원문 로깅
                    logger.debug { "[$requestId] Failed to parse stream line: ${line.take(100)}" }
                }
            }
        }

        // 최종 결과 구성
        val resultText = finalResult ?: assistantMessages.toString().ifEmpty { null }
        val executionResult = ExecutionResult(
            requestId = requestId,
            status = ExecutionStatus.SUCCESS,
            result = resultText,
            rawOutput = allOutput.toString(),
            usage = usage,
            sessionId = sessionId,
            cost = cost
        )

        return allOutput.toString() to executionResult
    }

    /**
     * 동기 프로세스 실행 (기존 호환성 유지)
     */
    private fun runProcess(
        args: List<String>,
        workingDir: File?,
        requestId: String,
        timeoutSeconds: Long
    ): ExecutionResult {
        return runBlocking {
            runProcessAsync(args, workingDir, requestId, timeoutSeconds)
        }
    }

    private fun parseSuccessResponse(output: String, requestId: String): ExecutionResult {
        return try {
            // JSON 출력인 경우 파싱 시도
            logger.debug { "[$requestId] Raw output: ${output.take(500)}..." }
            val cliOutput = objectMapper.readValue<ClaudeCliOutput>(output)
            logger.info { "[$requestId] Parsed - type: ${cliOutput.type}, subtype: ${cliOutput.subtype}, result length: ${cliOutput.result?.length ?: 0}" }

            // error_max_turns 등의 에러 subtype 처리
            if (cliOutput.subtype?.startsWith("error_") == true) {
                val errorMessage = when (cliOutput.subtype) {
                    "error_max_turns" -> "작업이 max-turns 제한(${cliOutput.usage?.let { "(input: ${it.inputTokens}, output: ${it.outputTokens} tokens)" } ?: ""})에 도달했습니다. 더 간단한 작업으로 나누어 시도해주세요."
                    else -> "Claude CLI error: ${cliOutput.subtype}"
                }
                logger.warn { "[$requestId] Claude CLI returned error subtype: ${cliOutput.subtype}" }
                return ExecutionResult(
                    requestId = requestId,
                    status = ExecutionStatus.ERROR,
                    error = errorMessage,
                    rawOutput = output,
                    usage = cliOutput.usage
                )
            }

            ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.SUCCESS,
                result = cliOutput.result,
                rawOutput = output,
                usage = cliOutput.usage,
                sessionId = cliOutput.sessionId,
                cost = cliOutput.cost
            )
        } catch (e: Exception) {
            // JSON 파싱 실패 시 원문 반환
            logger.warn { "[$requestId] Output is not JSON, returning raw text: ${e.message}" }
            logger.debug { "[$requestId] Raw output for fallback: ${output.take(200)}..." }
            ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.SUCCESS,
                result = output,
                rawOutput = output
            )
        }
    }
}

/**
 * Claude 실행 요청
 */
data class ExecutionRequest(
    val prompt: String,
    val systemPrompt: String? = null,
    val workingDirectory: String? = null,
    val model: String? = null,
    val maxTurns: Int? = null,
    val allowedTools: List<String>? = null,
    val deniedTools: List<String>? = null,
    val config: ClaudeConfig? = null,
    // Session 지속성을 위한 필드
    val userId: String? = null,          // 사용자 ID (session 키)
    val threadTs: String? = null,        // Slack 스레드 타임스탬프 (session 키)
    val forceNewSession: Boolean = false, // true면 기존 session 무시
    // 로깅용
    val agentId: String? = null          // 실행 에이전트 ID
)

/**
 * 실행 결과
 */
data class ExecutionResult(
    val requestId: String,
    val status: ExecutionStatus,
    val result: String? = null,
    val error: String? = null,
    val rawOutput: String? = null,
    val usage: TokenUsage? = null,
    val durationMs: Long = 0,
    val sessionId: String? = null,  // Claude CLI session ID (재사용 가능)
    val cost: Double? = null        // 실행 비용 (USD)
)

enum class ExecutionStatus {
    SUCCESS,
    ERROR,
    TIMEOUT
}

/**
 * Claude CLI JSON 출력 구조
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ClaudeCliOutput(
    val type: String? = null,
    val subtype: String? = null,
    val result: String? = null,
    val usage: TokenUsage? = null,
    val cost: Double? = null,
    val sessionId: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenUsage(
    @com.fasterxml.jackson.annotation.JsonProperty("input_tokens")
    @com.fasterxml.jackson.annotation.JsonAlias("inputTokens")
    val inputTokens: Int = 0,

    @com.fasterxml.jackson.annotation.JsonProperty("output_tokens")
    @com.fasterxml.jackson.annotation.JsonAlias("outputTokens")
    val outputTokens: Int = 0,

    @com.fasterxml.jackson.annotation.JsonProperty("cache_read_input_tokens")
    @com.fasterxml.jackson.annotation.JsonAlias("cacheReadInputTokens")
    val cacheReadTokens: Int = 0,

    @com.fasterxml.jackson.annotation.JsonProperty("cache_creation_input_tokens")
    @com.fasterxml.jackson.annotation.JsonAlias("cacheCreationInputTokens")
    val cacheWriteTokens: Int = 0
)

/**
 * Claude CLI stream-json 출력 이벤트
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamEvent(
    val type: String? = null,
    val subtype: String? = null,
    // assistant 메시지
    val message: StreamMessage? = null,
    // tool_use / tool_result
    val id: String? = null,  // 도구 호출 ID
    val name: String? = null,
    val input: Any? = null,
    val content: Any? = null,  // tool_result 결과
    // tool_result
    @com.fasterxml.jackson.annotation.JsonProperty("is_error")
    @com.fasterxml.jackson.annotation.JsonAlias("isError")
    val isError: Boolean? = null,
    // result
    val result: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("session_id")
    @com.fasterxml.jackson.annotation.JsonAlias("sessionId")
    val sessionId: String? = null,
    val usage: TokenUsage? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("total_cost_usd")
    @com.fasterxml.jackson.annotation.JsonAlias("cost")
    val cost: Double? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamMessage(
    val role: String? = null,
    val content: List<StreamContent>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamContent(
    val type: String? = null,
    val text: String? = null
)

/**
 * 스트리밍 실행 이벤트
 *
 * executeStreaming()에서 emit되는 이벤트들
 */
sealed class StreamingEvent {
    /**
     * 텍스트 청크 (assistant 응답)
     */
    data class Text(
        val requestId: String,
        val content: String
    ) : StreamingEvent()

    /**
     * 도구 호출 시작
     */
    data class ToolStart(
        val requestId: String,
        val toolId: String,
        val toolName: String,
        val input: Map<String, Any?>
    ) : StreamingEvent()

    /**
     * 도구 호출 완료
     */
    data class ToolEnd(
        val requestId: String,
        val toolId: String,
        val toolName: String,
        val result: String?,
        val success: Boolean
    ) : StreamingEvent()

    /**
     * 에러 발생
     */
    data class Error(
        val requestId: String,
        val message: String
    ) : StreamingEvent()

    /**
     * 실행 완료
     */
    data class Done(
        val requestId: String,
        val sessionId: String?,
        val durationMs: Long,
        val usage: TokenUsage?,
        val cost: Double?,
        val result: String?
    ) : StreamingEvent()
}
