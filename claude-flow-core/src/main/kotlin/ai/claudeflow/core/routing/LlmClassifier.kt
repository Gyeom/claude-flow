package ai.claudeflow.core.routing

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.model.AgentMatch
import mu.KotlinLogging
import java.io.File
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * LLM 기반 분류기
 *
 * 키워드/시맨틱 매칭 실패 시 LLM을 사용하여 에이전트 분류
 * Claude CLI를 호출하여 빠른 분류 수행
 */
class LlmClassifier(
    private val model: String = "claude-sonnet-4-20250514",
    private val timeoutSeconds: Long = 30
) {
    /**
     * LLM으로 에이전트 분류
     */
    fun classify(message: String, agents: List<Agent>): AgentMatch? {
        return try {
            val prompt = buildClassificationPrompt(message, agents)
            val result = executeClaudeClassification(prompt)

            if (result != null) {
                val matchedAgent = agents.find { it.id == result.agentId }
                if (matchedAgent != null) {
                    logger.info { "LLM classified as: ${matchedAgent.id} (${result.reasoning})" }
                    return AgentMatch(
                        agent = matchedAgent,
                        confidence = 0.80,  // LLM 분류는 0.8 confidence
                        matchedKeyword = "llm:${result.reasoning.take(50)}"
                    )
                }
            }
            null
        } catch (e: Exception) {
            logger.warn(e) { "LLM classification failed" }
            null
        }
    }

    private fun buildClassificationPrompt(message: String, agents: List<Agent>): String {
        val agentDescriptions = agents.joinToString("\n") { agent ->
            "- ${agent.id}: ${agent.description}"
        }

        return """
            |You are a classifier. Analyze the user message and select the most appropriate agent.
            |
            |Available agents:
            |$agentDescriptions
            |
            |User message: "$message"
            |
            |Respond ONLY with JSON in this exact format (no other text):
            |{"agent": "agent_id", "reasoning": "brief reason"}
        """.trimMargin()
    }

    private fun executeClaudeClassification(prompt: String): ClassificationResult? {
        val claudePath = findClaudePath() ?: return null

        val process = ProcessBuilder(
            claudePath,
            "-p",
            "--output-format", "text",
            "--model", model,
            "--max-turns", "1",
            prompt
        ).apply {
            redirectErrorStream(true)
        }.start()

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return null
        }

        val output = process.inputStream.bufferedReader().readText().trim()
        return parseClassificationResult(output)
    }

    private fun parseClassificationResult(output: String): ClassificationResult? {
        return try {
            // JSON 추출 (```json ... ``` 또는 { ... } 형태 모두 처리)
            val jsonRegex = """\{[^}]+\}""".toRegex()
            val jsonMatch = jsonRegex.find(output) ?: return null
            val jsonStr = jsonMatch.value

            // 간단한 JSON 파싱
            val agentMatch = """"agent"\s*:\s*"([^"]+)"""".toRegex().find(jsonStr)
            val reasoningMatch = """"reasoning"\s*:\s*"([^"]+)"""".toRegex().find(jsonStr)

            if (agentMatch != null) {
                ClassificationResult(
                    agentId = agentMatch.groupValues[1],
                    reasoning = reasoningMatch?.groupValues?.get(1) ?: "No reasoning"
                )
            } else null
        } catch (e: Exception) {
            logger.warn { "Failed to parse classification result: ${e.message}" }
            null
        }
    }

    private fun findClaudePath(): String? {
        val possiblePaths = listOf(
            System.getenv("HOME") + "/.local/bin/claude",
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
            "claude"
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return path
            }
        }

        // Try which command
        return try {
            val process = ProcessBuilder("which", "claude").start()
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private data class ClassificationResult(
        val agentId: String,
        val reasoning: String
    )
}
