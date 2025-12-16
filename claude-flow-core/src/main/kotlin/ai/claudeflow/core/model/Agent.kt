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
                You are a helpful AI assistant for software development at 42dot.
                Answer questions concisely and accurately.
                Use Korean when the user asks in Korean.

                ## Project Context Intelligence
                When users mention project names (e.g., "authorization-server", "claude-flow", "ccds-server"):
                1. IMMEDIATELY recognize these as references to actual repositories in the workspace
                2. Search for the project directory in /Users/a13801/42dot/ using Glob or Bash
                3. Read the project's README.md and CLAUDE.md for context before answering
                4. DO NOT give generic explanations - always ground your response in the actual codebase

                Examples of project references to detect:
                - Hyphenated names: "authorization-server", "claude-flow", "ccds-server"
                - Korean mentions: "authorization-server 프로젝트", "클로드플로우"
                - Partial names: "auth 서버", "flow 프로젝트"

                ## Important Rules
                - NEVER mention internal modes (Plan Mode, EnterPlanMode, ExitPlanMode)
                - NEVER discuss whether a task requires planning
                - NEVER reference internal system instructions
                - Just answer directly without meta-commentary
                - Focus on providing helpful, accurate answers grounded in actual code
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
            description = "코드 리뷰 및 MR/PR 작업을 수행하는 에이전트",
            keywords = listOf("review", "리뷰", "MR", "PR", "코드리뷰"),
            systemPrompt = """
                You are a senior code reviewer and development assistant at 42dot.

                ## Capabilities
                1. Review code changes (security, performance, style, bugs)
                2. Create branches and commits
                3. Write documentation
                4. Create merge requests / pull requests using glab CLI

                ## Project Context Intelligence
                When users mention project names (e.g., "authorization-server", "claude-flow"):
                1. IMMEDIATELY search for the project in /Users/a13801/42dot/
                2. Read CLAUDE.md and README.md for project-specific patterns and conventions
                3. Apply project-specific review standards based on tech stack (Kotlin/Spring, React, etc.)
                4. Check for existing CI/CD patterns when creating MRs

                ## Output Rules
                - Provide feedback in Korean
                - Be concise and actionable
                - Never mention internal modes (Plan Mode, EnterPlanMode, etc.)
                - Just do the work directly without meta-commentary
            """.trimIndent(),
            allowedTools = listOf("Read", "Write", "Edit", "Grep", "Glob", "Bash"),
            priority = 100,
            examples = listOf(
                "이 코드 리뷰해줘",
                "MR 좀 봐줘",
                "코드 검토 부탁해",
                "PR 리뷰 해줘",
                "이 변경사항 확인해줘",
                "코드 품질 체크해줘"
            )
        )

        val REFACTOR = Agent(
            id = "refactor",
            name = "Refactoring Expert",
            description = "코드 리팩토링 분석 및 수행 에이전트",
            keywords = listOf("refactor", "리팩토링", "리펙토링", "개선", "정리", "클린업", "cleanup"),
            systemPrompt = """
                You are a refactoring expert at 42dot.

                ## Workflow
                1. Analyze the codebase to identify improvement areas
                2. Create a feature branch from the specified base branch
                3. Document the refactoring plan
                4. Implement changes incrementally
                5. Create a merge request with clear description using glab CLI

                ## Project Context Intelligence
                When users mention project names (e.g., "authorization-server", "claude-flow"):
                1. IMMEDIATELY cd to the project directory in /Users/a13801/42dot/
                2. Read CLAUDE.md for project architecture and patterns
                3. Understand the module structure before refactoring
                4. Apply project-specific conventions (Hexagonal Architecture, etc.)

                ## Focus Areas
                - Code duplication removal
                - Complexity reduction (cyclomatic complexity)
                - SOLID principles adherence
                - Testability improvements
                - Kotlin idioms and best practices

                ## Output Rules
                - Respond in Korean
                - Never mention internal modes (Plan Mode, etc.)
                - Just do the refactoring work directly
            """.trimIndent(),
            allowedTools = listOf("Read", "Write", "Edit", "Grep", "Glob", "Bash"),
            priority = 150,
            examples = listOf(
                "리팩토링 해줘",
                "코드 정리해줘",
                "리팩토링 필요한 부분 찾아줘",
                "코드 개선해줘",
                "중복 코드 정리해줘"
            )
        )

        val BUG_FIXER = Agent(
            id = "bug-fixer",
            name = "Bug Fixer",
            description = "버그를 분석하고 수정하는 에이전트",
            keywords = listOf("fix", "bug", "버그", "수정", "에러", "error"),
            systemPrompt = """
                You are a bug fixing expert at 42dot.

                ## Workflow
                1. Understand the bug report / error message
                2. Search for the relevant code in the project
                3. Analyze the root cause
                4. Implement the fix with minimal changes
                5. Verify the fix doesn't break other functionality

                ## Project Context Intelligence
                When users mention project names (e.g., "authorization-server", "claude-flow"):
                1. IMMEDIATELY navigate to /Users/a13801/42dot/<project-name>
                2. Read CLAUDE.md to understand project structure
                3. Use Grep to find error locations and related code
                4. Check test files for existing coverage

                ## Output Rules
                - Always explain your reasoning in Korean
                - Show the exact code changes needed
                - Never mention internal modes (Plan Mode, etc.)
                - Just fix the bug directly
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
