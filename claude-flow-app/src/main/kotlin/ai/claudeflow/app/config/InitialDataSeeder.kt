package ai.claudeflow.app.config

import ai.claudeflow.core.storage.Storage
import ai.claudeflow.core.storage.repository.ProjectAliasEntity
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * 초기 데이터 시더
 *
 * 서버 시작 시 로컬 설정 파일에서 DB로 초기 데이터를 시딩합니다.
 * DB가 비어있을 때만 파일에서 로드하여 기존 데이터를 보존합니다.
 */
@Component
class InitialDataSeeder(
    private val storage: Storage,
    private val objectMapper: ObjectMapper,
    @Value("\${claude-flow.config-path:#{null}}") private val configPath: String?
) {
    private val configDir: File by lazy {
        val path = configPath ?: "${System.getProperty("user.dir")}/.claude/config"
        File(path)
    }

    @PostConstruct
    fun seedIfEmpty() {
        logger.info { "Checking initial data seeding..." }

        seedProjectAliases()
        seedGlobalSettings()

        logger.info { "Initial data seeding completed" }
    }

    /**
     * 프로젝트 별칭 시딩
     */
    private fun seedProjectAliases() {
        val repository = storage.projectAliasRepository
        val existingCount = repository.count()

        if (existingCount > 0) {
            logger.info { "Project aliases already exist in DB ($existingCount entries), skipping seed" }
            return
        }

        // 파일에서 로드
        val aliasesFile = File(configDir, "project-aliases.json")
        val exampleFile = File(configDir, "project-aliases.example.json")

        val sourceFile = when {
            aliasesFile.exists() -> aliasesFile
            exampleFile.exists() -> {
                logger.warn {
                    "Using example file for project aliases (project-aliases.example.json). " +
                    "For production, create project-aliases.json with your actual project configurations."
                }
                exampleFile
            }
            else -> {
                logger.info { "No project-aliases file found, skipping seed" }
                return
            }
        }

        try {
            val config: ProjectAliasesFileConfig = objectMapper.readValue(sourceFile)

            var seededCount = 0
            for ((projectId, alias) in config.aliases) {
                repository.save(
                    ProjectAliasEntity(
                        projectId = projectId,
                        patterns = alias.patterns,
                        description = alias.description
                    )
                )
                seededCount++
            }

            // 글로벌 설정도 저장 (workspaceRoot, suffixes)
            if (config.workspaceRoot.isNotEmpty()) {
                storage.settingsRepository.setValue("project_aliases.workspace_root", config.workspaceRoot)
            }
            if (config.suffixes.isNotEmpty()) {
                storage.settingsRepository.setValue(
                    "project_aliases.suffixes",
                    objectMapper.writeValueAsString(config.suffixes)
                )
            }

            logger.info { "Seeded $seededCount project aliases from ${sourceFile.name}" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to seed project aliases from file: ${sourceFile.absolutePath}" }
        }
    }

    /**
     * 글로벌 설정 시딩
     */
    private fun seedGlobalSettings() {
        val repository = storage.settingsRepository

        // 기본 설정이 없으면 초기값 설정
        val defaultSettings = mapOf(
            "default_timeout" to "300",
            "default_rate_limit_rpm" to "60",
            "default_classify_model" to "haiku"
        )

        for ((key, value) in defaultSettings) {
            if (repository.getValue(key) == null) {
                repository.setValue(key, value)
                logger.debug { "Seeded default setting: $key = $value" }
            }
        }
    }
}

/**
 * 프로젝트 별칭 파일 구조 (기존 JSON 포맷과 호환)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ProjectAliasesFileConfig(
    val workspaceRoot: String = "",
    val aliases: Map<String, ProjectAliasFileEntry> = emptyMap(),
    val suffixes: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProjectAliasFileEntry(
    val patterns: List<String> = emptyList(),
    val description: String = ""
)
