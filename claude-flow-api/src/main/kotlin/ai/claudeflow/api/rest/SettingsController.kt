package ai.claudeflow.api.rest

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Settings API - 프로젝트 별칭 등 설정 관리
 */
@RestController
@RequestMapping("/api/v1/settings")
class SettingsController(
    private val objectMapper: ObjectMapper,
    @Value("\${claude-flow.config-path:#{null}}") private val configPath: String?
) {
    private val configDir: File by lazy {
        val path = configPath ?: "${System.getProperty("user.dir")}/.claude/config"
        File(path).also { it.mkdirs() }
    }

    private val aliasesFile: File
        get() = File(configDir, "project-aliases.json")

    private val exampleFile: File
        get() = File(configDir, "project-aliases.example.json")

    // ==================== Project Aliases ====================

    /**
     * 프로젝트 별칭 설정 조회
     */
    @GetMapping("/project-aliases")
    fun getProjectAliases(): Mono<ResponseEntity<ProjectAliasesConfig>> = mono {
        try {
            val config = loadAliasesConfig()
            ResponseEntity.ok(config)
        } catch (e: Exception) {
            logger.error(e) { "Failed to load project aliases" }
            ResponseEntity.ok(ProjectAliasesConfig())
        }
    }

    /**
     * 프로젝트 별칭 설정 저장
     */
    @PutMapping("/project-aliases")
    fun saveProjectAliases(@RequestBody config: ProjectAliasesConfig): Mono<ResponseEntity<SaveResult>> = mono {
        try {
            saveAliasesConfig(config)
            logger.info { "Project aliases saved: ${config.aliases.size} projects" }
            ResponseEntity.ok(SaveResult(success = true, message = "Settings saved successfully"))
        } catch (e: Exception) {
            logger.error(e) { "Failed to save project aliases" }
            ResponseEntity.ok(SaveResult(success = false, message = e.message ?: "Failed to save"))
        }
    }

    /**
     * 단일 프로젝트 별칭 추가/수정
     */
    @PutMapping("/project-aliases/{projectId}")
    fun upsertProjectAlias(
        @PathVariable projectId: String,
        @RequestBody alias: ProjectAlias
    ): Mono<ResponseEntity<SaveResult>> = mono {
        try {
            val config = loadAliasesConfig()
            val updated = config.copy(
                aliases = config.aliases + (projectId to alias)
            )
            saveAliasesConfig(updated)
            logger.info { "Project alias upserted: $projectId" }
            ResponseEntity.ok(SaveResult(success = true, message = "Alias saved"))
        } catch (e: Exception) {
            logger.error(e) { "Failed to upsert project alias: $projectId" }
            ResponseEntity.ok(SaveResult(success = false, message = e.message ?: "Failed to save"))
        }
    }

    /**
     * 단일 프로젝트 별칭 삭제
     */
    @DeleteMapping("/project-aliases/{projectId}")
    fun deleteProjectAlias(@PathVariable projectId: String): Mono<ResponseEntity<SaveResult>> = mono {
        try {
            val config = loadAliasesConfig()
            val updated = config.copy(
                aliases = config.aliases - projectId
            )
            saveAliasesConfig(updated)
            logger.info { "Project alias deleted: $projectId" }
            ResponseEntity.ok(SaveResult(success = true, message = "Alias deleted"))
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete project alias: $projectId" }
            ResponseEntity.ok(SaveResult(success = false, message = e.message ?: "Failed to delete"))
        }
    }

    /**
     * 프로젝트 별칭 테스트 - 주어진 텍스트에서 프로젝트 탐지
     */
    @PostMapping("/project-aliases/test")
    fun testProjectAliases(@RequestBody request: TestAliasRequest): Mono<ResponseEntity<TestAliasResult>> = mono {
        try {
            val config = loadAliasesConfig()
            val detected = mutableListOf<DetectedProject>()

            for ((projectId, alias) in config.aliases) {
                for (pattern in alias.patterns) {
                    if (request.text.contains(pattern, ignoreCase = true)) {
                        detected.add(DetectedProject(
                            projectId = projectId,
                            matchedPattern = pattern,
                            description = alias.description
                        ))
                        break // 같은 프로젝트는 한 번만
                    }
                }
            }

            ResponseEntity.ok(TestAliasResult(
                text = request.text,
                detected = detected
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to test project aliases" }
            ResponseEntity.ok(TestAliasResult(text = request.text, detected = emptyList()))
        }
    }

    // ==================== Helper Functions ====================

    private fun loadAliasesConfig(): ProjectAliasesConfig {
        val file = when {
            aliasesFile.exists() -> aliasesFile
            exampleFile.exists() -> exampleFile
            else -> return ProjectAliasesConfig()
        }

        return try {
            objectMapper.readValue(file)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse aliases config, returning default" }
            ProjectAliasesConfig()
        }
    }

    private fun saveAliasesConfig(config: ProjectAliasesConfig) {
        configDir.mkdirs()
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(aliasesFile, config)
    }
}

// ==================== DTOs ====================

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProjectAliasesConfig(
    val workspaceRoot: String = "\${WORKSPACE_PATH:-\$HOME/workspace}",
    val aliases: Map<String, ProjectAlias> = emptyMap(),
    val suffixes: List<String> = listOf("서버", "프로젝트", "레포", "repository", "repo", "server", "project")
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProjectAlias(
    val patterns: List<String> = emptyList(),
    val description: String = ""
)

data class SaveResult(
    val success: Boolean,
    val message: String
)

data class TestAliasRequest(
    val text: String
)

data class TestAliasResult(
    val text: String,
    val detected: List<DetectedProject>
)

data class DetectedProject(
    val projectId: String,
    val matchedPattern: String,
    val description: String?
)
