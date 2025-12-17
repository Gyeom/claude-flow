package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.storage.BaseRepository
import ai.claudeflow.core.storage.ConnectionProvider
import ai.claudeflow.core.storage.DateRange
import ai.claudeflow.core.storage.query.QueryBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mu.KotlinLogging
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Repository for agent management
 * 확장 지원 (timeout, staticResponse, outputSchema, isolated)
 */
class AgentRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<Agent, String>(connectionProvider) {

    private val json = Json { ignoreUnknownKeys = true }

    override val tableName: String = "agents"
    override val primaryKeyColumn: String = "id"

    override fun mapRow(rs: ResultSet): Agent {
        val projectIdValue = rs.getString("project_id")
        val outputSchemaStr = rs.getString("output_schema")

        return Agent(
            id = rs.getString("id"),
            name = rs.getString("name"),
            description = rs.getString("description") ?: "",
            keywords = rs.getString("keywords")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            systemPrompt = rs.getString("system_prompt"),
            model = rs.getString("model") ?: "claude-sonnet-4-20250514",
            maxTokens = rs.getInt("max_tokens"),
            allowedTools = rs.getString("allowed_tools")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            workingDirectory = rs.getString("working_directory"),
            enabled = rs.getInt("enabled") == 1,
            priority = rs.getInt("priority"),
            examples = rs.getString("examples")?.split("|||")?.filter { it.isNotBlank() } ?: emptyList(),
            projectId = if (projectIdValue == "global") null else projectIdValue,
            // 확장 필드
            timeout = rs.getInt("timeout").takeIf { !rs.wasNull() && it > 0 },
            staticResponse = rs.getInt("static_response") == 1,
            outputSchema = outputSchemaStr?.let {
                try { json.parseToJsonElement(it) } catch (e: Exception) { null }
            },
            isolated = rs.getInt("isolated") == 1,
            createdAt = rs.getString("created_at"),
            updatedAt = rs.getString("updated_at")
        )
    }

    override fun getId(entity: Agent): String = entity.id

    override fun save(entity: Agent) {
        val now = Instant.now().toString()
        insert()
            .columns(
                "id" to entity.id,
                "project_id" to (entity.projectId ?: "global"),
                "name" to entity.name,
                "description" to entity.description,
                "keywords" to entity.keywords.joinToString(","),
                "system_prompt" to entity.systemPrompt,
                "model" to entity.model,
                "max_tokens" to entity.maxTokens,
                "allowed_tools" to entity.allowedTools.joinToString(","),
                "working_directory" to entity.workingDirectory,
                "enabled" to (if (entity.enabled) 1 else 0),
                "priority" to entity.priority,
                "examples" to entity.examples.joinToString("|||"),
                // 확장 필드
                "timeout" to entity.timeout,
                "static_response" to (if (entity.staticResponse) 1 else 0),
                "output_schema" to entity.outputSchema?.toString(),
                "isolated" to (if (entity.isolated) 1 else 0),
                "created_at" to (entity.createdAt ?: now),
                "updated_at" to now
            )
            .executeOrReplace()
        logger.info { "Saved agent: ${entity.id} (project: ${entity.projectId ?: "global"})" }
    }

    /**
     * Find agent by ID and project
     */
    fun findByIdAndProject(agentId: String, projectId: String? = null): Agent? {
        return query()
            .select("*")
            .where("id = ?", agentId)
            .where("project_id = ?", projectId ?: "global")
            .executeOne { mapRow(it) }
    }

    /**
     * Find all agents for a specific project
     */
    fun findByProject(projectId: String? = null): List<Agent> {
        return query()
            .select("*")
            .where("project_id = ?", projectId ?: "global")
            .orderBy("priority", QueryBuilder.SortDirection.DESC)
            .execute { mapRow(it) }
    }

    /**
     * Find all enabled agents for a project
     */
    fun findEnabledByProject(projectId: String? = null): List<Agent> {
        return query()
            .select("*")
            .where("project_id = ?", projectId ?: "global")
            .where("enabled = ?", 1)
            .orderBy("priority", QueryBuilder.SortDirection.DESC)
            .execute { mapRow(it) }
    }

    /**
     * Find all agents ordered by project and priority
     */
    override fun findAll(): List<Agent> {
        return query()
            .select("*")
            .orderBy("project_id" to QueryBuilder.SortDirection.ASC, "priority" to QueryBuilder.SortDirection.DESC)
            .execute { mapRow(it) }
    }

    /**
     * Delete agent by ID and project
     */
    fun deleteByIdAndProject(agentId: String, projectId: String? = null): Boolean {
        val deleted = delete()
            .where("id = ?", agentId)
            .where("project_id = ?", projectId ?: "global")
            .execute() > 0

        if (deleted) {
            logger.info { "Deleted agent: $agentId (project: ${projectId ?: "global"})" }
        }
        return deleted
    }

    /**
     * Set agent enabled status
     */
    fun setEnabled(agentId: String, projectId: String?, enabled: Boolean): Boolean {
        return update()
            .set("enabled", if (enabled) 1 else 0)
            .set("updated_at", Instant.now().toString())
            .where("id = ?", agentId)
            .where("project_id = ?", projectId ?: "global")
            .execute() > 0
    }

    /**
     * Update agent priority
     */
    fun updatePriority(agentId: String, projectId: String?, priority: Int): Boolean {
        return update()
            .set("priority", priority)
            .set("updated_at", Instant.now().toString())
            .where("id = ?", agentId)
            .where("project_id = ?", projectId ?: "global")
            .execute() > 0
    }

    /**
     * Get model statistics from executions
     */
    fun getModelStats(dateRange: DateRange): List<ModelStats> {
        // Get total count first
        val total = executeQueryOne(
            "SELECT COUNT(*) FROM executions WHERE created_at BETWEEN ? AND ?",
            dateRange.from.toString(), dateRange.to.toString()
        ) { it.getLong(1) } ?: 0L

        return executeQuery(
            """
            SELECT
                COALESCE(
                    (SELECT model FROM agents WHERE id = e.agent_id LIMIT 1),
                    'claude-sonnet-4-20250514'
                ) as model,
                COUNT(*) as requests,
                COALESCE(SUM(cost), 0) as cost,
                SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) as successful,
                COALESCE(AVG(duration_ms), 0) as avg_duration
            FROM executions e
            WHERE created_at BETWEEN ? AND ?
            GROUP BY model
            ORDER BY requests DESC
            """.trimIndent(),
            dateRange.from.toString(), dateRange.to.toString()
        ) {
            val requests = it.getLong("requests")
            val successful = it.getLong("successful")
            ModelStats(
                model = it.getString("model"),
                requests = requests,
                percentage = if (total > 0) (requests.toDouble() / total) * 100 else 0.0,
                cost = it.getDouble("cost"),
                successRate = if (requests > 0) successful.toDouble() / requests else 0.0,
                avgDurationMs = it.getLong("avg_duration")
            )
        }
    }

    /**
     * Find agents by keyword
     */
    fun findByKeyword(keyword: String): List<Agent> {
        return executeQuery(
            """
            SELECT * FROM agents
            WHERE enabled = 1 AND keywords LIKE ?
            ORDER BY priority DESC
            """.trimIndent(),
            "%$keyword%"
        ) { mapRow(it) }
    }

    /**
     * Count agents by project
     */
    fun countByProject(projectId: String? = null): Long {
        return countWhere("project_id = ?", projectId ?: "global")
    }

    /**
     * Count enabled agents
     */
    fun countEnabled(): Long {
        return countWhere("enabled = ?", 1)
    }
}

data class ModelStats(
    val model: String,
    val requests: Long,
    val percentage: Double,
    val cost: Double,
    val successRate: Double,
    val avgDurationMs: Long
)
