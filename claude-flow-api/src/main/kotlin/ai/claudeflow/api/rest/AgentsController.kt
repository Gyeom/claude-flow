package ai.claudeflow.api.rest

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.routing.AgentRouter
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * Agents REST API (Read-only)
 *
 * 에이전트 목록 조회만 지원합니다.
 * 에이전트 관리는 코드에서 직접 수행합니다.
 */
@RestController
@RequestMapping("/api/v2/agents")
class AgentsController(
    private val agentRouter: AgentRouter
) {
    @GetMapping
    fun listAgents(): Mono<ResponseEntity<List<AgentListItemDto>>> = mono {
        logger.debug { "List all agents" }
        val agents = agentRouter.listAllAgents()
        ResponseEntity.ok(agents.map { it.toListItemDto() })
    }

    private fun Agent.toListItemDto() = AgentListItemDto(
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
        projectId = projectId
    )
}

/**
 * 에이전트 목록 응답 DTO (v2 API)
 */
data class AgentListItemDto(
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
    val projectId: String?
)
