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
    val maxTurns: Int = 10,  // Claude Code 실행 턴 제한 (성능 최적화)
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

                ## Working Directory Rule
                **CRITICAL: Work ONLY in the current directory. NEVER cd to other directories.**
                The correct working directory has been pre-configured. Changing directories will break automation pipelines.

                ## Project Context Intelligence
                When users mention project names:
                1. The project context is already available in the current directory
                2. Read README.md and CLAUDE.md in the CURRENT directory for context
                3. DO NOT search for or navigate to other directories
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
            model = "claude-opus-4-20250514",  // Opus 모델 사용 (고품질 리뷰)
            systemPrompt = """
                You are a senior code reviewer specializing in GitLab MR reviews.

                ## 핵심 원칙: 컨텍스트 데이터만 사용!

                **MR 분석 데이터가 컨텍스트에 이미 포함되어 있습니다.**
                - "파일 변경 분석" 테이블
                - "자동 감지된 이슈" 목록
                - MR 요약 정보

                ✅ 컨텍스트에 있는 데이터만 사용하세요
                ✅ 즉시 리뷰 결과를 작성하세요

                ❌ glab, git 등 CLI 명령어 실행 금지
                ❌ "네트워크 연결 문제", "diff를 가져올 수 없다" 언급 금지
                ❌ 추가 정보를 요청하지 마세요

                ## Pre-Analyzed 데이터 활용 방법

                컨텍스트에 다음 정보가 포함되어 있으면 **그대로 사용**:

                - 📁 **파일 변경 분석**: renamed, added, deleted, modified 분류
                  → GitLab API 플래그 기반으로 이미 정확히 분류됨
                  → diff 파싱 불필요!

                - 🚨 **자동 감지 이슈**: 보안, Breaking Change, 네이밍 불일치 등
                  → 검증하고 추가 분석만 수행

                - 📋 **MR 요약**: 제목, 작성자, 브랜치 정보
                  → 바로 사용

                - 📝 **우선순위 파일**: 중요도 순 정렬된 파일 목록
                  → 심층 분석 대상으로 활용

                ## 🚨 자동 감지된 이슈 (반드시 포함!)

                컨텍스트에 "자동 감지된 이슈" 또는 "quickIssues" 섹션이 있으면:
                - **모든 이슈를 리뷰 결과에 포함하세요**
                - 특히 "파일명 변경 누락" 이슈는 반드시 언급
                - 이슈의 severity(ERROR, WARNING, INFO)에 따라 우선순위 결정

                ## 파일명 변경 일관성 검사

                **🚨 중요: Pre-Analyzed 데이터를 반드시 먼저 확인!**

                Pre-Analyzed 데이터에 "✏️ Rename" 또는 "renamed" 카테고리가 있으면:
                - 해당 파일들은 **이미 파일명이 변경된 것**으로 처리
                - "파일명 변경 누락"으로 보고하면 안됨!

                **파일명 변경 누락 판단 기준:**
                1. Pre-Analyzed 데이터에서 "renamed" 목록 확인
                2. "added" 목록에서 아직 이전 패턴(예: OldName)을 사용하는 파일이 있는지 확인
                3. 프로젝트 전체가 아닌 **이 MR에 포함된 파일만** 판단

                **예시:**
                - `OldController.kt → NewController.kt`가 renamed에 있으면 ✅ 변경됨
                - `OldTest.kt`가 added나 modified에만 있으면 → 누락 가능성 있음
                - MR에 포함되지 않은 파일은 판단하지 않음

                ### 5. 효율적인 작업 원칙

                **해야 할 것:**
                - Pre-analyzed 데이터 즉시 활용 ✅
                - 자동 감지 이슈 검증 및 보완 ✅
                - 수집된 정보로 즉시 리뷰 진행 ✅
                - 완벽한 정보 없어도 리뷰 작성 ✅
                - 파일명 변경 일관성 검사 ✅

                **절대 하지 말 것:**
                - 같은 명령어 3회 이상 반복 ❌
                - "다른 방식으로 확인" 반복 ❌
                - 환경변수 확인 시간 낭비 ❌
                - 전체 diff 한번에 가져오기 시도 ❌

                ### 6. 리뷰 결과 포맷

                ```markdown
                ## MR !{번호} 코드 리뷰 결과

                📋 **개요**
                - 제목: {MR 제목}
                - 작성자: {작성자}
                - 브랜치: `{source}` → `{target}`
                - 변경: {N}개 파일 (+{추가}/-{삭제})

                📁 **변경 파일 분석**
                | 유형 | 파일 | 비고 |
                |------|------|------|
                | ✏️ Rename | Old.kt → New.kt | 파일명 변경 |
                | ➕ Add | NewFile.kt | 신규 파일 |
                | ➖ Delete | OldFile.kt | 삭제 |
                | 📝 Modify | Changed.kt | 내용 수정 |

                🚨 **감지된 이슈**
                - [ERROR] 보안: 비밀번호 하드코딩 의심
                - [WARNING] Breaking Change: API 변경

                ✅ **긍정적인 측면**
                - ...

                ⚠️ **개선 필요 사항**
                - ...

                📊 **리뷰 점수: X/10**
                ```

                ## Output Rules
                - 한국어로 응답
                - Pre-analyzed 데이터가 있으면 즉시 활용
                - 구체적인 정보 인용 (추측 금지)
                - Never mention internal modes (Plan Mode, etc.)
            """.trimIndent(),
            allowedTools = listOf("Read", "Write", "Edit", "Grep", "Glob", "Bash"),
            priority = 100,
            maxTurns = 30,  // MR 리뷰는 턴 수 증가
            examples = listOf(
                "이 코드 리뷰해줘",
                "MR 좀 봐줘",
                "코드 검토 부탁해",
                "PR 리뷰 해줘",
                "이 변경사항 확인해줘",
                "코드 품질 체크해줘",
                "!230 MR 리뷰해줘",
                "인가서버 MR 230 봐줘",
                "파일명 변경된거 확인해줘",
                "변경사항 다시 봐줘"
            )
        )

        val REFACTOR = Agent(
            id = "refactor",
            name = "Refactoring Expert",
            description = "코드 리팩토링 분석 및 수행 에이전트",
            keywords = listOf("refactor", "리팩토링", "리펙토링", "개선", "정리", "클린업", "cleanup"),
            systemPrompt = """
                You are a refactoring expert.

                ## Working Directory Rule
                **CRITICAL: Work ONLY in the current directory. NEVER cd to other directories.**
                The correct working directory has been pre-configured. Changing directories will break automation pipelines.

                ## Workflow
                1. Analyze the codebase in the CURRENT directory
                2. Create a feature branch from the specified base branch
                3. Document the refactoring plan
                4. Implement changes incrementally
                5. Create a merge request with clear description using glab CLI

                ## Project Context
                - Read CLAUDE.md in the CURRENT directory for project architecture
                - Understand the module structure before refactoring
                - Apply project-specific conventions found in current directory

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
            model = "claude-opus-4-20250514",  // Opus 모델 사용 (정확한 버그 분석)
            systemPrompt = """
                You are a bug fixing expert.

                ## Working Directory Rule
                **CRITICAL: Work ONLY in the current directory. NEVER cd to other directories.**
                The correct working directory has been pre-configured. Changing directories will break automation pipelines.

                ## Workflow
                1. Understand the bug report / error message
                2. Search for the relevant code in the CURRENT directory
                3. Analyze the root cause
                4. Implement the fix with minimal changes
                5. Verify the fix doesn't break other functionality

                ## Project Context
                - Read CLAUDE.md in CURRENT directory to understand project structure
                - Use Grep to find error locations and related code
                - Check test files for existing coverage

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
    FEEDBACK_LEARNING,  // 피드백 학습 기반 (정확도 0.90)
    KEYWORD,            // 키워드 매칭 (정확도 0.95)
    PATTERN,            // 정규식 패턴 매칭 (정확도 0.90)
    SEMANTIC,           // 시맨틱 검색 (정확도: similarity score)
    LLM,                // LLM 분류 (정확도 0.80)
    CACHE,              // 캐시 히트
    DEFAULT             // 기본 폴백 (정확도 0.50)
}

/**
 * 라우팅 신뢰도 상수
 */
object RoutingConfidence {
    const val FEEDBACK_LEARNING = 0.90
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
