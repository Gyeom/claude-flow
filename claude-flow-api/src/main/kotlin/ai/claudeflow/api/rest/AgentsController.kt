package ai.claudeflow.api.rest

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.routing.AgentRouter
import ai.claudeflow.core.routing.AgentUpdate
import ai.claudeflow.core.storage.Storage
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * Agents REST API
 */
@RestController
@RequestMapping("/api/v2/agents")
@CrossOrigin(origins = ["http://localhost:3000", "http://localhost:3001"])
class AgentsController(
    private val agentRouter: AgentRouter,
    private val storage: Storage
) {
    @GetMapping
    fun listAgents(@RequestParam projectId: String?): Mono<ResponseEntity<List<AgentFullDto>>> = mono {
        logger.info { "List agents for project: ${projectId ?: "all"}" }
        val agents = if (projectId != null) {
            storage.getAgentsByProject(projectId)
        } else {
            agentRouter.listAllAgents()
        }
        ResponseEntity.ok(agents.map { it.toFullDto() })
    }

    @GetMapping("/{agentId}")
    fun getAgent(
        @PathVariable agentId: String,
        @RequestParam projectId: String?
    ): Mono<ResponseEntity<AgentFullDto>> = mono {
        logger.info { "Get agent: $agentId (project: ${projectId ?: "global"})" }
        val agent = storage.getAgent(agentId, projectId) ?: agentRouter.getAgent(agentId)
        if (agent != null) {
            ResponseEntity.ok(agent.toFullDto())
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping
    fun createAgent(@RequestBody request: CreateAgentFullRequest): Mono<ResponseEntity<AgentFullDto>> = mono {
        logger.info { "Create agent: ${request.id}" }
        val agent = Agent(
            id = request.id,
            name = request.name,
            description = request.description,
            keywords = request.keywords,
            systemPrompt = request.systemPrompt,
            model = request.model ?: "claude-sonnet-4-20250514",
            maxTokens = request.maxTokens ?: 4096,
            allowedTools = request.allowedTools ?: emptyList(),
            workingDirectory = request.workingDirectory,
            enabled = request.enabled ?: true,
            priority = request.priority ?: 0,
            examples = request.examples ?: emptyList(),
            projectId = request.projectId
        )
        val added = agentRouter.addAgent(agent)
        if (!added) {
            return@mono ResponseEntity.badRequest().build<AgentFullDto>()
        }
        storage.saveAgent(agent)
        ResponseEntity.ok(agent.toFullDto())
    }

    @PatchMapping("/{agentId}")
    fun updateAgent(
        @PathVariable agentId: String,
        @RequestBody request: UpdateAgentFullRequest
    ): Mono<ResponseEntity<AgentFullDto>> = mono {
        logger.info { "Update agent: $agentId" }
        val update = AgentUpdate(
            name = request.name,
            description = request.description,
            keywords = request.keywords,
            systemPrompt = request.systemPrompt,
            model = request.model,
            allowedTools = request.allowedTools,
            workingDirectory = request.workingDirectory,
            enabled = request.enabled,
            priority = request.priority,
            examples = request.examples,
            projectId = request.projectId
        )
        val updated = agentRouter.updateAgent(agentId, update)
        if (!updated) {
            return@mono ResponseEntity.notFound().build<AgentFullDto>()
        }
        val agent = agentRouter.getAgent(agentId)
        if (agent != null) {
            storage.saveAgent(agent)
            ResponseEntity.ok(agent.toFullDto())
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping("/{agentId}")
    fun deleteAgent(
        @PathVariable agentId: String,
        @RequestParam projectId: String?
    ): Mono<ResponseEntity<Map<String, Any>>> = mono {
        logger.info { "Delete agent: $agentId" }
        val removed = agentRouter.removeAgent(agentId)
        if (removed) {
            storage.deleteAgent(agentId, projectId)
        }
        ResponseEntity.ok(mapOf("success" to removed, "agentId" to agentId))
    }

    @PatchMapping("/{agentId}/enabled")
    fun setAgentEnabled(
        @PathVariable agentId: String,
        @RequestBody request: AgentEnabledRequest
    ): Mono<ResponseEntity<AgentFullDto>> = mono {
        logger.info { "Set agent $agentId enabled: ${request.enabled}" }
        val success = agentRouter.setAgentEnabled(agentId, request.enabled)
        if (!success) {
            return@mono ResponseEntity.notFound().build<AgentFullDto>()
        }
        val agent = agentRouter.getAgent(agentId) ?: agentRouter.listAllAgents().find { it.id == agentId }
        if (agent != null) {
            storage.setAgentEnabled(agentId, agent.projectId, request.enabled)
            ResponseEntity.ok(agent.toFullDto())
        } else {
            ResponseEntity.notFound().build()
        }
    }

    private fun Agent.toFullDto() = AgentFullDto(
        id = id, name = name, description = description, keywords = keywords,
        systemPrompt = systemPrompt, model = model, maxTokens = maxTokens,
        allowedTools = allowedTools, workingDirectory = workingDirectory,
        enabled = enabled, priority = priority, examples = examples, projectId = projectId
    )
}

data class AgentFullDto(
    val id: String, val name: String, val description: String, val keywords: List<String>,
    val systemPrompt: String, val model: String, val maxTokens: Int,
    val allowedTools: List<String>, val workingDirectory: String?, val enabled: Boolean,
    val priority: Int, val examples: List<String>, val projectId: String?
)

data class CreateAgentFullRequest(
    val id: String, val name: String, val description: String, val keywords: List<String>,
    val systemPrompt: String, val model: String? = null, val maxTokens: Int? = null,
    val allowedTools: List<String>? = null, val workingDirectory: String? = null,
    val enabled: Boolean? = null, val priority: Int? = null, val examples: List<String>? = null,
    val projectId: String? = null
)

data class UpdateAgentFullRequest(
    val name: String? = null, val description: String? = null, val keywords: List<String>? = null,
    val systemPrompt: String? = null, val model: String? = null, val maxTokens: Int? = null,
    val allowedTools: List<String>? = null, val workingDirectory: String? = null,
    val enabled: Boolean? = null, val priority: Int? = null, val examples: List<String>? = null,
    val projectId: String? = null
)

data class AgentEnabledRequest(val enabled: Boolean)
