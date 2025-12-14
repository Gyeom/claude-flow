package ai.claudeflow.executor

import ai.claudeflow.core.model.ClaudeConfig
import ai.claudeflow.core.model.OutputFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
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

        logger.info { "[$requestId] Starting Claude execution (async): ${request.prompt.take(50)}..." }

        // 작업 디렉토리 검증
        val workingDir = request.workingDirectory?.let { File(it) }
        if (workingDir != null && !workingDir.exists()) {
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
        logger.info { "[$requestId] CLI args: claude ${args.joinToString(" ")}" }
        if (existingSession != null) {
            logger.info { "[$requestId] Resuming session: ${existingSession.sessionId}" }
        }

        val timeoutSeconds = (request.config?.timeoutSeconds ?: defaultConfig.timeoutSeconds).toLong()

        try {
            val result = withTimeoutOrNull(timeoutSeconds * 1000L) {
                runProcessAsync(args, workingDir, requestId, timeoutSeconds)
            } ?: ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.TIMEOUT,
                error = "Execution timed out after ${timeoutSeconds} seconds"
            )

            // Session ID 저장 (성공 시)
            if (result.status == ExecutionStatus.SUCCESS && result.sessionId != null && sessionKey != null) {
                sessionCache[sessionKey] = SessionInfo(result.sessionId)
                logger.info { "[$requestId] Session cached: ${result.sessionId} for key: $sessionKey" }
            }

            result.copy(durationMs = System.currentTimeMillis() - startTime)
        } catch (e: Exception) {
            logger.error(e) { "[$requestId] Execution failed" }
            ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.ERROR,
                error = "Execution failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime
            )
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

        // 출력 형식
        args.addAll(listOf("--output-format", config.outputFormat.name.lowercase()))

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
     */
    private suspend fun runProcessAsync(
        args: List<String>,
        workingDir: File?,
        requestId: String,
        timeoutSeconds: Long
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val claudePath = findClaudePath()
        logger.info { "[$requestId] Running (async): $claudePath ${args.take(5).joinToString(" ")}..." }

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

        // stdout과 stderr를 비동기로 읽기
        val stdoutDeferred = async {
            process.inputStream.bufferedReader().use { it.readText() }
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

        val stdout = stdoutDeferred.await()
        val stderr = stderrDeferred.await()
        val exitCode = process.exitValue()

        logger.info { "[$requestId] Process completed with exit code: $exitCode" }
        logger.debug { "[$requestId] stdout length: ${stdout.length}, stderr length: ${stderr.length}" }

        if (exitCode == 0) {
            parseSuccessResponse(stdout, requestId)
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
    val forceNewSession: Boolean = false // true면 기존 session 무시
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
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0
)
