package ai.claudeflow.api.rest

import ai.claudeflow.core.plugin.PluginManager
import ai.claudeflow.core.plugin.PluginResult
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * 플러그인 관리 REST API
 */
@RestController
@RequestMapping("/api/v1/plugins")
class PluginController(
    private val pluginManager: PluginManager
) {
    /**
     * 플러그인 목록 조회
     */
    @GetMapping
    fun listPlugins(): Mono<ResponseEntity<List<PluginInfoDto>>> = mono {
        val plugins = pluginManager.getPluginInfo().map { info ->
            PluginInfoDto(
                id = info.id,
                name = info.name,
                description = info.description,
                enabled = info.enabled,
                commands = info.commands
            )
        }
        ResponseEntity.ok(plugins)
    }

    /**
     * 플러그인 상세 조회
     */
    @GetMapping("/{pluginId}")
    fun getPlugin(@PathVariable pluginId: String): Mono<ResponseEntity<PluginDetailDto>> = mono {
        val plugin = pluginManager.get(pluginId)
        if (plugin != null) {
            ResponseEntity.ok(PluginDetailDto(
                id = plugin.id,
                name = plugin.name,
                description = plugin.description,
                enabled = plugin.enabled,
                commands = plugin.commands.map { cmd ->
                    PluginCommandDto(
                        name = cmd.name,
                        description = cmd.description,
                        usage = cmd.usage,
                        examples = cmd.examples
                    )
                }
            ))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * 플러그인 명령어 실행
     */
    @PostMapping("/{pluginId}/execute")
    fun executePlugin(
        @PathVariable pluginId: String,
        @RequestBody request: PluginExecuteRequest
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        logger.info { "Execute plugin: $pluginId.${request.command}" }

        val result = pluginManager.execute(pluginId, request.command, request.args)

        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * 플러그인 활성화/비활성화
     */
    @PatchMapping("/{pluginId}/enabled")
    fun setPluginEnabled(
        @PathVariable pluginId: String,
        @RequestBody request: SetEnabledRequest
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        val plugin = pluginManager.get(pluginId)
        if (plugin != null) {
            plugin.enabled = request.enabled
            logger.info { "Plugin $pluginId enabled: ${request.enabled}" }
            ResponseEntity.ok(mapOf(
                "success" to true,
                "pluginId" to pluginId,
                "enabled" to request.enabled
            ))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ==================== GitLab 플러그인 전용 API ====================

    /**
     * GitLab MR 목록 조회
     */
    @GetMapping("/gitlab/mrs")
    fun listGitLabMRs(@RequestParam project: String?): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val args: Map<String, Any> = if (project != null) mapOf("project" to project) else emptyMap()
        val result = pluginManager.execute("gitlab", "mr-list", args)
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * GitLab MR 상세 조회
     */
    @GetMapping("/gitlab/mrs/{project}/{mrId}")
    fun getGitLabMR(
        @PathVariable project: String,
        @PathVariable mrId: Int
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("gitlab", "mr-info", mapOf(
            "project" to project,
            "mr_id" to mrId
        ))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * GitLab 파이프라인 상태 조회
     */
    @GetMapping("/gitlab/pipelines/{project}")
    fun getGitLabPipelines(@PathVariable project: String): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("gitlab", "pipeline-status", mapOf("project" to project))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    // ==================== Jira 플러그인 전용 API ====================

    /**
     * Jira 이슈 조회
     */
    @GetMapping("/jira/issues/{issueKey}")
    fun getJiraIssue(@PathVariable issueKey: String): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "issue", mapOf("issue_key" to issueKey))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 내 이슈 목록
     */
    @GetMapping("/jira/my-issues")
    fun getMyJiraIssues(): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "my-issues", emptyMap())
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 스프린트 이슈
     */
    @GetMapping("/jira/sprint")
    fun getJiraSprintIssues(@RequestParam boardId: Int?): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val args: Map<String, Any> = if (boardId != null) mapOf("board_id" to boardId) else emptyMap()
        val result = pluginManager.execute("jira", "sprint", args)
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 이슈 검색
     */
    @GetMapping("/jira/search")
    fun searchJiraIssues(@RequestParam jql: String): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "search", mapOf("jql" to jql))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 이슈 상태 변경
     */
    @PostMapping("/jira/issues/{issueKey}/transition")
    fun transitionJiraIssue(
        @PathVariable issueKey: String,
        @RequestBody request: JiraTransitionRequest
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "transition", mapOf(
            "issue_key" to issueKey,
            "status" to request.status
        ))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }
}

// DTOs
data class PluginInfoDto(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val commands: List<String>
)

data class PluginDetailDto(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val commands: List<PluginCommandDto>
)

data class PluginCommandDto(
    val name: String,
    val description: String,
    val usage: String,
    val examples: List<String>
)

data class PluginExecuteRequest(
    val command: String,
    val args: Map<String, Any> = emptyMap()
)

data class PluginExecuteResponse(
    val success: Boolean,
    val data: Any? = null,
    val message: String? = null,
    val error: String? = null
)

data class SetEnabledRequest(
    val enabled: Boolean
)

data class JiraTransitionRequest(
    val status: String
)
