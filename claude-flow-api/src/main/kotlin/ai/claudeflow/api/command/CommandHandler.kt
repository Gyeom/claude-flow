package ai.claudeflow.api.command

import ai.claudeflow.core.model.Project
import ai.claudeflow.core.registry.ProjectRegistry
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Slack 명령어 핸들러
 *
 * @봇 /project list 형태의 명령어 처리
 */
class CommandHandler(
    private val projectRegistry: ProjectRegistry
) {
    // Slack 멘션 패턴 (예: <@U0A2TN1CBB5>)
    private val mentionPattern = Regex("<@[A-Z0-9]+>", RegexOption.IGNORE_CASE)

    /**
     * 명령어 실행
     */
    fun execute(text: String, channel: String): CommandResult {
        // 멘션 제거 후 트림
        val trimmed = text.replace(mentionPattern, "").trim()

        // 명령어가 아니면 null 반환
        if (!trimmed.startsWith("/")) {
            return CommandResult(
                isCommand = false,
                response = null
            )
        }

        val parts = trimmed.split(Regex("\\s+"))
        val command = parts[0].lowercase()
        val args = parts.drop(1)

        logger.info { "Executing command: $command with args: $args" }

        return when (command) {
            "/project", "/프로젝트" -> handleProjectCommand(args, channel)
            "/help", "/도움" -> handleHelpCommand()
            else -> CommandResult(
                isCommand = true,
                response = "❓ 알 수 없는 명령어입니다: $command\n`/help`로 사용 가능한 명령어를 확인하세요."
            )
        }
    }

    /**
     * /project 명령어 처리
     */
    private fun handleProjectCommand(args: List<String>, channel: String): CommandResult {
        if (args.isEmpty()) {
            return CommandResult(
                isCommand = true,
                response = """
                    📁 *프로젝트 명령어*

                    `/project list` - 등록된 프로젝트 목록
                    `/project set {id}` - 현재 채널에 프로젝트 설정
                    `/project info` - 현재 채널의 프로젝트 정보
                    `/project add {id} {path}` - 새 프로젝트 등록
                    `/project remove {id}` - 프로젝트 삭제
                """.trimIndent()
            )
        }

        val subCommand = args[0].lowercase()
        val subArgs = args.drop(1)

        return when (subCommand) {
            "list", "목록" -> listProjects()
            "set", "설정" -> setProject(subArgs, channel)
            "info", "정보" -> getProjectInfo(channel)
            "add", "추가" -> addProject(subArgs)
            "remove", "삭제" -> removeProject(subArgs)
            else -> CommandResult(
                isCommand = true,
                response = "❓ 알 수 없는 프로젝트 명령어입니다: $subCommand"
            )
        }
    }

    /**
     * 프로젝트 목록
     */
    private fun listProjects(): CommandResult {
        val projects = projectRegistry.listAll()
        val defaultId = projectRegistry.getDefaultProject()?.id

        if (projects.isEmpty()) {
            return CommandResult(
                isCommand = true,
                response = "📁 등록된 프로젝트가 없습니다."
            )
        }

        val projectList = projects.joinToString("\n") { project ->
            val isDefault = if (project.id == defaultId) " ⭐" else ""
            "• `${project.id}`$isDefault - ${project.name}\n  📂 ${project.workingDirectory}"
        }

        return CommandResult(
            isCommand = true,
            response = "📁 *등록된 프로젝트* (${projects.size}개)\n\n$projectList"
        )
    }

    /**
     * 채널에 프로젝트 설정
     */
    private fun setProject(args: List<String>, channel: String): CommandResult {
        if (args.isEmpty()) {
            return CommandResult(
                isCommand = true,
                response = "❌ 프로젝트 ID를 입력하세요.\n사용법: `/project set {project_id}`"
            )
        }

        val projectId = args[0]
        val project = projectRegistry.get(projectId)

        if (project == null) {
            val available = projectRegistry.listAll().joinToString(", ") { "`${it.id}`" }
            return CommandResult(
                isCommand = true,
                response = "❌ 프로젝트를 찾을 수 없습니다: `$projectId`\n\n사용 가능한 프로젝트: $available"
            )
        }

        val success = projectRegistry.setChannelProject(channel, projectId)

        return if (success) {
            CommandResult(
                isCommand = true,
                response = """
                    ✅ 프로젝트가 설정되었습니다!

                    *프로젝트*: ${project.name} (`${project.id}`)
                    *경로*: `${project.workingDirectory}`

                    이제 이 채널에서 질문하면 해당 프로젝트 컨텍스트에서 답변합니다.
                """.trimIndent()
            )
        } else {
            CommandResult(
                isCommand = true,
                response = "❌ 프로젝트 설정에 실패했습니다."
            )
        }
    }

    /**
     * 현재 채널의 프로젝트 정보
     */
    private fun getProjectInfo(channel: String): CommandResult {
        val project = projectRegistry.getChannelProject(channel)

        return if (project != null) {
            CommandResult(
                isCommand = true,
                response = """
                    📁 *현재 프로젝트 정보*

                    *이름*: ${project.name}
                    *ID*: `${project.id}`
                    *경로*: `${project.workingDirectory}`
                    *Git*: ${project.gitRemote ?: "없음"}
                    *브랜치*: `${project.defaultBranch}`
                """.trimIndent()
            )
        } else {
            CommandResult(
                isCommand = true,
                response = """
                    📁 *현재 프로젝트 정보*

                    이 채널에 설정된 프로젝트가 없습니다.
                    `/project set {id}`로 프로젝트를 설정하세요.
                """.trimIndent()
            )
        }
    }

    /**
     * 프로젝트 추가
     */
    private fun addProject(args: List<String>): CommandResult {
        if (args.size < 2) {
            return CommandResult(
                isCommand = true,
                response = "❌ 프로젝트 ID와 경로를 입력하세요.\n사용법: `/project add {id} {path}`"
            )
        }

        val id = args[0]
        val path = args.drop(1).joinToString(" ")  // 경로에 공백이 있을 수 있음

        // 이미 존재하는지 확인
        if (projectRegistry.get(id) != null) {
            return CommandResult(
                isCommand = true,
                response = "❌ 이미 존재하는 프로젝트 ID입니다: `$id`"
            )
        }

        val project = Project(
            id = id,
            name = id,  // 이름은 ID와 동일하게 시작
            workingDirectory = path
        )

        val success = projectRegistry.register(project)

        return if (success) {
            CommandResult(
                isCommand = true,
                response = """
                    ✅ 프로젝트가 등록되었습니다!

                    *ID*: `$id`
                    *경로*: `$path`

                    `/project set $id`로 이 채널에 설정할 수 있습니다.
                """.trimIndent()
            )
        } else {
            CommandResult(
                isCommand = true,
                response = "❌ 프로젝트 등록에 실패했습니다."
            )
        }
    }

    /**
     * 프로젝트 삭제
     */
    private fun removeProject(args: List<String>): CommandResult {
        if (args.isEmpty()) {
            return CommandResult(
                isCommand = true,
                response = "❌ 삭제할 프로젝트 ID를 입력하세요.\n사용법: `/project remove {id}`"
            )
        }

        val id = args[0]
        val success = projectRegistry.unregister(id)

        return if (success) {
            CommandResult(
                isCommand = true,
                response = "✅ 프로젝트가 삭제되었습니다: `$id`"
            )
        } else {
            CommandResult(
                isCommand = true,
                response = "❌ 프로젝트를 찾을 수 없습니다: `$id`"
            )
        }
    }

    /**
     * 도움말
     */
    private fun handleHelpCommand(): CommandResult {
        return CommandResult(
            isCommand = true,
            response = """
                🤖 *Claude Flow 명령어*

                *프로젝트 관리*
                `/project list` - 프로젝트 목록
                `/project set {id}` - 채널에 프로젝트 설정
                `/project info` - 현재 프로젝트 정보
                `/project add {id} {path}` - 프로젝트 추가
                `/project remove {id}` - 프로젝트 삭제

                *일반*
                `/help` - 이 도움말 표시

                명령어 없이 질문하면 Claude가 답변합니다!
            """.trimIndent()
        )
    }
}

/**
 * 명령어 실행 결과
 */
data class CommandResult(
    val isCommand: Boolean,      // 명령어인지 여부
    val response: String?        // 응답 메시지 (명령어가 아니면 null)
)
