package ai.claudeflow.core.registry

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.model.Project
import ai.claudeflow.core.rag.KnowledgeVectorService
import ai.claudeflow.core.storage.repository.ProjectRepository
import mu.KotlinLogging
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 프로젝트 레지스트리
 *
 * DB 기반 프로젝트 관리를 지원하며 인메모리 캐시를 병행 사용합니다.
 * 채널별 기본 프로젝트 설정을 지원합니다.
 * 프로젝트 등록 시 RAG 인덱싱을 자동 수행합니다.
 *
 * @property projectRepository DB 저장소 (null이면 인메모리 모드)
 * @property knowledgeVectorService RAG 인덱싱 서비스 (null이면 인덱싱 스킵)
 */
class ProjectRegistry(
    private val projectRepository: ProjectRepository? = null,
    private val knowledgeVectorService: KnowledgeVectorService? = null,
    initialProjects: List<Project> = emptyList()
) {
    // 인메모리 캐시 (id -> Project)
    private val projects = ConcurrentHashMap<String, Project>()

    // 채널별 기본 프로젝트 캐시 (channel -> projectId)
    private val channelProjects = ConcurrentHashMap<String, String>()

    // 글로벌 기본 프로젝트 ID
    private var defaultProjectId: String? = null

    init {
        // DB가 있으면 초기 로드
        projectRepository?.let { repo ->
            repo.findAll().forEach { project ->
                projects[project.id] = project
                if (project.isDefault) {
                    defaultProjectId = project.id
                }
            }
            logger.info { "Loaded ${projects.size} projects from database" }
        }

        // 초기 프로젝트 등록 (DB 없는 경우)
        initialProjects.forEach { register(it) }
    }

    /**
     * 프로젝트 등록
     *
     * 등록 시 RAG 인덱싱을 자동 수행하여 자연어 검색 가능하게 함
     */
    fun register(project: Project): Boolean {
        // 작업 디렉토리 검증
        val workDir = File(project.workingDirectory)
        if (!workDir.exists()) {
            logger.warn { "Project working directory does not exist: ${project.workingDirectory}" }
        }

        // DB에 저장 (있으면)
        projectRepository?.save(project)

        // 캐시에 저장
        projects[project.id] = project
        logger.info { "Registered project: ${project.id} (${project.name}) at ${project.workingDirectory}" }

        // 기본 프로젝트 설정
        if (project.isDefault || defaultProjectId == null) {
            defaultProjectId = project.id
        }

        // RAG 인덱싱 (비동기적으로 처리)
        indexProjectToRag(project)

        return true
    }

    /**
     * 프로젝트를 RAG에 인덱싱
     */
    private fun indexProjectToRag(project: Project) {
        if (knowledgeVectorService == null) return

        try {
            // 개별 프로젝트 인덱싱
            val indexed = knowledgeVectorService.indexProject(project)
            if (indexed) {
                logger.debug { "Indexed project ${project.id} to RAG" }
            }

            // 프로젝트 목록도 업데이트 (메타 쿼리 대응)
            knowledgeVectorService.indexProjectList(projects.values.toList())
        } catch (e: Exception) {
            logger.warn { "Failed to index project ${project.id} to RAG: ${e.message}" }
            // RAG 인덱싱 실패해도 등록은 성공으로 처리
        }
    }

    /**
     * 프로젝트 제거
     *
     * 제거 시 RAG에서도 삭제
     */
    fun unregister(projectId: String): Boolean {
        // DB에서 삭제 (연관 데이터 포함)
        projectRepository?.deleteWithRelations(projectId)

        val removed = projects.remove(projectId)
        if (removed != null) {
            // 해당 프로젝트를 사용하던 채널 매핑 제거
            channelProjects.entries.removeIf { it.value == projectId }

            // 기본 프로젝트였다면 다른 것으로 변경
            if (defaultProjectId == projectId) {
                defaultProjectId = projects.values.firstOrNull { it.isDefault }?.id
                    ?: projects.keys.firstOrNull()
            }

            // RAG에서 삭제 및 목록 업데이트
            removeProjectFromRag(projectId)

            logger.info { "Unregistered project: $projectId" }
            return true
        }
        return false
    }

    /**
     * RAG에서 프로젝트 삭제
     */
    private fun removeProjectFromRag(projectId: String) {
        if (knowledgeVectorService == null) return

        try {
            knowledgeVectorService.deleteProject(projectId)
            // 프로젝트 목록도 업데이트
            knowledgeVectorService.indexProjectList(projects.values.toList())
        } catch (e: Exception) {
            logger.warn { "Failed to remove project $projectId from RAG: ${e.message}" }
        }
    }

    /**
     * 프로젝트 조회
     */
    fun get(projectId: String): Project? {
        return projects[projectId] ?: projectRepository?.findById(projectId)?.also {
            projects[it.id] = it  // 캐시에 추가
        }
    }

    /**
     * 모든 프로젝트 목록
     */
    fun listAll(): List<Project> {
        // DB가 있으면 최신 데이터 반환
        return projectRepository?.findAll() ?: projects.values.toList()
    }

    /**
     * 채널에 프로젝트 설정
     */
    fun setChannelProject(channel: String, projectId: String): Boolean {
        if (!projects.containsKey(projectId) && projectRepository?.findById(projectId) == null) {
            logger.warn { "Project not found: $projectId" }
            return false
        }

        // DB에 매핑 저장
        projectRepository?.mapChannelToProject(channel, projectId)

        // 캐시에 저장
        channelProjects[channel] = projectId
        logger.info { "Set channel $channel to project $projectId" }
        return true
    }

    /**
     * 채널의 프로젝트 설정 해제
     */
    fun clearChannelProject(channel: String) {
        projectRepository?.unmapChannel(channel)
        channelProjects.remove(channel)
    }

    /**
     * 채널에 설정된 프로젝트 조회
     *
     * 우선순위: 채널 매핑 → 기본 프로젝트
     */
    fun getChannelProject(channel: String): Project? {
        // 캐시 확인
        val cachedProjectId = channelProjects[channel]
        if (cachedProjectId != null) {
            return get(cachedProjectId)
        }

        // DB에서 조회
        val dbProject = projectRepository?.findByChannel(channel)
        if (dbProject != null) {
            channelProjects[channel] = dbProject.id  // 캐시
            return dbProject
        }

        // 기본 프로젝트 반환
        return getDefaultProject()
    }

    /**
     * 채널의 프로젝트 ID 조회
     */
    fun getChannelProjectId(channel: String): String? {
        return channelProjects[channel]
            ?: projectRepository?.findByChannel(channel)?.id
            ?: defaultProjectId
    }

    /**
     * 기본 프로젝트 설정 (하나만 기본 프로젝트로 설정)
     */
    fun setDefaultProject(projectId: String): Boolean {
        // DB에서 기본 프로젝트 설정
        val success = projectRepository?.setAsDefault(projectId) ?: run {
            if (!projects.containsKey(projectId)) return false
            true
        }

        if (success) {
            defaultProjectId = projectId
            // 캐시 무효화
            projects.values.filter { it.isDefault && it.id != projectId }.forEach {
                projects[it.id] = it.copy(isDefault = false)
            }
            projects[projectId]?.let {
                projects[projectId] = it.copy(isDefault = true)
            }
            logger.info { "Set default project to $projectId" }
        }
        return success
    }

    /**
     * 기본 프로젝트 조회
     */
    fun getDefaultProject(): Project? {
        // 캐시된 기본 프로젝트
        defaultProjectId?.let { return get(it) }

        // DB에서 기본 프로젝트 조회
        return projectRepository?.findDefault()?.also {
            defaultProjectId = it.id
            projects[it.id] = it
        }
    }

    /**
     * 채널-프로젝트 매핑 목록
     */
    fun getChannelMappings(): Map<String, String> {
        return channelProjects.toMap()
    }

    /**
     * 프로젝트의 채널 목록
     */
    fun getProjectChannels(projectId: String): List<String> {
        return projectRepository?.getChannelsByProject(projectId)
            ?: channelProjects.filter { it.value == projectId }.keys.toList()
    }

    /**
     * 알람 채널 ID로 프로젝트 조회
     *
     * projects.json의 alertChannels 필드에서 매칭되는 프로젝트 반환
     */
    fun findByAlertChannel(channelId: String): Project? {
        return projects.values.find { project ->
            project.alertChannels.contains(channelId)
        } ?: projectRepository?.findAll()?.find { project ->
            project.alertChannels.contains(channelId)
        }
    }

    /**
     * 프로젝트 통계 조회
     */
    fun getProjectStats(projectId: String) = projectRepository?.getProjectStats(projectId)

    /**
     * Rate Limit 설정 업데이트
     */
    fun updateRateLimit(projectId: String, rpm: Int): Boolean {
        val success = projectRepository?.updateRateLimit(projectId, rpm) ?: false
        if (success) {
            projects[projectId]?.let {
                projects[projectId] = it.copy(rateLimitRpm = rpm)
            }
        }
        return success
    }

    /**
     * 캐시 새로고침
     */
    fun refreshCache() {
        projects.clear()
        channelProjects.clear()
        defaultProjectId = null

        projectRepository?.let { repo ->
            repo.findAll().forEach { project ->
                projects[project.id] = project
                if (project.isDefault) {
                    defaultProjectId = project.id
                }
            }
        }
        logger.info { "Refreshed project cache: ${projects.size} projects loaded" }
    }

    companion object {
        /**
         * 기본 프로젝트로 초기화된 레지스트리 생성 (인메모리 모드)
         *
         * WORKSPACE_PATH 환경변수로 워크스페이스 경로 설정 가능
         * 기본값: $HOME/workspace 또는 /workspace
         */
        fun withDefaultProjects(): ProjectRegistry {
            val workspacePath = System.getenv("WORKSPACE_PATH")
                ?: System.getenv("HOME")?.let { "$it/workspace" }
                ?: "/workspace"

            return ProjectRegistry(
                projectRepository = null,
                initialProjects = listOf(
                    Project(
                        id = "default",
                        name = "Default Project",
                        description = "Default workspace project",
                        workingDirectory = workspacePath,
                        gitRemote = null,
                        defaultBranch = "main",
                        isDefault = true
                    )
                )
            )
        }

        @Deprecated("Use withDefaultProjects() instead", ReplaceWith("withDefaultProjects()"))
        fun withSampleProjects() = withDefaultProjects()
    }
}
