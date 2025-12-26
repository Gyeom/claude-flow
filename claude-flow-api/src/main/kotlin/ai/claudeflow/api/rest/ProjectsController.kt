package ai.claudeflow.api.rest

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.model.Project
import ai.claudeflow.core.registry.ProjectRegistry
import ai.claudeflow.core.storage.Storage
import ai.claudeflow.core.storage.repository.ProjectStats
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * Projects REST API
 *
 * 계층적 URL 패턴:
 * - /api/v1/projects - 프로젝트 CRUD
 * - /api/v1/projects/{projectId}/agents - 프로젝트별 에이전트
 * - /api/v1/projects/{projectId}/channels - 채널 매핑
 * - /api/v1/projects/{projectId}/stats - 프로젝트 통계
 */
@RestController
@RequestMapping("/api/v1/projects")
class ProjectsController(
    private val projectRegistry: ProjectRegistry,
    private val storage: Storage
) {
    // ==================== Project CRUD ====================

    @GetMapping
    fun listProjects(): Mono<ResponseEntity<List<ProjectResponse>>> = mono {
        logger.info { "List all projects" }
        val projects = projectRegistry.listAll()
        ResponseEntity.ok(projects.map { it.toDto() })
    }

    @GetMapping("/{projectId}")
    fun getProject(@PathVariable projectId: String): Mono<ResponseEntity<ProjectResponse>> = mono {
        logger.info { "Get project: $projectId" }
        val project = projectRegistry.get(projectId)
        if (project != null) {
            ResponseEntity.ok(project.toDto())
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping
    fun createProject(@RequestBody request: CreateProjectRequest): Mono<ResponseEntity<ProjectResponse>> = mono {
        logger.info { "Create project: ${request.id}" }
        val project = Project(
            id = request.id,
            name = request.name,
            description = request.description,
            workingDirectory = request.workingDirectory,
            gitRemote = request.gitRemote,
            defaultBranch = request.defaultBranch ?: "main",
            isDefault = request.isDefault ?: false,
            enableUserContext = request.enableUserContext ?: true,
            classifyModel = request.classifyModel ?: "haiku",
            classifyTimeout = request.classifyTimeout ?: 30,
            rateLimitRpm = request.rateLimitRpm ?: 0,
            allowedTools = request.allowedTools ?: emptyList(),
            disallowedTools = request.disallowedTools ?: emptyList(),
            fallbackAgentId = request.fallbackAgentId ?: "general"
        )

        val registered = projectRegistry.register(project)
        if (registered) {
            ResponseEntity.ok(project.toDto())
        } else {
            ResponseEntity.badRequest().build()
        }
    }

    @PatchMapping("/{projectId}")
    fun updateProject(
        @PathVariable projectId: String,
        @RequestBody request: UpdateProjectRequest
    ): Mono<ResponseEntity<ProjectResponse>> = mono {
        logger.info { "Update project: $projectId" }
        val existing = projectRegistry.get(projectId)
        if (existing == null) {
            return@mono ResponseEntity.notFound().build<ProjectResponse>()
        }

        val updated = existing.copy(
            name = request.name ?: existing.name,
            description = request.description ?: existing.description,
            workingDirectory = request.workingDirectory ?: existing.workingDirectory,
            gitRemote = request.gitRemote ?: existing.gitRemote,
            defaultBranch = request.defaultBranch ?: existing.defaultBranch,
            enableUserContext = request.enableUserContext ?: existing.enableUserContext,
            classifyModel = request.classifyModel ?: existing.classifyModel,
            classifyTimeout = request.classifyTimeout ?: existing.classifyTimeout,
            rateLimitRpm = request.rateLimitRpm ?: existing.rateLimitRpm,
            allowedTools = request.allowedTools ?: existing.allowedTools,
            disallowedTools = request.disallowedTools ?: existing.disallowedTools,
            fallbackAgentId = request.fallbackAgentId ?: existing.fallbackAgentId
        )

        projectRegistry.register(updated)
        ResponseEntity.ok(updated.toDto())
    }

    @DeleteMapping("/{projectId}")
    fun deleteProject(@PathVariable projectId: String): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Delete project: $projectId" }
        val removed = projectRegistry.unregister(projectId)
        ResponseEntity.ok(mapOf("success" to removed, "projectId" to projectId))
    }

    @PostMapping("/{projectId}/default")
    fun setDefaultProject(@PathVariable projectId: String): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Set default project: $projectId" }
        val success = projectRegistry.setDefaultProject(projectId)
        ResponseEntity.ok(mapOf("success" to success, "projectId" to projectId))
    }

    // ==================== Project Agents (계층적 라우팅) ====================

    @GetMapping("/{projectId}/agents")
    fun listProjectAgents(@PathVariable projectId: String): Mono<ResponseEntity<List<ProjectAgentResponse>>> = mono {
        logger.info { "List agents for project: $projectId" }
        val project = projectRegistry.get(projectId)
        if (project == null) {
            return@mono ResponseEntity.notFound().build<List<ProjectAgentResponse>>()
        }

        val agents = storage.agentRepository.findByProject(projectId)
        ResponseEntity.ok(agents.map { it.toDto() })
    }

    @GetMapping("/{projectId}/agents/{agentId}")
    fun getProjectAgent(
        @PathVariable projectId: String,
        @PathVariable agentId: String
    ): Mono<ResponseEntity<ProjectAgentResponse>> = mono {
        logger.info { "Get agent $agentId for project $projectId" }
        val agent = storage.agentRepository.findByIdAndProject(agentId, projectId)
        if (agent != null) {
            ResponseEntity.ok(agent.toDto())
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/{projectId}/agents")
    fun createProjectAgent(
        @PathVariable projectId: String,
        @RequestBody request: CreateProjectAgentRequest
    ): Mono<ResponseEntity<ProjectAgentResponse>> = mono {
        logger.info { "Create agent ${request.id} for project $projectId" }

        val project = projectRegistry.get(projectId)
        if (project == null) {
            return@mono ResponseEntity.notFound().build<ProjectAgentResponse>()
        }

        val agent = Agent(
            id = request.id,
            name = request.name,
            description = request.description,
            keywords = request.keywords ?: emptyList(),
            systemPrompt = request.systemPrompt,
            model = request.model ?: "claude-sonnet-4-20250514",
            maxTokens = request.maxTokens ?: 4096,
            allowedTools = request.allowedTools ?: emptyList(),
            workingDirectory = request.workingDirectory ?: project.workingDirectory,
            enabled = request.enabled ?: true,
            priority = request.priority ?: 0,
            examples = request.examples ?: emptyList(),
            projectId = projectId,
            timeout = request.timeout,
            staticResponse = request.staticResponse ?: false,
            isolated = request.isolated ?: false
        )

        storage.agentRepository.save(agent)
        ResponseEntity.ok(agent.toDto())
    }

    @DeleteMapping("/{projectId}/agents/{agentId}")
    fun deleteProjectAgent(
        @PathVariable projectId: String,
        @PathVariable agentId: String
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Delete agent $agentId from project $projectId" }
        val deleted = storage.agentRepository.deleteByIdAndProject(agentId, projectId)
        ResponseEntity.ok(mapOf("success" to deleted, "agentId" to agentId, "projectId" to projectId))
    }

    // ==================== Project Channels ====================

    @GetMapping("/{projectId}/channels")
    fun listProjectChannels(@PathVariable projectId: String): Mono<ResponseEntity<List<String>>> = mono {
        logger.info { "List channels for project: $projectId" }
        val channels = projectRegistry.getProjectChannels(projectId)
        ResponseEntity.ok(channels)
    }

    @PostMapping("/{projectId}/channels")
    fun mapChannel(
        @PathVariable projectId: String,
        @RequestBody request: ChannelMappingRequest
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Map channel ${request.channel} to project $projectId" }
        val success = projectRegistry.setChannelProject(request.channel, projectId)
        ResponseEntity.ok(mapOf(
            "success" to success,
            "channel" to request.channel,
            "projectId" to projectId
        ))
    }

    @DeleteMapping("/{projectId}/channels/{channel}")
    fun unmapChannel(
        @PathVariable projectId: String,
        @PathVariable channel: String
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Unmap channel $channel from project $projectId" }
        projectRegistry.clearChannelProject(channel)
        ResponseEntity.ok(mapOf("success" to true, "channel" to channel))
    }

    // ==================== Project Stats ====================

    @GetMapping("/{projectId}/stats")
    fun getProjectStats(@PathVariable projectId: String): Mono<ResponseEntity<ProjectStatsResponse>> = mono {
        logger.info { "Get stats for project: $projectId" }
        val stats = projectRegistry.getProjectStats(projectId)
        if (stats != null) {
            ResponseEntity.ok(stats.toDto())
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ==================== Rate Limit ====================

    @PatchMapping("/{projectId}/rate-limit")
    fun updateRateLimit(
        @PathVariable projectId: String,
        @RequestBody request: RateLimitRequest
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Update rate limit for project $projectId: ${request.rpm} RPM" }
        val success = projectRegistry.updateRateLimit(projectId, request.rpm)
        ResponseEntity.ok(mapOf("success" to success, "projectId" to projectId, "rpm" to request.rpm))
    }

    // ==================== Extension Functions ====================

    private fun Project.toDto() = ProjectResponse(
        id = id,
        name = name,
        description = description,
        workingDirectory = workingDirectory,
        gitRemote = gitRemote,
        gitlabPath = gitlabPath,
        defaultBranch = defaultBranch,
        isDefault = isDefault,
        enableUserContext = enableUserContext,
        classifyModel = classifyModel,
        classifyTimeout = classifyTimeout,
        rateLimitRpm = rateLimitRpm,
        allowedTools = allowedTools,
        disallowedTools = disallowedTools,
        fallbackAgentId = fallbackAgentId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun Agent.toDto() = ProjectAgentResponse(
        id = id,
        name = name,
        description = description,
        keywords = keywords,
        systemPrompt = systemPrompt,
        model = model,
        maxTokens = maxTokens,
        allowedTools = allowedTools,
        workingDirectory = workingDirectory,
        enabled = enabled,
        priority = priority,
        examples = examples,
        projectId = projectId,
        timeout = timeout,
        staticResponse = staticResponse,
        isolated = isolated
    )

    private fun ProjectStats.toDto() = ProjectStatsResponse(
        projectId = projectId,
        projectName = projectName,
        totalExecutions = totalExecutions,
        uniqueUsers = uniqueUsers,
        agentCount = agentCount,
        totalCost = totalCost,
        avgDurationMs = avgDurationMs
    )
}

// ==================== DTOs ====================

data class ProjectResponse(
    val id: String,
    val name: String,
    val description: String?,
    val workingDirectory: String,
    val gitRemote: String?,
    val gitlabPath: String?,
    val defaultBranch: String,
    val isDefault: Boolean,
    val enableUserContext: Boolean,
    val classifyModel: String,
    val classifyTimeout: Int,
    val rateLimitRpm: Int,
    val allowedTools: List<String>,
    val disallowedTools: List<String>,
    val fallbackAgentId: String,
    val createdAt: String?,
    val updatedAt: String?
)

data class CreateProjectRequest(
    val id: String,
    val name: String,
    val description: String? = null,
    val workingDirectory: String,
    val gitRemote: String? = null,
    val defaultBranch: String? = null,
    val isDefault: Boolean? = null,
    val enableUserContext: Boolean? = null,
    val classifyModel: String? = null,
    val classifyTimeout: Int? = null,
    val rateLimitRpm: Int? = null,
    val allowedTools: List<String>? = null,
    val disallowedTools: List<String>? = null,
    val fallbackAgentId: String? = null
)

data class UpdateProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val workingDirectory: String? = null,
    val gitRemote: String? = null,
    val defaultBranch: String? = null,
    val enableUserContext: Boolean? = null,
    val classifyModel: String? = null,
    val classifyTimeout: Int? = null,
    val rateLimitRpm: Int? = null,
    val allowedTools: List<String>? = null,
    val disallowedTools: List<String>? = null,
    val fallbackAgentId: String? = null
)

data class ProjectAgentResponse(
    val id: String,
    val name: String,
    val description: String,
    val keywords: List<String>,
    val systemPrompt: String,
    val model: String,
    val maxTokens: Int,
    val allowedTools: List<String>,
    val workingDirectory: String?,
    val enabled: Boolean,
    val priority: Int,
    val examples: List<String>,
    val projectId: String?,
    val timeout: Int?,
    val staticResponse: Boolean,
    val isolated: Boolean
)

data class CreateProjectAgentRequest(
    val id: String,
    val name: String,
    val description: String,
    val keywords: List<String>? = null,
    val systemPrompt: String,
    val model: String? = null,
    val maxTokens: Int? = null,
    val allowedTools: List<String>? = null,
    val workingDirectory: String? = null,
    val enabled: Boolean? = null,
    val priority: Int? = null,
    val examples: List<String>? = null,
    val timeout: Int? = null,
    val staticResponse: Boolean? = null,
    val isolated: Boolean? = null
)

data class ChannelMappingRequest(
    val channel: String
)

data class RateLimitRequest(
    val rpm: Int
)

data class ProjectStatsResponse(
    val projectId: String,
    val projectName: String,
    val totalExecutions: Long,
    val uniqueUsers: Long,
    val agentCount: Int,
    val totalCost: Double,
    val avgDurationMs: Double
)
