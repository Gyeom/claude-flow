package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.model.Project
import ai.claudeflow.core.storage.BaseRepository
import ai.claudeflow.core.storage.ConnectionProvider
import ai.claudeflow.core.storage.query.QueryBuilder
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.time.Instant

/**
 * 프로젝트 Repository (멀티테넌시 지원)
 *
 * 프로젝트 기반 멀티테넌시 구현
 */
class ProjectRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<Project, String>(connectionProvider) {

    private val objectMapper = jacksonObjectMapper()

    override val tableName: String = "projects"
    override val primaryKeyColumn: String = "id"

    override fun mapRow(rs: ResultSet): Project {
        return Project(
            id = rs.getString("id"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            workingDirectory = rs.getString("working_directory"),
            gitRemote = rs.getString("git_remote"),
            gitlabPath = rs.getString("gitlab_path"),
            defaultBranch = rs.getString("default_branch") ?: "main",
            isDefault = rs.getInt("is_default") == 1,
            enableUserContext = rs.getInt("enable_user_context") == 1,
            classifyModel = rs.getString("classify_model") ?: "haiku",
            classifyTimeout = rs.getInt("classify_timeout").takeIf { it > 0 } ?: 30,
            rateLimitRpm = rs.getInt("rate_limit_rpm"),
            allowedTools = rs.getString("allowed_tools")?.let {
                objectMapper.readValue(it)
            } ?: emptyList(),
            disallowedTools = rs.getString("disallowed_tools")?.let {
                objectMapper.readValue(it)
            } ?: emptyList(),
            fallbackAgentId = rs.getString("fallback_agent_id") ?: "general",
            aliases = rs.getString("aliases")?.let {
                objectMapper.readValue(it)
            } ?: emptyList(),
            createdAt = rs.getString("created_at"),
            updatedAt = rs.getString("updated_at")
        )
    }

    override fun getId(entity: Project): String = entity.id

    override fun save(entity: Project) {
        val now = Instant.now().toString()
        insert()
            .columns(
                "id" to entity.id,
                "name" to entity.name,
                "description" to entity.description,
                "working_directory" to entity.workingDirectory,
                "git_remote" to entity.gitRemote,
                "gitlab_path" to entity.gitlabPath,
                "default_branch" to entity.defaultBranch,
                "is_default" to if (entity.isDefault) 1 else 0,
                "enable_user_context" to if (entity.enableUserContext) 1 else 0,
                "classify_model" to entity.classifyModel,
                "classify_timeout" to entity.classifyTimeout,
                "rate_limit_rpm" to entity.rateLimitRpm,
                "allowed_tools" to objectMapper.writeValueAsString(entity.allowedTools),
                "disallowed_tools" to objectMapper.writeValueAsString(entity.disallowedTools),
                "fallback_agent_id" to entity.fallbackAgentId,
                "aliases" to objectMapper.writeValueAsString(entity.aliases),
                "created_at" to (entity.createdAt ?: now),
                "updated_at" to now
            )
            .executeOrReplace()
    }

    override fun findAll(): List<Project> {
        return query()
            .select("*")
            .orderBy("is_default", QueryBuilder.SortDirection.DESC)
            .orderBy("name", QueryBuilder.SortDirection.ASC)
            .execute { mapRow(it) }
    }

    /**
     * 기본 프로젝트 조회
     */
    fun findDefault(): Project? {
        return query()
            .select("*")
            .where("is_default = ?", 1)
            .limit(1)
            .execute { mapRow(it) }
            .firstOrNull()
    }

    /**
     * 채널에 매핑된 프로젝트 조회
     */
    fun findByChannel(channel: String): Project? {
        return executeQuery(
            """
            SELECT p.* FROM projects p
            JOIN channel_projects cp ON p.id = cp.project_id
            WHERE cp.channel = ?
            """.trimIndent(),
            channel
        ) { mapRow(it) }.firstOrNull()
    }

    /**
     * 채널에 프로젝트 매핑
     */
    fun mapChannelToProject(channel: String, projectId: String): Boolean {
        return executeUpdate(
            """
            INSERT OR REPLACE INTO channel_projects (channel, project_id)
            VALUES (?, ?)
            """.trimIndent(),
            channel, projectId
        ) > 0
    }

    /**
     * 채널 매핑 해제
     */
    fun unmapChannel(channel: String): Boolean {
        return executeUpdate(
            "DELETE FROM channel_projects WHERE channel = ?",
            channel
        ) > 0
    }

    /**
     * 프로젝트의 채널 목록 조회
     */
    fun getChannelsByProject(projectId: String): List<String> {
        return executeQuery(
            "SELECT channel FROM channel_projects WHERE project_id = ?",
            projectId
        ) { it.getString("channel") }
    }

    /**
     * 기본 프로젝트 설정 (다른 프로젝트는 is_default = false로 변경)
     */
    fun setAsDefault(projectId: String): Boolean {
        // 트랜잭션으로 처리
        val conn = connection
        return try {
            conn.autoCommit = false

            // 모든 프로젝트의 is_default를 0으로
            executeUpdate("UPDATE projects SET is_default = 0")

            // 해당 프로젝트만 is_default = 1
            val updated = executeUpdate(
                "UPDATE projects SET is_default = 1, updated_at = ? WHERE id = ?",
                Instant.now().toString(), projectId
            ) > 0

            conn.commit()
            updated
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /**
     * Rate Limit 설정 업데이트
     */
    fun updateRateLimit(projectId: String, rpm: Int): Boolean {
        return update()
            .set("rate_limit_rpm", rpm)
            .set("updated_at", Instant.now().toString())
            .where("id = ?", projectId)
            .execute() > 0
    }

    /**
     * 프로젝트 통계 조회
     */
    fun getProjectStats(projectId: String): ProjectStats? {
        return executeQuery(
            """
            SELECT
                p.id,
                p.name,
                COUNT(DISTINCT e.id) as total_executions,
                COUNT(DISTINCT e.user_id) as unique_users,
                COUNT(DISTINCT a.id) as agent_count,
                COALESCE(SUM(e.cost), 0) as total_cost,
                COALESCE(AVG(e.duration_ms), 0) as avg_duration_ms
            FROM projects p
            LEFT JOIN executions e ON e.project_id = p.id
            LEFT JOIN agents a ON a.project_id = p.id
            WHERE p.id = ?
            GROUP BY p.id
            """.trimIndent(),
            projectId
        ) {
            ProjectStats(
                projectId = it.getString("id"),
                projectName = it.getString("name"),
                totalExecutions = it.getLong("total_executions"),
                uniqueUsers = it.getLong("unique_users"),
                agentCount = it.getInt("agent_count"),
                totalCost = it.getDouble("total_cost"),
                avgDurationMs = it.getDouble("avg_duration_ms")
            )
        }.firstOrNull()
    }

    /**
     * 프로젝트 삭제 (연관 데이터도 함께 삭제)
     */
    fun deleteWithRelations(projectId: String): Boolean {
        val conn = connection
        return try {
            conn.autoCommit = false

            // 채널 매핑 삭제
            executeUpdate("DELETE FROM channel_projects WHERE project_id = ?", projectId)

            // 프로젝트 소속 에이전트 삭제
            executeUpdate("DELETE FROM agents WHERE project_id = ?", projectId)

            // 프로젝트 삭제
            val deleted = executeUpdate("DELETE FROM projects WHERE id = ?", projectId) > 0

            conn.commit()
            deleted
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }
}

/**
 * 프로젝트 통계
 */
data class ProjectStats(
    val projectId: String,
    val projectName: String,
    val totalExecutions: Long,
    val uniqueUsers: Long,
    val agentCount: Int,
    val totalCost: Double,
    val avgDurationMs: Double
)
