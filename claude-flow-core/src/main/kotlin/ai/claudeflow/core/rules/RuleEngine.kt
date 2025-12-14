package ai.claudeflow.core.rules

import mu.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * Rules 조건부 라우팅 시스템
 *
 * Claudio 스타일의 규칙 기반 라우팅:
 * - 조건 매칭 (채널, 사용자, 시간대 등)
 * - 우선순위 기반 규칙 평가
 * - 다양한 액션 지원
 */
class RuleEngine {
    private val rules = mutableListOf<Rule>()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 규칙 추가
     */
    fun addRule(rule: Rule) {
        rules.add(rule)
        rules.sortByDescending { it.priority }
        logger.info { "Added rule: ${rule.name} (priority: ${rule.priority})" }
    }

    /**
     * 규칙 삭제
     */
    fun removeRule(ruleId: String): Boolean {
        return rules.removeIf { it.id == ruleId }.also {
            if (it) logger.info { "Removed rule: $ruleId" }
        }
    }

    /**
     * 모든 규칙 조회
     */
    fun listRules(): List<Rule> = rules.toList()

    /**
     * 컨텍스트에 매칭되는 규칙 찾기
     */
    fun evaluate(context: RuleContext): List<RuleMatch> {
        return rules
            .filter { it.enabled }
            .mapNotNull { rule ->
                if (matchesConditions(rule.conditions, context)) {
                    RuleMatch(rule = rule, matchedConditions = rule.conditions)
                } else null
            }
    }

    /**
     * 첫 번째 매칭 규칙 찾기 (우선순위 높은 순)
     */
    fun findFirstMatch(context: RuleContext): RuleMatch? {
        return evaluate(context).firstOrNull()
    }

    /**
     * 조건 매칭 검사
     */
    private fun matchesConditions(conditions: List<Condition>, context: RuleContext): Boolean {
        if (conditions.isEmpty()) return true

        return conditions.all { condition ->
            matchesCondition(condition, context)
        }
    }

    private fun matchesCondition(condition: Condition, context: RuleContext): Boolean {
        val contextValue = when (condition.field) {
            "channel" -> context.channel
            "user" -> context.userId
            "message" -> context.message
            "agent" -> context.agentId
            "project" -> context.projectId
            "hour" -> context.hour.toString()
            "dayOfWeek" -> context.dayOfWeek.toString()
            "isThread" -> context.isThread.toString()
            "hasAttachment" -> context.hasAttachment.toString()
            "language" -> context.language
            else -> context.metadata[condition.field]
        }

        return when (condition.operator) {
            ConditionOperator.EQUALS -> contextValue == condition.value
            ConditionOperator.NOT_EQUALS -> contextValue != condition.value
            ConditionOperator.CONTAINS -> contextValue?.contains(condition.value, ignoreCase = true) == true
            ConditionOperator.NOT_CONTAINS -> contextValue?.contains(condition.value, ignoreCase = true) != true
            ConditionOperator.STARTS_WITH -> contextValue?.startsWith(condition.value, ignoreCase = true) == true
            ConditionOperator.ENDS_WITH -> contextValue?.endsWith(condition.value, ignoreCase = true) == true
            ConditionOperator.REGEX -> contextValue?.let { Regex(condition.value).containsMatchIn(it) } == true
            ConditionOperator.IN -> condition.value.split(",").map { it.trim() }.contains(contextValue)
            ConditionOperator.NOT_IN -> !condition.value.split(",").map { it.trim() }.contains(contextValue)
            ConditionOperator.GREATER_THAN -> (contextValue?.toIntOrNull() ?: 0) > (condition.value.toIntOrNull() ?: 0)
            ConditionOperator.LESS_THAN -> (contextValue?.toIntOrNull() ?: 0) < (condition.value.toIntOrNull() ?: 0)
            ConditionOperator.IS_EMPTY -> contextValue.isNullOrBlank()
            ConditionOperator.IS_NOT_EMPTY -> !contextValue.isNullOrBlank()
        }
    }

    /**
     * 기본 규칙 초기화
     */
    fun initializeDefaults() {
        // 업무 시간 외 자동 응답
        addRule(Rule(
            id = "after-hours",
            name = "업무 시간 외 자동 응답",
            description = "18시 이후 또는 9시 이전 메시지에 자동 응답",
            priority = 100,
            conditions = listOf(
                Condition("hour", ConditionOperator.NOT_IN, "9,10,11,12,13,14,15,16,17")
            ),
            actions = listOf(
                RuleAction(
                    type = ActionType.REPLY,
                    params = mapOf("message" to "현재 업무 시간이 아닙니다. 내일 확인하겠습니다.")
                )
            )
        ))

        // VIP 사용자 우선 처리
        addRule(Rule(
            id = "vip-user",
            name = "VIP 사용자 우선 처리",
            description = "특정 사용자의 메시지를 우선 처리",
            priority = 200,
            conditions = listOf(
                Condition("user", ConditionOperator.IN, "U123456,U789012")  // VIP 사용자 ID
            ),
            actions = listOf(
                RuleAction(
                    type = ActionType.SET_PRIORITY,
                    params = mapOf("priority" to "high")
                )
            )
        ))

        // 특정 채널 전용 에이전트
        addRule(Rule(
            id = "dev-channel-agent",
            name = "개발 채널 전용 에이전트",
            description = "개발 관련 채널은 bug-fixer 에이전트 사용",
            priority = 150,
            conditions = listOf(
                Condition("channel", ConditionOperator.REGEX, ".*(dev|develop|engineering).*")
            ),
            actions = listOf(
                RuleAction(
                    type = ActionType.ROUTE_TO_AGENT,
                    params = mapOf("agentId" to "bug-fixer")
                )
            )
        ))

        logger.info { "Initialized ${rules.size} default rules" }
    }
}

/**
 * 규칙 정의
 */
@Serializable
data class Rule(
    val id: String,
    val name: String,
    val description: String = "",
    val priority: Int = 0,  // 높을수록 먼저 평가
    val enabled: Boolean = true,
    val conditions: List<Condition> = emptyList(),
    val actions: List<RuleAction> = emptyList()
)

/**
 * 조건
 */
@Serializable
data class Condition(
    val field: String,
    val operator: ConditionOperator,
    val value: String
)

/**
 * 조건 연산자
 */
@Serializable
enum class ConditionOperator {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    NOT_CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    REGEX,
    IN,
    NOT_IN,
    GREATER_THAN,
    LESS_THAN,
    IS_EMPTY,
    IS_NOT_EMPTY
}

/**
 * 규칙 액션
 */
@Serializable
data class RuleAction(
    val type: ActionType,
    val params: Map<String, String> = emptyMap()
)

/**
 * 액션 타입
 */
@Serializable
enum class ActionType {
    ROUTE_TO_AGENT,    // 특정 에이전트로 라우팅
    SET_PRIORITY,      // 우선순위 설정
    REPLY,             // 자동 응답
    NOTIFY,            // 알림 전송
    LOG,               // 로깅
    SKIP,              // 처리 스킵
    FORWARD,           // 다른 채널로 전달
    TAG,               // 태그 추가
    SET_CONTEXT,       // 컨텍스트 설정
    TRIGGER_WORKFLOW   // 워크플로우 트리거
}

/**
 * 규칙 평가 컨텍스트
 */
data class RuleContext(
    val channel: String,
    val userId: String,
    val message: String,
    val agentId: String? = null,
    val projectId: String? = null,
    val hour: Int = java.time.LocalTime.now().hour,
    val dayOfWeek: Int = java.time.LocalDate.now().dayOfWeek.value,
    val isThread: Boolean = false,
    val hasAttachment: Boolean = false,
    val language: String = "ko",
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 규칙 매칭 결과
 */
data class RuleMatch(
    val rule: Rule,
    val matchedConditions: List<Condition>
)
