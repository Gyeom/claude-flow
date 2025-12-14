package ai.claudeflow.core.model

import kotlinx.serialization.Serializable

/**
 * 프로젝트 설정
 *
 * 각 프로젝트(리포지토리)별로 독립적인 설정을 가짐
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val description: String? = null,
    val workingDirectory: String,
    val gitRemote: String? = null,
    val defaultBranch: String = "main",
    val agents: List<Agent> = listOf(Agent.GENERAL),
    val defaultAgent: String = "general",
    val claudeConfig: ClaudeConfig = ClaudeConfig()
)

/**
 * Claude CLI 실행 설정
 */
@Serializable
data class ClaudeConfig(
    val model: String = "claude-sonnet-4-20250514",
    val maxTokens: Int = 4096,
    val timeoutSeconds: Int = 300,
    val permissionMode: PermissionMode = PermissionMode.ACCEPT_EDITS,
    val outputFormat: OutputFormat = OutputFormat.JSON,
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
    STREAM
}
