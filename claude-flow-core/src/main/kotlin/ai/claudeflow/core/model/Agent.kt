package ai.claudeflow.core.model

import kotlinx.serialization.Serializable

/**
 * AI 에이전트 설정
 *
 * 각 에이전트는 특정 역할(코드리뷰, QA, 버그픽스 등)을 담당하며
 * 키워드 또는 시맨틱 매칭으로 라우팅됨
 */
@Serializable
data class Agent(
    val id: String,
    val name: String,
    val description: String,
    val keywords: List<String> = emptyList(),
    val systemPrompt: String,
    val model: String = "claude-sonnet-4-20250514",
    val maxTokens: Int = 4096,
    val allowedTools: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val enabled: Boolean = true
) {
    companion object {
        val GENERAL = Agent(
            id = "general",
            name = "General Assistant",
            description = "일반적인 질문에 답변하는 에이전트",
            keywords = listOf("질문", "help", "도움"),
            systemPrompt = """
                You are a helpful AI assistant for software development.
                Answer questions concisely and accurately.
                Use Korean when the user asks in Korean.
            """.trimIndent()
        )

        val CODE_REVIEWER = Agent(
            id = "code-reviewer",
            name = "Code Reviewer",
            description = "코드 리뷰를 수행하는 에이전트",
            keywords = listOf("review", "리뷰", "MR", "PR", "코드리뷰"),
            systemPrompt = """
                You are a senior code reviewer.
                Review code changes focusing on:
                1. Security vulnerabilities
                2. Performance issues
                3. Code style and best practices
                4. Potential bugs

                Provide constructive feedback in Korean.
            """.trimIndent(),
            allowedTools = listOf("Read", "Grep", "Glob")
        )

        val BUG_FIXER = Agent(
            id = "bug-fixer",
            name = "Bug Fixer",
            description = "버그를 분석하고 수정하는 에이전트",
            keywords = listOf("fix", "bug", "버그", "수정", "에러", "error"),
            systemPrompt = """
                You are a bug fixing expert.
                Analyze the issue, find the root cause, and provide a fix.
                Always explain your reasoning.
            """.trimIndent(),
            allowedTools = listOf("Read", "Edit", "Grep", "Glob", "Bash")
        )
    }
}

/**
 * 에이전트 라우팅 결과
 */
@Serializable
data class AgentMatch(
    val agent: Agent,
    val confidence: Double,
    val matchedKeyword: String? = null
)
