package ai.claudeflow.core.registry

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.model.ClaudeConfig
import ai.claudeflow.core.model.Project
import mu.KotlinLogging
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 프로젝트 레지스트리
 *
 * 여러 프로젝트를 등록하고 관리
 * 채널별 기본 프로젝트 설정 지원
 */
class ProjectRegistry(
    initialProjects: List<Project> = emptyList()
) {
    // 등록된 프로젝트들 (id -> Project)
    private val projects = ConcurrentHashMap<String, Project>()

    // 채널별 기본 프로젝트 (channel -> projectId)
    private val channelProjects = ConcurrentHashMap<String, String>()

    // 글로벌 기본 프로젝트
    private var defaultProjectId: String? = null

    init {
        initialProjects.forEach { register(it) }
    }

    /**
     * 프로젝트 등록
     */
    fun register(project: Project): Boolean {
        // 작업 디렉토리 검증
        val workDir = File(project.workingDirectory)
        if (!workDir.exists()) {
            logger.warn { "Project working directory does not exist: ${project.workingDirectory}" }
        }

        projects[project.id] = project
        logger.info { "Registered project: ${project.id} (${project.name}) at ${project.workingDirectory}" }

        // 첫 번째 프로젝트를 기본으로 설정
        if (defaultProjectId == null) {
            defaultProjectId = project.id
        }

        return true
    }

    /**
     * 프로젝트 제거
     */
    fun unregister(projectId: String): Boolean {
        val removed = projects.remove(projectId)
        if (removed != null) {
            // 해당 프로젝트를 사용하던 채널 매핑 제거
            channelProjects.entries.removeIf { it.value == projectId }

            // 기본 프로젝트였다면 다른 것으로 변경
            if (defaultProjectId == projectId) {
                defaultProjectId = projects.keys.firstOrNull()
            }

            logger.info { "Unregistered project: $projectId" }
            return true
        }
        return false
    }

    /**
     * 프로젝트 조회
     */
    fun get(projectId: String): Project? = projects[projectId]

    /**
     * 모든 프로젝트 목록
     */
    fun listAll(): List<Project> = projects.values.toList()

    /**
     * 채널에 프로젝트 설정
     */
    fun setChannelProject(channel: String, projectId: String): Boolean {
        if (!projects.containsKey(projectId)) {
            logger.warn { "Project not found: $projectId" }
            return false
        }

        channelProjects[channel] = projectId
        logger.info { "Set channel $channel to project $projectId" }
        return true
    }

    /**
     * 채널의 프로젝트 설정 해제
     */
    fun clearChannelProject(channel: String) {
        channelProjects.remove(channel)
    }

    /**
     * 채널에 설정된 프로젝트 조회
     */
    fun getChannelProject(channel: String): Project? {
        val projectId = channelProjects[channel] ?: defaultProjectId ?: return null
        return projects[projectId]
    }

    /**
     * 채널의 프로젝트 ID 조회
     */
    fun getChannelProjectId(channel: String): String? {
        return channelProjects[channel] ?: defaultProjectId
    }

    /**
     * 기본 프로젝트 설정
     */
    fun setDefaultProject(projectId: String): Boolean {
        if (!projects.containsKey(projectId)) {
            return false
        }
        defaultProjectId = projectId
        logger.info { "Set default project to $projectId" }
        return true
    }

    /**
     * 기본 프로젝트 조회
     */
    fun getDefaultProject(): Project? {
        return defaultProjectId?.let { projects[it] }
    }

    /**
     * 채널-프로젝트 매핑 목록
     */
    fun getChannelMappings(): Map<String, String> = channelProjects.toMap()

    companion object {
        /**
         * 샘플 프로젝트로 초기화된 레지스트리 생성
         *
         * 실제 사용 시 WORKSPACE_PATH 환경변수를 설정하세요.
         */
        fun withSampleProjects(): ProjectRegistry {
            val workspacePath = System.getenv("WORKSPACE_PATH")
                ?: System.getenv("HOME")?.let { "$it/projects" }
                ?: "/workspace"

            return ProjectRegistry(listOf(
                Project(
                    id = "claude-flow",
                    name = "Claude Flow",
                    description = "Slack AI Assistant Platform",
                    workingDirectory = "$workspacePath/claude-flow",
                    gitRemote = "https://github.com/your-org/claude-flow",
                    defaultBranch = "main",
                    agents = listOf(Agent.GENERAL, Agent.CODE_REVIEWER, Agent.BUG_FIXER)
                ),
                Project(
                    id = "sample-project",
                    name = "Sample Project",
                    description = "Example project configuration",
                    workingDirectory = "$workspacePath/sample-project",
                    gitRemote = "https://github.com/your-org/sample-project",
                    defaultBranch = "main",
                    agents = listOf(Agent.GENERAL, Agent.CODE_REVIEWER)
                )
            ))
        }
    }
}
