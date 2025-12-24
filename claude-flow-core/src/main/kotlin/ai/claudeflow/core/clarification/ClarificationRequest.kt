package ai.claudeflow.core.clarification

/**
 * 사용자에게 명확화를 요청하는 응답 패턴
 *
 * Claude Code의 AskUserQuestion 패턴을 참고하여 구현
 * 컨텍스트가 부족하거나 여러 선택지가 있을 때 사용
 *
 * 사용 시나리오:
 * - 여러 프로젝트에서 동일한 MR/이슈 번호 발견
 * - 여러 에이전트가 요청을 처리할 수 있을 때
 * - 모호한 명령어 (리뷰 vs 수정 vs 분석)
 * - 필수 정보 누락
 */
data class ClarificationRequest(
    /** 질문 헤더 (짧게, 12자 이내) */
    val header: String,

    /** 사용자에게 보여줄 질문 */
    val question: String,

    /** 선택 옵션들 (2-4개 권장) */
    val options: List<ClarificationOption>,

    /** 다중 선택 허용 여부 */
    val multiSelect: Boolean = false,

    /** 추가 컨텍스트 데이터 */
    val context: Map<String, Any> = emptyMap(),

    /** 명확화 타입 */
    val type: ClarificationType = ClarificationType.SELECTION
) {
    /**
     * Slack 메시지 형식으로 변환
     */
    fun toSlackMessage(): String = buildString {
        appendLine("❓ **$header**")
        appendLine()
        appendLine(question)
        appendLine()
        options.forEachIndexed { idx, option ->
            appendLine("${idx + 1}. **${option.label}**")
            if (option.description.isNotBlank()) {
                appendLine("   ${option.description}")
            }
        }
        appendLine()
        if (multiSelect) {
            appendLine("_여러 개 선택 가능합니다. 번호를 입력하세요 (예: 1, 3)_")
        } else {
            appendLine("_번호를 입력하거나 직접 답변해주세요_")
        }
    }

    /**
     * AskUserQuestion 형식으로 변환 (Claude Code 호환)
     */
    fun toAskUserQuestionFormat(): Map<String, Any> = mapOf(
        "questions" to listOf(
            mapOf(
                "question" to question,
                "header" to header.take(12),
                "multiSelect" to multiSelect,
                "options" to options.map { opt ->
                    mapOf(
                        "label" to opt.label,
                        "description" to opt.description
                    )
                }
            )
        )
    )

    companion object {
        /**
         * 선택형 명확화 요청 생성 (범용)
         *
         * 프로젝트, MR, 액션 등 모든 선택 상황에 사용
         */
        fun selection(
            header: String,
            question: String,
            options: List<Pair<String, String>>
        ): ClarificationRequest = ClarificationRequest(
            header = header,
            question = question,
            options = options.map { (label, desc) -> ClarificationOption(label, desc) },
            type = ClarificationType.SELECTION
        )

        /**
         * 확인 요청 (Yes/No)
         */
        fun confirm(
            question: String,
            yesLabel: String = "네",
            noLabel: String = "아니오",
            yesDescription: String = "",
            noDescription: String = ""
        ): ClarificationRequest = ClarificationRequest(
            header = "확인",
            question = question,
            options = listOf(
                ClarificationOption(yesLabel, yesDescription),
                ClarificationOption(noLabel, noDescription)
            ),
            type = ClarificationType.CONFIRMATION
        )

        /**
         * 추가 정보(텍스트) 입력 요청
         */
        fun input(
            header: String,
            question: String,
            suggestions: List<String> = emptyList()
        ): ClarificationRequest = ClarificationRequest(
            header = header,
            question = question,
            options = suggestions.take(4).map { hint ->
                ClarificationOption(hint, "")
            }.ifEmpty {
                listOf(ClarificationOption("직접 입력", "원하는 값을 입력해주세요"))
            },
            type = ClarificationType.INPUT
        )
    }
}

/**
 * 선택 옵션
 */
data class ClarificationOption(
    /** 옵션 라벨 (1-5 단어) */
    val label: String,

    /** 옵션 설명 */
    val description: String = "",

    /** 옵션 값 (선택 시 반환되는 값) */
    val value: String = label,

    /** 추가 메타데이터 */
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * 명확화 요청 타입
 */
enum class ClarificationType {
    /** 여러 항목 중 선택 (프로젝트, MR, 액션 등 모든 선택) */
    SELECTION,

    /** 확인 (Yes/No) */
    CONFIRMATION,

    /** 추가 정보 요청 (텍스트 입력 필요) */
    INPUT
}

/**
 * 명확화가 필요한 상황을 나타내는 Result wrapper
 */
sealed class ClarifiedResult<out T> {
    /** 성공 - 명확화 없이 진행 가능 */
    data class Success<T>(val value: T) : ClarifiedResult<T>()

    /** 명확화 필요 - 사용자에게 질문 */
    data class NeedsClarification(val request: ClarificationRequest) : ClarifiedResult<Nothing>()

    /** 실패 */
    data class Failure(val error: String) : ClarifiedResult<Nothing>()

    fun <R> map(transform: (T) -> R): ClarifiedResult<R> = when (this) {
        is Success -> Success(transform(value))
        is NeedsClarification -> this
        is Failure -> this
    }

    fun getOrNull(): T? = when (this) {
        is Success -> value
        else -> null
    }
}
