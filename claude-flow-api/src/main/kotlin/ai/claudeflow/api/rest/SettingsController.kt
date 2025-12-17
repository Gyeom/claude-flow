package ai.claudeflow.api.rest

import ai.claudeflow.core.storage.Storage
import ai.claudeflow.core.storage.repository.ProjectAliasEntity
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * Settings API - 프로젝트 별칭 등 설정 관리 (DB 기반)
 */
@RestController
@RequestMapping("/api/v1/settings")
class SettingsController(
    private val storage: Storage,
    private val objectMapper: ObjectMapper
) {
    private val aliasRepository get() = storage.projectAliasRepository
    private val settingsRepository get() = storage.settingsRepository

    // ==================== Project Aliases ====================

    /**
     * 프로젝트 별칭 설정 조회
     */
    @GetMapping("/project-aliases")
    fun getProjectAliases(): Mono<ResponseEntity<ProjectAliasesConfig>> = mono {
        try {
            val aliases = aliasRepository.findAllAsMap()
            val workspaceRoot = settingsRepository.getValue("project_aliases.workspace_root")
                ?: "\${WORKSPACE_PATH:-\$HOME/workspace}"
            val suffixes = settingsRepository.getValue("project_aliases.suffixes")?.let {
                objectMapper.readValue<List<String>>(it)
            } ?: listOf("서버", "프로젝트", "레포", "repository", "repo", "server", "project")

            val config = ProjectAliasesConfig(
                workspaceRoot = workspaceRoot,
                aliases = aliases.mapValues { (_, dto) ->
                    ProjectAlias(patterns = dto.patterns, description = dto.description)
                },
                suffixes = suffixes
            )

            ResponseEntity.ok(config)
        } catch (e: Exception) {
            logger.error(e) { "Failed to load project aliases" }
            ResponseEntity.ok(ProjectAliasesConfig())
        }
    }

    /**
     * 프로젝트 별칭 설정 전체 저장
     */
    @PutMapping("/project-aliases")
    fun saveProjectAliases(@RequestBody config: ProjectAliasesConfig): Mono<ResponseEntity<SaveResult>> = mono {
        try {
            // 기존 별칭 모두 삭제 후 새로 저장
            val existingAliases = aliasRepository.findAll()
            for (alias in existingAliases) {
                aliasRepository.deleteByProjectId(alias.projectId)
            }

            // 새 별칭 저장
            for ((projectId, alias) in config.aliases) {
                aliasRepository.save(
                    ProjectAliasEntity(
                        projectId = projectId,
                        patterns = alias.patterns,
                        description = alias.description
                    )
                )
            }

            // 글로벌 설정 저장
            settingsRepository.setValue("project_aliases.workspace_root", config.workspaceRoot)
            settingsRepository.setValue("project_aliases.suffixes", objectMapper.writeValueAsString(config.suffixes))

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
            aliasRepository.save(
                ProjectAliasEntity(
                    projectId = projectId,
                    patterns = alias.patterns,
                    description = alias.description
                )
            )
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
            val deleted = aliasRepository.deleteByProjectId(projectId)
            if (deleted) {
                logger.info { "Project alias deleted: $projectId" }
                ResponseEntity.ok(SaveResult(success = true, message = "Alias deleted"))
            } else {
                ResponseEntity.ok(SaveResult(success = false, message = "Alias not found"))
            }
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
            val detected = aliasRepository.detectProjects(request.text).map { result ->
                DetectedProject(
                    projectId = result.projectId,
                    matchedPattern = result.matchedPattern,
                    description = result.description
                )
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

    // ==================== General Settings ====================

    /**
     * 전역 설정 조회
     */
    @GetMapping
    fun getSettings(): Mono<ResponseEntity<Map<String, String>>> = mono {
        try {
            val settings = settingsRepository.getAllAsMap()
            ResponseEntity.ok(settings)
        } catch (e: Exception) {
            logger.error(e) { "Failed to load settings" }
            ResponseEntity.ok(emptyMap())
        }
    }

    /**
     * 전역 설정 업데이트
     */
    @PutMapping
    fun updateSettings(@RequestBody settings: Map<String, String>): Mono<ResponseEntity<SaveResult>> = mono {
        try {
            for ((key, value) in settings) {
                settingsRepository.setValue(key, value)
            }
            logger.info { "Settings updated: ${settings.keys}" }
            ResponseEntity.ok(SaveResult(success = true, message = "Settings saved"))
        } catch (e: Exception) {
            logger.error(e) { "Failed to update settings" }
            ResponseEntity.ok(SaveResult(success = false, message = e.message ?: "Failed to save"))
        }
    }

    /**
     * 단일 설정 조회
     */
    @GetMapping("/{key}")
    fun getSetting(@PathVariable key: String): Mono<ResponseEntity<SettingValue>> = mono {
        try {
            val value = settingsRepository.getValue(key)
            ResponseEntity.ok(SettingValue(key = key, value = value))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get setting: $key" }
            ResponseEntity.ok(SettingValue(key = key, value = null))
        }
    }

    /**
     * 단일 설정 저장
     */
    @PutMapping("/{key}")
    fun setSetting(
        @PathVariable key: String,
        @RequestBody request: SetSettingRequest
    ): Mono<ResponseEntity<SaveResult>> = mono {
        try {
            settingsRepository.setValue(key, request.value)
            logger.info { "Setting saved: $key" }
            ResponseEntity.ok(SaveResult(success = true, message = "Setting saved"))
        } catch (e: Exception) {
            logger.error(e) { "Failed to save setting: $key" }
            ResponseEntity.ok(SaveResult(success = false, message = e.message ?: "Failed to save"))
        }
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

data class SettingValue(
    val key: String,
    val value: String?
)

data class SetSettingRequest(
    val value: String
)
