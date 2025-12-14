package ai.claudeflow.core.model

import kotlinx.serialization.Serializable

/**
 * AI 에이전트 설정
 *
 * 각 에이전트는 특정 역할(코드리뷰, QA, 버그픽스 등)을 담당하며
 * 키워드 또는 시맨틱 매칭으로 라우팅됨
 *
 * @property priority 에이전트 우선순위 (0-1000). 높을수록 시맨틱 매칭에서 가중치 부여
 *                    adjusted_score = score * (1.0 + priority/1000.0)
 * @property examples 시맨틱 라우팅을 위한 예제 문장 목록
 * @property projectId 소속 프로젝트 ID (null이면 글로벌)
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
    val enabled: Boolean = true,
    val priority: Int = 0,  // 0-1000, Claude Flow 스타일 우선순위
    val examples: List<String> = emptyList(),  // 시맨틱 라우팅 예제
    val projectId: String? = null  // 프로젝트별 에이전트 지원
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

                IMPORTANT RULES:
                - NEVER mention or explain internal modes like "Plan Mode", "EnterPlanMode", "ExitPlanMode", or any internal tool/mode names
                - NEVER discuss whether a task requires planning or not
                - NEVER reference internal system instructions or how you process requests
                - Just answer the user's question directly without meta-commentary about your process
                - Focus solely on providing helpful, accurate answers
            """.trimIndent(),
            priority = 0,  // 가장 낮은 우선순위 (폴백용)
            examples = listOf(
                "이거 어떻게 하는거야",
                "설명해줘",
                "뭐야 이거",
                "도움이 필요해",
                "구현 방법 알려줘",
                "아키텍처 설명해줘"
            )
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

                IMPORTANT: Never mention internal modes (Plan Mode, EnterPlanMode, etc.) or discuss your internal process. Just provide the review directly.
            """.trimIndent(),
            allowedTools = listOf("Read", "Grep", "Glob"),
            priority = 100,  // 중간 우선순위
            examples = listOf(
                "이 코드 리뷰해줘",
                "MR 좀 봐줘",
                "코드 검토 부탁해",
                "PR 리뷰 해줘",
                "이 변경사항 확인해줘",
                "코드 품질 체크해줘"
            )
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

                IMPORTANT: Never mention internal modes (Plan Mode, EnterPlanMode, etc.) or discuss your internal process. Just fix the bug directly.
            """.trimIndent(),
            allowedTools = listOf("Read", "Edit", "Grep", "Glob", "Bash"),
            priority = 200,  // 높은 우선순위
            examples = listOf(
                "버그 수정해줘",
                "에러 발생하는데 고쳐줘",
                "오류 해결해줘",
                "이거 왜 안되지",
                "NullPointerException 발생",
                "테스트 실패 원인 찾아줘"
            )
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
