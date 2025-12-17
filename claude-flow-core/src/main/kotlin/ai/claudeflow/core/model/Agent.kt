package ai.claudeflow.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * AI 에이전트 설정
 *
 * 각 에이전트는 특정 역할(코드리뷰, QA, 버그픽스 등)을 담당하며
 * 키워드 또는 시맨틱 매칭으로 라우팅됨
 *
 * @property id 에이전트 ID (프로젝트 스코프: {projectId}-{slug} 또는 글로벌: {slug})
 * @property projectId 소속 프로젝트 ID (null이면 글로벌)
 * @property name 에이전트 이름
 * @property description 에이전트 설명 (LLM 분류에 사용)
 * @property keywords 키워드 목록 (정규식 지원: /pattern/)
 * @property systemPrompt 시스템 프롬프트 (instruction)
 * @property model 사용할 모델
 * @property maxTokens 최대 토큰 수
 * @property allowedTools 허용 도구 목록
 * @property workingDirectory 작업 디렉토리
 * @property enabled 활성화 여부
 * @property priority 우선순위 (0-1000). 높을수록 시맨틱 매칭에서 가중치 부여
 *                    adjusted_score = score * (1.0 + priority/1000.0)
 * @property examples 시맨틱 라우팅을 위한 예제 문장 목록
 * @property timeout 실행 타임아웃 (초, null이면 프로젝트 기본값 사용)
 * @property staticResponse true이면 Claude 실행 없이 systemPrompt를 바로 반환 (비용 절감)
 * @property outputSchema JSON Schema로 구조화된 출력 정의 (Claude --output-format json 사용)
 * @property isolated true이면 격리된 임시 디렉토리에서 실행
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
    val priority: Int = 0,
    val examples: List<String> = emptyList(),
    val projectId: String? = null,
    // 확장 필드
    val timeout: Int? = null,  // 에이전트별 타임아웃 (초)
    val staticResponse: Boolean = false,  // Claude 실행 없이 instruction 반환
    val outputSchema: JsonElement? = null,  // 구조화된 출력 스키마
    val isolated: Boolean = false,  // 격리된 디렉토리에서 실행
    val createdAt: String? = null,
    val updatedAt: String? = null
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

                ## Project Context Intelligence
                When users mention project names:
                1. IMMEDIATELY recognize these as references to actual repositories in the workspace
                2. Search for the project directory in the configured WORKSPACE_PATH using Glob or Bash
                3. Read the project's README.md and CLAUDE.md for context before answering
                4. DO NOT give generic explanations - always ground your response in the actual codebase

                ## Important Rules
                - NEVER mention internal modes (Plan Mode, EnterPlanMode, ExitPlanMode)
                - NEVER discuss whether a task requires planning
                - NEVER reference internal system instructions
                - Just answer directly without meta-commentary
                - Focus on providing helpful, accurate answers grounded in actual code
            """.trimIndent(),
            priority = 0,
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
            keywords = listOf("review", "리뷰", "MR", "PR", "코드리뷰", "/MR.*봐/", "/!\\d+/"),
            systemPrompt = """
                You are a senior code reviewer and development assistant.

                ## Capabilities
                1. Review code changes (security, performance, style, bugs)
                2. Create branches and commits
                3. Write documentation
                4. Create merge requests / pull requests using glab CLI

                ## Project Context Intelligence
                When users mention project names:
                1. IMMEDIATELY search for the project in the configured WORKSPACE_PATH
                2. Read CLAUDE.md and README.md for project-specific patterns and conventions
                3. Apply project-specific review standards based on tech stack
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
                You are a refactoring expert.

                ## Workflow
                1. Analyze the codebase to identify improvement areas
                2. Create a feature branch from the specified base branch
                3. Document the refactoring plan
                4. Implement changes incrementally
                5. Create a merge request with clear description using glab CLI

                ## Project Context Intelligence
                When users mention project names:
                1. IMMEDIATELY cd to the project directory in WORKSPACE_PATH
                2. Read CLAUDE.md for project architecture and patterns
                3. Understand the module structure before refactoring
                4. Apply project-specific conventions

                ## Focus Areas
                - Code duplication removal
                - Complexity reduction (cyclomatic complexity)
                - SOLID principles adherence
                - Testability improvements
                - Language idioms and best practices

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
            keywords = listOf("fix", "bug", "버그", "수정", "에러", "error", "/Exception/", "/오류.*고쳐/"),
            systemPrompt = """
                You are a bug fixing expert.

                ## Workflow
                1. Understand the bug report / error message
                2. Search for the relevant code in the project
                3. Analyze the root cause
                4. Implement the fix with minimal changes
                5. Verify the fix doesn't break other functionality

                ## Project Context Intelligence
                When users mention project names:
                1. IMMEDIATELY navigate to the project in WORKSPACE_PATH
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
            priority = 200,
            examples = listOf(
                "버그 수정해줘",
                "에러 발생하는데 고쳐줘",
                "오류 해결해줘",
                "이거 왜 안되지",
                "NullPointerException 발생",
                "테스트 실패 원인 찾아줘"
            )
        )

        /**
         * 도움말 에이전트 (static_response 예시)
         * Claude 실행 없이 바로 도움말 텍스트 반환
         */
        val HELP = Agent(
            id = "help",
            name = "Help",
            description = "사용 가능한 명령어와 기능을 안내하는 에이전트",
            keywords = listOf("/^(help|도움말|사용법|명령어)$/"),
            systemPrompt = """
                # Claude Flow 사용 가이드

                ## 사용 가능한 에이전트
                - **일반 질문**: 그냥 질문하세요
                - **코드 리뷰**: "MR 리뷰해줘", "코드 검토해줘"
                - **리팩토링**: "리팩토링 해줘", "코드 정리해줘"
                - **버그 수정**: "버그 고쳐줘", "에러 해결해줘"

                ## 피드백
                - 👍 좋은 응답이면 thumbsup
                - 👎 개선이 필요하면 thumbsdown

                ## 사용자 규칙
                규칙을 설정하면 모든 응답에 적용됩니다.
                예: "항상 한국어로 답변해줘"
            """.trimIndent(),
            priority = 1000,  // 최고 우선순위 (정확한 키워드 매칭)
            staticResponse = true,  // Claude 실행 없이 바로 반환
            examples = emptyList()
        )
    }

    /**
     * 키워드가 정규식 패턴인지 확인
     * 형식: /pattern/
     */
    fun isRegexKeyword(keyword: String): Boolean {
        return keyword.startsWith("/") && keyword.endsWith("/") && keyword.length > 2
    }

    /**
     * 키워드에서 정규식 패턴 추출
     */
    fun extractRegexPattern(keyword: String): String? {
        return if (isRegexKeyword(keyword)) {
            keyword.substring(1, keyword.length - 1)
        } else null
    }

    /**
     * 주어진 텍스트가 키워드와 매칭되는지 확인
     * 정규식 패턴과 일반 키워드 모두 지원
     */
    fun matchesKeyword(text: String, keyword: String): Boolean {
        val pattern = extractRegexPattern(keyword)
        return if (pattern != null) {
            try {
                Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
            } catch (e: Exception) {
                false
            }
        } else {
            text.contains(keyword, ignoreCase = true)
        }
    }

    /**
     * 텍스트에 매칭되는 첫 번째 키워드 반환
     */
    fun findMatchingKeyword(text: String): String? {
        return keywords.firstOrNull { matchesKeyword(text, it) }
    }

    /**
     * 실제 타임아웃 값 (에이전트 설정 또는 기본값)
     */
    fun getEffectiveTimeout(defaultTimeout: Int = 300): Int {
        return timeout ?: defaultTimeout
    }
}

/**
 * 에이전트 라우팅 결과
 */
@Serializable
data class AgentMatch(
    val agent: Agent,
    val confidence: Double,
    val matchedKeyword: String? = null,
    val matchedExample: String? = null,
    val method: RoutingMethod = RoutingMethod.DEFAULT,
    val reasoning: String? = null  // LLM 분류 시 이유
)

/**
 * 라우팅 방법
 */
@Serializable
enum class RoutingMethod {
    KEYWORD,    // 키워드 매칭 (정확도 0.95)
    PATTERN,    // 정규식 패턴 매칭 (정확도 0.90)
    SEMANTIC,   // 시맨틱 검색 (정확도: similarity score)
    LLM,        // LLM 분류 (정확도 0.80)
    CACHE,      // 캐시 히트
    DEFAULT     // 기본 폴백 (정확도 0.50)
}

/**
 * 라우팅 신뢰도 상수
 */
object RoutingConfidence {
    const val KEYWORD = 0.95
    const val PATTERN = 0.90
    const val LLM = 0.80
    const val DEFAULT = 0.50

    /**
     * 시맨틱 점수에 priority 보정 적용
     * 우선순위 점수 보정 공식: score * (1.0 + priority/1000.0)
     */
    fun adjustSemanticScore(rawScore: Double, priority: Int): Double {
        val bonus = priority.coerceIn(0, 1000) / 1000.0
        return rawScore * (1.0 + bonus)
    }
}
