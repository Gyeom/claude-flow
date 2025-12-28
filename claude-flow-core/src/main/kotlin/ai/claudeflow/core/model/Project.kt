package ai.claudeflow.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 프로젝트 설정 (멀티테넌시 지원)
 *
 * 각 프로젝트(팀/서비스)별로 독립적인 에이전트, 설정, Rate Limit을 가짐
 * 멀티테넌시 구현
 *
 * @property id 프로젝트 고유 ID (slug 형태 권장: my-team, backend-service)
 * @property name 프로젝트 표시명
 * @property description 프로젝트 설명
 * @property workingDirectory 기본 작업 디렉토리
 * @property isDefault 기본 프로젝트 여부 (채널 매핑 없을 때 사용)
 * @property enableUserContext 사용자 컨텍스트 적용 여부
 * @property classifyModel 에이전트 분류에 사용할 모델 (haiku 권장)
 * @property classifyTimeout 분류 타임아웃 (초)
 * @property rateLimitRpm 분당 요청 제한 (0 = 무제한)
 * @property allowedTools 허용된 도구 목록 (빈 목록 = 모두 허용)
 * @property disallowedTools 금지된 도구 목록
 * @property fallbackAgentId 폴백 에이전트 ID
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val description: String? = null,
    val workingDirectory: String,
    val gitRemote: String? = null,
    val gitlabPath: String? = null,  // GitLab 프로젝트 경로 (예: "team/my-project")
    val defaultBranch: String = "main",
    val isDefault: Boolean = false,
    val enableUserContext: Boolean = true,
    val classifyModel: String = "haiku",
    val classifyTimeout: Int = 30,
    val rateLimitRpm: Int = 0,  // 0 = unlimited
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val fallbackAgentId: String = "general",
    val aliases: List<String> = emptyList(),  // 프로젝트 별칭 (RAG 검색용)
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    companion object {
        /**
         * 기본 프로젝트 생성
         */
        fun default(workingDirectory: String = "/workspace") = Project(
            id = "default",
            name = "Default Project",
            description = "기본 프로젝트",
            workingDirectory = workingDirectory,
            isDefault = true
        )
    }

    /**
     * 에이전트 ID 생성 (프로젝트 스코프)
     * 형식: {projectId}-{agentSlug}
     */
    fun scopedAgentId(agentSlug: String): String {
        return if (id == "default") agentSlug else "$id-$agentSlug"
    }

    /**
     * 도구가 허용되는지 확인
     */
    fun isToolAllowed(tool: String): Boolean {
        if (disallowedTools.contains(tool)) return false
        if (allowedTools.isEmpty()) return true
        return allowedTools.contains(tool)
    }
}

/**
 * Claude CLI 실행 설정
 */
@Serializable
data class ClaudeConfig(
    val model: String = "claude-sonnet-4-20250514",
    val maxTokens: Int = 4096,
    val timeoutSeconds: Int = 900,  // 15분 기본값
    val permissionMode: PermissionMode = PermissionMode.ACCEPT_EDITS,
    val outputFormat: OutputFormat = OutputFormat.STREAM_JSON,
    val allowedTools: List<String> = emptyList(),
    val deniedTools: List<String> = emptyList()
)

@Serializable
enum class PermissionMode {
    PLAN,           // 읽기만 가능
    ACCEPT_EDITS,   // 편집 자동 승인
    DONT_ASK        // 모든 작업 자동 승인
}

@Serializable
enum class OutputFormat {
    TEXT,
    JSON,
    STREAM,
    STREAM_JSON  // 실시간 도구 호출 로깅용
}
