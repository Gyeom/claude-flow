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

    /**
     * GitLab MR 노트(코멘트) 목록 조회
     */
    @GetMapping("/gitlab/mrs/{project}/{mrId}/notes")
    fun getGitLabMRNotes(
        @PathVariable project: String,
        @PathVariable mrId: Int
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("gitlab", "mr-notes", mapOf(
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
     * GitLab 노트 이모지(Award Emoji) 조회
     */
    @GetMapping("/gitlab/mrs/{project}/{mrId}/notes/{noteId}/emojis")
    fun getGitLabNoteEmojis(
        @PathVariable project: String,
        @PathVariable mrId: Int,
        @PathVariable noteId: Int
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("gitlab", "note-emojis", mapOf(
            "project" to project,
            "mr_id" to mrId,
            "note_id" to noteId
        ))
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
    fun getJiraSprintIssues(
        @RequestParam boardId: Int?,
        @RequestParam sprintId: Int?
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val args = mutableMapOf<String, Any>()
        if (boardId != null) args["board_id"] = boardId
        if (sprintId != null) args["sprint_id"] = sprintId
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
     * Jira 이슈에 사용 가능한 트랜지션 목록 조회
     */
    @GetMapping("/jira/issues/{issueKey}/transitions")
    fun getJiraIssueTransitions(
        @PathVariable issueKey: String
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "get_transitions", mapOf("issue_key" to issueKey))
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
        val args = mutableMapOf<String, Any>(
            "issue_key" to issueKey,
            "status" to request.status
        )
        request.dueDate?.let { args["due_date"] = it }
        request.startDate?.let { args["start_date"] = it }

        val result = pluginManager.execute("jira", "transition", args)
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 이슈 생성
     */
    @PostMapping("/jira/issues")
    fun createJiraIssue(@RequestBody request: JiraCreateIssueRequest): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val args = mutableMapOf<String, Any>(
            "project" to request.project,
            "summary" to request.summary
        )
        request.description?.let { args["description"] = it }
        request.issueType?.let { args["issue_type"] = it }
        request.priority?.let { args["priority"] = it }
        request.parentIssue?.let { args["parent"] = it }
        request.epicLink?.let { args["epic_link"] = it }
        request.assignee?.let { args["assignee"] = it }
        request.reporter?.let { args["reporter"] = it }
        request.labels?.let { if (it.isNotEmpty()) args["labels"] = it }
        request.components?.let { if (it.isNotEmpty()) args["components"] = it }
        request.storyPoints?.let { args["story_points"] = it }
        request.originalEstimate?.let { args["original_estimate"] = it }
        request.startDate?.let { args["start_date"] = it }
        request.dueDate?.let { args["due_date"] = it }
        request.sprintId?.let { args["sprint_id"] = it }

        val result = pluginManager.execute("jira", "create", args)
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 이슈 댓글 추가
     */
    @PostMapping("/jira/issues/{issueKey}/comments")
    fun addJiraComment(
        @PathVariable issueKey: String,
        @RequestBody request: JiraCommentRequest
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "comment", mapOf(
            "issue_key" to issueKey,
            "comment" to request.comment
        ))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 이슈 댓글 조회
     */
    @GetMapping("/jira/issues/{issueKey}/comments")
    fun getJiraComments(@PathVariable issueKey: String): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "comments", mapOf("issue_key" to issueKey))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 이슈 담당자 변경
     */
    @PutMapping("/jira/issues/{issueKey}/assignee")
    fun assignJiraIssue(
        @PathVariable issueKey: String,
        @RequestBody request: JiraAssignRequest
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "assign", mapOf(
            "issue_key" to issueKey,
            "assignee" to request.assignee
        ))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 이슈 라벨 관리
     */
    @PostMapping("/jira/issues/{issueKey}/labels")
    fun manageJiraLabels(
        @PathVariable issueKey: String,
        @RequestBody request: JiraLabelRequest
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "labels", mapOf(
            "issue_key" to issueKey,
            "action" to request.action,
            "label" to request.label
        ))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 이슈 링크 생성
     */
    @PostMapping("/jira/issues/{issueKey}/links")
    fun linkJiraIssues(
        @PathVariable issueKey: String,
        @RequestBody request: JiraLinkRequest
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "link", mapOf(
            "issue_key" to issueKey,
            "link_type" to request.linkType,
            "target_issue" to request.targetIssue
        ))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 프로젝트 목록
     */
    @GetMapping("/jira/projects")
    fun listJiraProjects(): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "projects", emptyMap())
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 보드 목록
     */
    @GetMapping("/jira/boards")
    fun listJiraBoards(@RequestParam projectKey: String?): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val args: Map<String, Any> = if (projectKey != null) mapOf("project_key" to projectKey) else emptyMap()
        val result = pluginManager.execute("jira", "boards", args)
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 보드의 스프린트 목록
     */
    @GetMapping("/jira/boards/{boardId}/sprints")
    fun listJiraSprints(@PathVariable boardId: Int): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "sprints", mapOf("board_id" to boardId))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 이슈의 스프린트 설정/변경
     * - sprintId: 스프린트 ID 또는 "backlog" (백로그로 이동)
     */
    @PutMapping("/jira/issues/{issueKey}/sprint")
    fun setJiraIssueSprint(
        @PathVariable issueKey: String,
        @RequestBody request: JiraSetSprintRequest
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "set_sprint", mapOf(
            "issue_key" to issueKey,
            "sprint_id" to request.sprintId
        ))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 사용자 검색
     */
    @GetMapping("/jira/users/search")
    fun searchJiraUsers(
        @RequestParam query: String,
        @RequestParam(required = false) projectKey: String?
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val args = mutableMapOf<String, Any>("query" to query)
        if (projectKey != null) {
            args["project_key"] = projectKey
        }
        val result = pluginManager.execute("jira", "search_users", args)
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 프로젝트에 할당 가능한 사용자 목록
     */
    @GetMapping("/jira/projects/{projectKey}/users")
    fun getJiraProjectUsers(
        @PathVariable projectKey: String
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "search_users", mapOf(
            "query" to "",
            "project_key" to projectKey
        ))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    // ============================================================
    // 트랜지션 사전 검증 및 MR 링크 관련 API
    // ============================================================

    /**
     * Jira 이슈의 연결된 링크 조회 (Issue Links + Remote Links)
     */
    @GetMapping("/jira/issues/{issueKey}/links")
    fun getJiraIssueLinks(
        @PathVariable issueKey: String
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "get_issue_links", mapOf("issue_key" to issueKey))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * Jira 이슈에 웹 링크(Remote Link) 추가
     * GitLab MR, GitHub PR 등 외부 URL 연결
     */
    @PostMapping("/jira/issues/{issueKey}/remote-links")
    fun addJiraRemoteLink(
        @PathVariable issueKey: String,
        @RequestBody request: JiraRemoteLinkRequest
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "add_remote_link", mapOf(
            "issue_key" to issueKey,
            "url" to request.url,
            "title" to request.title
        ))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * 트랜지션 실행 가능 여부 사전 검증
     * MR 링크 필요 여부, 필수 필드 등 확인
     */
    @GetMapping("/jira/issues/{issueKey}/transitions/{transitionId}/requirements")
    fun checkTransitionRequirements(
        @PathVariable issueKey: String,
        @PathVariable transitionId: String
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val result = pluginManager.execute("jira", "check_transition_requirements", mapOf(
            "issue_key" to issueKey,
            "transition_id" to transitionId
        ))
        ResponseEntity.ok(PluginExecuteResponse(
            success = result.success,
            data = result.data,
            message = result.message,
            error = result.error
        ))
    }

    /**
     * GitLab에서 Jira 이슈 키로 MR 검색
     */
    @GetMapping("/gitlab/mrs/search")
    fun searchGitLabMRsByIssueKey(
        @RequestParam issueKey: String,
        @RequestParam(required = false) project: String?
    ): Mono<ResponseEntity<PluginExecuteResponse>> = mono {
        val args = mutableMapOf<String, Any>("issue_key" to issueKey)
        project?.let { args["project"] = it }

        val result = pluginManager.execute("gitlab", "search-mrs-by-issue", args)
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
    val status: String,
    val dueDate: String? = null,      // 기한 (yyyy-MM-dd 형식)
    val startDate: String? = null     // 시작일 (yyyy-MM-dd 형식)
)

data class JiraCreateIssueRequest(
    val project: String,
    val summary: String,
    val description: String? = null,
    val issueType: String? = "Task",
    val priority: String? = null,
    val parentIssue: String? = null,
    val epicLink: String? = null,
    val assignee: String? = null,
    val reporter: String? = null,
    val labels: List<String>? = null,
    val components: List<String>? = null,
    val storyPoints: Int? = null,
    val originalEstimate: String? = null,
    val startDate: String? = null,
    val dueDate: String? = null,
    val sprintId: Int? = null
)

data class JiraCommentRequest(
    val comment: String
)

data class JiraAssignRequest(
    val assignee: String
)

data class JiraLabelRequest(
    val action: String,  // "add" or "remove"
    val label: String
)

data class JiraLinkRequest(
    val linkType: String,  // "blocks", "relates", "duplicates" etc.
    val targetIssue: String
)

data class JiraRemoteLinkRequest(
    val url: String,       // 외부 URL (GitLab MR, GitHub PR 등)
    val title: String      // 링크 제목 (예: "MR: Fix login bug")
)

data class JiraSetSprintRequest(
    val sprintId: String   // 스프린트 ID (숫자) 또는 "backlog" (백로그로 이동)
)
