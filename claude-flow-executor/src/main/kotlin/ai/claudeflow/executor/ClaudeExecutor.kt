package ai.claudeflow.executor

import ai.claudeflow.core.model.ClaudeConfig
import ai.claudeflow.core.model.OutputFormat
import ai.claudeflow.core.model.PermissionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Claude CLI 실행기
 *
 * Claude Code CLI를 래핑하여 프로그래매틱하게 실행
 */
class ClaudeExecutor(
    private val defaultConfig: ClaudeConfig = ClaudeConfig()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Claude CLI 실행
     */
    suspend fun execute(request: ExecutionRequest): ExecutionResult {
        val requestId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        logger.info { "[$requestId] Starting Claude execution: ${request.prompt.take(50)}..." }

        // 작업 디렉토리 검증
        val workingDir = request.workingDirectory?.let { File(it) }
        if (workingDir != null && !workingDir.exists()) {
            return ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.ERROR,
                error = "Working directory does not exist: ${request.workingDirectory}",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // CLI 인자 구성
        val args = buildArgs(request)
        logger.debug { "[$requestId] CLI args: claude ${args.joinToString(" ")}" }

        return withContext(Dispatchers.IO) {
            val timeoutSeconds = request.config?.timeoutSeconds ?: defaultConfig.timeoutSeconds

            val result = withTimeoutOrNull(timeoutSeconds.seconds) {
                runProcess(args, workingDir, requestId)
            }

            if (result == null) {
                logger.warn { "[$requestId] Execution timed out after ${timeoutSeconds}s" }
                ExecutionResult(
                    requestId = requestId,
                    status = ExecutionStatus.TIMEOUT,
                    error = "Execution timed out after ${timeoutSeconds} seconds",
                    durationMs = System.currentTimeMillis() - startTime
                )
            } else {
                result.copy(durationMs = System.currentTimeMillis() - startTime)
            }
        }
    }

    private fun buildArgs(request: ExecutionRequest): List<String> {
        val config = request.config ?: defaultConfig
        val args = mutableListOf<String>()

        // 기본 옵션
        args.add("-p")  // 프롬프트 모드

        // 출력 형식
        args.addAll(listOf("--output-format", config.outputFormat.name.lowercase()))

        // 모델
        args.addAll(listOf("--model", request.model ?: config.model))

        // 권한 모드
        val permissionMode = when (config.permissionMode) {
            PermissionMode.PLAN -> "plan"
            PermissionMode.ACCEPT_EDITS -> "acceptEdits"
            PermissionMode.DONT_ASK -> "dontAsk"
        }
        args.addAll(listOf("--permission-mode", permissionMode))

        // max-turns
        request.maxTurns?.let {
            args.addAll(listOf("--max-turns", it.toString()))
        }

        // 허용 도구
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

        // 프롬프트 (마지막에 추가)
        args.add(request.prompt)

        return args
    }

    private suspend fun runProcess(
        args: List<String>,
        workingDir: File?,
        requestId: String
    ): ExecutionResult {
        return try {
            val processBuilder = ProcessBuilder(listOf("claude") + args)
                .apply {
                    workingDir?.let { directory(it) }
                    redirectErrorStream(false)
                }

            val process = processBuilder.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            logger.debug { "[$requestId] Exit code: $exitCode" }

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
        } catch (e: Exception) {
            logger.error(e) { "[$requestId] Failed to execute Claude CLI" }
            ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.ERROR,
                error = "Failed to spawn process: ${e.message}"
            )
        }
    }

    private fun parseSuccessResponse(output: String, requestId: String): ExecutionResult {
        return try {
            // JSON 출력인 경우 파싱 시도
            val cliOutput = json.decodeFromString<ClaudeCliOutput>(output)
            ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.SUCCESS,
                result = cliOutput.result,
                rawOutput = output,
                usage = cliOutput.usage
            )
        } catch (e: Exception) {
            // JSON 파싱 실패 시 원문 반환
            logger.debug { "[$requestId] Output is not JSON, returning raw text" }
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
    val config: ClaudeConfig? = null
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
    val durationMs: Long = 0
)

enum class ExecutionStatus {
    SUCCESS,
    ERROR,
    TIMEOUT
}

/**
 * Claude CLI JSON 출력 구조
 */
@Serializable
data class ClaudeCliOutput(
    val result: String? = null,
    val usage: TokenUsage? = null,
    val cost: Double? = null,
    val sessionId: String? = null
)

@Serializable
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0
)
