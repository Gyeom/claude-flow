package ai.claudeflow.executor

import ai.claudeflow.core.model.ClaudeConfig
import ai.claudeflow.core.model.OutputFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Claude CLI 실행기
 *
 * Claude Code CLI를 래핑하여 프로그래매틱하게 실행
 */
class ClaudeExecutor(
    private val defaultConfig: ClaudeConfig = ClaudeConfig()
) {
    private val objectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    /**
     * Claude CLI 실행 (블로킹)
     */
    fun execute(request: ExecutionRequest): ExecutionResult {
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
        logger.info { "[$requestId] CLI args: claude ${args.joinToString(" ")}" }

        val timeoutSeconds = (request.config?.timeoutSeconds ?: defaultConfig.timeoutSeconds).toLong()

        return try {
            val result = runProcess(args, workingDir, requestId, timeoutSeconds)
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

    private fun buildArgs(request: ExecutionRequest): List<String> {
        val config = request.config ?: defaultConfig
        val args = mutableListOf<String>()

        // 기본 옵션
        args.add("-p")  // 프롬프트 모드

        // 출력 형식
        args.addAll(listOf("--output-format", config.outputFormat.name.lowercase()))

        // 모델
        args.addAll(listOf("--model", request.model ?: config.model))

        // 권한 모드 - 자동화 환경에서는 skip permissions 사용
        args.add("--dangerously-skip-permissions")

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

    private fun runProcess(
        args: List<String>,
        workingDir: File?,
        requestId: String,
        timeoutSeconds: Long
    ): ExecutionResult {
        val claudePath = findClaudePath()
        logger.info { "[$requestId] Running: $claudePath ${args.take(5).joinToString(" ")}..." }

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

        // stdout과 stderr를 별도 스레드에서 읽기
        val stdoutBuilder = StringBuilder()
        val stdoutThread = Thread {
            process.inputStream.bufferedReader().use { reader ->
                stdoutBuilder.append(reader.readText())
            }
        }.also { it.start() }

        val stderrBuilder = StringBuilder()
        val stderrThread = Thread {
            process.errorStream.bufferedReader().use { reader ->
                stderrBuilder.append(reader.readText())
            }
        }.also { it.start() }

        // 프로세스 완료 대기 (타임아웃 적용)
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (!completed) {
            process.destroyForcibly()
            logger.warn { "[$requestId] Process timed out after ${timeoutSeconds}s" }
            return ExecutionResult(
                requestId = requestId,
                status = ExecutionStatus.TIMEOUT,
                error = "Execution timed out after ${timeoutSeconds} seconds"
            )
        }

        // 스레드 완료 대기
        stdoutThread.join(5000)
        stderrThread.join(5000)

        val stdout = stdoutBuilder.toString()
        val stderr = stderrBuilder.toString()
        val exitCode = process.exitValue()

        logger.info { "[$requestId] Process completed with exit code: $exitCode" }
        logger.debug { "[$requestId] stdout length: ${stdout.length}, stderr length: ${stderr.length}" }

        return if (exitCode == 0) {
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
                usage = cliOutput.usage
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
