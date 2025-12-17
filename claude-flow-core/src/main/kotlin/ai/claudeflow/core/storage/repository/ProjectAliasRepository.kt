package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.BaseRepository
import ai.claudeflow.core.storage.ConnectionProvider
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.time.Instant

/**
 * 프로젝트 별칭 Repository
 *
 * 프로젝트 ID와 키워드 패턴을 매핑하여 자연어에서 프로젝트를 탐지합니다.
 */
class ProjectAliasRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<ProjectAliasEntity, String>(connectionProvider) {

    private val objectMapper = jacksonObjectMapper()

    override val tableName: String = "project_aliases"
    override val primaryKeyColumn: String = "project_id"

    override fun mapRow(rs: ResultSet): ProjectAliasEntity {
        return ProjectAliasEntity(
            projectId = rs.getString("project_id"),
            patterns = rs.getString("patterns")?.let {
                objectMapper.readValue(it)
            } ?: emptyList(),
            description = rs.getString("description"),
            createdAt = rs.getString("created_at"),
            updatedAt = rs.getString("updated_at")
        )
    }

    override fun getId(entity: ProjectAliasEntity): String = entity.projectId

    override fun save(entity: ProjectAliasEntity) {
        val now = Instant.now().toString()
        insert()
            .columns(
                "project_id" to entity.projectId,
                "patterns" to objectMapper.writeValueAsString(entity.patterns),
                "description" to entity.description,
                "created_at" to (entity.createdAt ?: now),
                "updated_at" to now
            )
            .executeOrReplace()
    }

    /**
     * 프로젝트 ID로 별칭 조회
     */
    fun findByProjectId(projectId: String): ProjectAliasEntity? {
        return findById(projectId)
    }

    /**
     * 모든 별칭을 Map 형태로 조회 (호환성)
     */
    fun findAllAsMap(): Map<String, ProjectAliasDto> {
        return findAll().associate { entity ->
            entity.projectId to ProjectAliasDto(
                patterns = entity.patterns,
                description = entity.description ?: ""
            )
        }
    }

    /**
     * 별칭 삭제
     */
    fun deleteByProjectId(projectId: String): Boolean {
        return deleteById(projectId)
    }

    /**
     * 텍스트에서 프로젝트 탐지
     */
    fun detectProjects(text: String): List<DetectedProjectResult> {
        val detected = mutableListOf<DetectedProjectResult>()

        for (alias in findAll()) {
            for (pattern in alias.patterns) {
                if (text.contains(pattern, ignoreCase = true)) {
                    detected.add(
                        DetectedProjectResult(
                            projectId = alias.projectId,
                            matchedPattern = pattern,
                            description = alias.description
                        )
                    )
                    break // 같은 프로젝트는 한 번만
                }
            }
        }

        return detected
    }
}

/**
 * 프로젝트 별칭 엔티티
 */
data class ProjectAliasEntity(
    val projectId: String,
    val patterns: List<String>,
    val description: String?,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

/**
 * 프로젝트 별칭 DTO (API 호환용)
 */
data class ProjectAliasDto(
    val patterns: List<String>,
    val description: String
)

/**
 * 프로젝트 탐지 결과
 */
data class DetectedProjectResult(
    val projectId: String,
    val matchedPattern: String,
    val description: String?
)
