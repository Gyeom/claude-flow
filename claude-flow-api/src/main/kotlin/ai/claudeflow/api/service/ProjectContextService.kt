package ai.claudeflow.api.service

import ai.claudeflow.core.rag.KnowledgeVectorService
import ai.claudeflow.core.storage.Storage
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * 프로젝트 컨텍스트 서비스
 * 프롬프트에서 프로젝트를 탐지하고 관련 컨텍스트를 주입합니다.
 *
 * RAG 기반 시맨틱 검색을 통해 프로젝트 관련 질문에 답변합니다:
 * - "어떤 프로젝트들을 관리하고 있니?" → RAG에서 프로젝트 목록 검색
 * - "인가서버에 대해 설명해줘" → 특정 프로젝트 정보 검색
 *
 * DB에 저장된 project_aliases 테이블을 우선 사용하고,
 * 없으면 config/project-aliases.json 파일을 폴백으로 사용합니다.
 */
@Service
class ProjectContextService(
    private val objectMapper: ObjectMapper,
    private val storage: Storage,
    private val knowledgeVectorService: KnowledgeVectorService?,
    @Value("\${claude-flow.config-path:#{null}}") private val configPath: String?,
    @Value("\${claude-flow.workspace-root:#{null}}") private val workspaceRoot: String?
) {
    private val configDir: File by lazy {
        val path = configPath ?: "${System.getProperty("user.dir")}/.claude/config"
        File(path)
    }

    private val aliasesFile: File
        get() = File(configDir, "project-aliases.json")

    private val exampleFile: File
        get() = File(configDir, "project-aliases.example.json")

    /**
     * 프롬프트를 분석하여 프로젝트 컨텍스트를 주입한 프롬프트 반환
     *
     * 1. RAG 기반 시맨틱 검색 (프로젝트 목록 질문 등 메타 쿼리 처리)
     * 2. 패턴 기반 탐지 (특정 프로젝트명 언급 시 디렉토리 컨텍스트 주입)
     */
    fun enrichPromptWithProjectContext(prompt: String): EnrichedPrompt {
        val contextParts = mutableListOf<String>()
        val projectInfos = mutableListOf<DetectedProjectInfo>()

        // 1. RAG 기반 검색 (시맨틱 매칭)
        val ragContext = searchProjectsWithRag(prompt)
        if (ragContext != null) {
            contextParts.add(ragContext.context)
            projectInfos.addAll(ragContext.projectInfos)
            logger.info { "RAG search found ${ragContext.projectInfos.size} relevant projects" }
        }

        // 2. 패턴 기반 탐지 (명시적 프로젝트명 언급)
        val config = loadAliasesConfig()
        val detectedProjects = detectProjects(prompt, config)

        for (detected in detectedProjects) {
            // 이미 RAG에서 찾은 프로젝트는 스킵
            if (projectInfos.any { it.projectId == detected.projectId }) continue

            val projectDir = findProjectDirectory(detected.projectId, config)
            if (projectDir != null && projectDir.exists()) {
                val context = extractProjectContext(projectDir)
                if (context.isNotEmpty()) {
                    contextParts.add("""
                        |[Detected Project: ${detected.projectId}]
                        |Path: ${projectDir.absolutePath}
                        |Matched: "${detected.matchedPattern}"
                        |--- Project Context ---
                        |$context
                        |--- End Project Context ---
                    """.trimMargin())

                    projectInfos.add(DetectedProjectInfo(
                        projectId = detected.projectId,
                        matchedPattern = detected.matchedPattern,
                        description = detected.description,
                        path = projectDir.absolutePath,
                        contextSize = context.length
                    ))
                }
            }
        }

        if (contextParts.isEmpty()) {
            return EnrichedPrompt(
                originalPrompt = prompt,
                enrichedPrompt = prompt,
                detectedProjects = emptyList(),
                contextInjected = false
            )
        }

        val enrichedPrompt = """
            |${contextParts.joinToString("\n\n")}
            |
            |--- User Request ---
            |$prompt
        """.trimMargin()

        logger.info { "Enriched prompt with ${projectInfos.size} project context(s): ${projectInfos.map { it.projectId }}" }

        return EnrichedPrompt(
            originalPrompt = prompt,
            enrichedPrompt = enrichedPrompt,
            detectedProjects = projectInfos,
            contextInjected = true
        )
    }

    /**
     * RAG 기반 프로젝트 검색
     *
     * 메타 쿼리(프로젝트 목록 질문)와 특정 프로젝트 질문 모두 처리
     */
    private fun searchProjectsWithRag(prompt: String): RagSearchResult? {
        if (knowledgeVectorService == null) return null

        try {
            val results = knowledgeVectorService.searchProjects(prompt, topK = 3, minScore = 0.4f)
            if (results.isEmpty()) return null

            val contextParts = mutableListOf<String>()
            val projectInfos = mutableListOf<DetectedProjectInfo>()

            for (result in results) {
                when (result.type) {
                    KnowledgeVectorService.TYPE_PROJECT_LIST -> {
                        // 프로젝트 목록 컨텍스트
                        contextParts.add("""
                            |[Project List from RAG]
                            |${result.content}
                        """.trimMargin())

                        // 메타 정보로 DetectedProjectInfo 추가
                        projectInfos.add(DetectedProjectInfo(
                            projectId = "project-list",
                            matchedPattern = "RAG semantic search",
                            description = "관리 중인 프로젝트 목록",
                            path = "",
                            contextSize = result.content.length
                        ))
                    }
                    KnowledgeVectorService.TYPE_PROJECT -> {
                        // 개별 프로젝트 컨텍스트
                        contextParts.add("""
                            |[Project from RAG: ${result.docId}]
                            |Relevance: ${String.format("%.2f", result.score)}
                            |${result.content}
                        """.trimMargin())

                        projectInfos.add(DetectedProjectInfo(
                            projectId = result.docId,
                            matchedPattern = "RAG semantic search (score: ${String.format("%.2f", result.score)})",
                            description = result.metadata["description"] as? String,
                            path = result.metadata["working_directory"] as? String ?: "",
                            contextSize = result.content.length
                        ))
                    }
                }
            }

            if (contextParts.isEmpty()) return null

            return RagSearchResult(
                context = contextParts.joinToString("\n\n"),
                projectInfos = projectInfos
            )
        } catch (e: Exception) {
            logger.warn { "RAG search failed: ${e.message}" }
            return null
        }
    }

    /**
     * 프롬프트에서 프로젝트 탐지
     */
    private fun detectProjects(prompt: String, config: ProjectAliasesConfig): List<DetectedProject> {
        val detected = mutableListOf<DetectedProject>()

        for ((projectId, alias) in config.aliases) {
            for (pattern in alias.patterns) {
                if (prompt.contains(pattern, ignoreCase = true)) {
                    detected.add(DetectedProject(
                        projectId = projectId,
                        matchedPattern = pattern,
                        description = alias.description
                    ))
                    break // 같은 프로젝트는 한 번만
                }
            }
        }

        return detected
    }

    /**
     * 프로젝트 디렉토리 찾기
     */
    private fun findProjectDirectory(projectId: String, config: ProjectAliasesConfig): File? {
        val root = resolveWorkspaceRoot(config.workspaceRoot)
        if (!root.exists()) {
            logger.warn { "Workspace root does not exist: $root" }
            return null
        }

        // 1. 정확한 이름 매칭
        val exactMatch = File(root, projectId)
        if (exactMatch.exists() && exactMatch.isDirectory) {
            return exactMatch
        }

        // 2. 대소문자 무시 검색 (한 레벨)
        root.listFiles()?.find {
            it.isDirectory && it.name.equals(projectId, ignoreCase = true)
        }?.let { return it }

        // 3. 하위 디렉토리 검색 (두 레벨까지)
        root.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory) {
                subDir.listFiles()?.find {
                    it.isDirectory && it.name.equals(projectId, ignoreCase = true)
                }?.let { return it }
            }
        }

        return null
    }

    /**
     * 프로젝트 컨텍스트 추출
     */
    private fun extractProjectContext(projectDir: File): String {
        val parts = mutableListOf<String>()

        // 1. CLAUDE.md (최우선)
        val claudeMd = File(projectDir, "CLAUDE.md")
        if (claudeMd.exists()) {
            val content = claudeMd.readText().take(3000)
            parts.add("## CLAUDE.md\n$content")
        }

        // 2. README.md (요약)
        val readmeMd = File(projectDir, "README.md")
        if (readmeMd.exists() && !claudeMd.exists()) {
            val content = readmeMd.readText().take(1500)
            parts.add("## README.md (Summary)\n$content")
        }

        // 3. 기술 스택 탐지
        val techStack = detectTechStack(projectDir)
        if (techStack.isNotEmpty()) {
            parts.add("## Tech Stack\n$techStack")
        }

        // 4. 디렉토리 구조 (간략)
        val structure = getDirectoryStructure(projectDir, maxDepth = 2)
        if (structure.isNotEmpty()) {
            parts.add("## Directory Structure\n```\n$structure\n```")
        }

        return parts.joinToString("\n\n")
    }

    /**
     * 기술 스택 탐지
     */
    private fun detectTechStack(projectDir: File): String {
        val stack = mutableListOf<String>()

        if (File(projectDir, "build.gradle.kts").exists() || File(projectDir, "build.gradle").exists()) {
            stack.add("Gradle")
        }
        if (File(projectDir, "pom.xml").exists()) {
            stack.add("Maven")
        }
        if (File(projectDir, "package.json").exists()) {
            stack.add("Node.js")
        }
        if (File(projectDir, "Cargo.toml").exists()) {
            stack.add("Rust")
        }
        if (File(projectDir, "go.mod").exists()) {
            stack.add("Go")
        }
        if (File(projectDir, "requirements.txt").exists() || File(projectDir, "pyproject.toml").exists()) {
            stack.add("Python")
        }

        // Kotlin/Java 탐지
        projectDir.walkTopDown().maxDepth(3).find { it.extension == "kt" }?.let { stack.add("Kotlin") }
        projectDir.walkTopDown().maxDepth(3).find { it.extension == "java" }?.let { stack.add("Java") }

        return stack.joinToString(", ")
    }

    /**
     * 디렉토리 구조 가져오기
     */
    private fun getDirectoryStructure(dir: File, maxDepth: Int, currentDepth: Int = 0, prefix: String = ""): String {
        if (currentDepth >= maxDepth) return ""

        val ignoreDirs = setOf(".git", "node_modules", "build", "target", ".gradle", ".idea", "__pycache__")
        val entries = dir.listFiles()
            ?.filter { !ignoreDirs.contains(it.name) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?.take(15)
            ?: return ""

        return buildString {
            for ((index, entry) in entries.withIndex()) {
                val isLast = index == entries.size - 1
                val connector = if (isLast) "└── " else "├── "
                val newPrefix = if (isLast) "$prefix    " else "$prefix│   "

                append("$prefix$connector${entry.name}")
                if (entry.isDirectory) append("/")
                appendLine()

                if (entry.isDirectory && currentDepth < maxDepth - 1) {
                    append(getDirectoryStructure(entry, maxDepth, currentDepth + 1, newPrefix))
                }
            }
        }
    }

    private fun resolveWorkspaceRoot(configRoot: String): File {
        // 환경변수 치환
        var resolved = configRoot
        if (resolved.contains("\${WORKSPACE_PATH")) {
            resolved = System.getenv("WORKSPACE_PATH") ?: System.getProperty("user.home") + "/projects"
        }
        if (resolved.contains("\$HOME")) {
            resolved = resolved.replace("\$HOME", System.getProperty("user.home"))
        }

        // 설정된 workspaceRoot 우선
        workspaceRoot?.let { return File(it) }

        return File(resolved)
    }

    /**
     * Aliases 설정 로드 (우선순위)
     * 1. DB project_aliases 테이블
     * 2. config/project-aliases.json 파일
     * 3. 빈 설정 반환
     */
    private fun loadAliasesConfig(): ProjectAliasesConfig {
        // 1. DB에서 먼저 조회
        try {
            val dbAliases = storage.projectAliasRepository.findAllAsMap()
            if (dbAliases.isNotEmpty()) {
                val aliases = dbAliases.mapValues { (_, dto) ->
                    ProjectAlias(
                        patterns = dto.patterns,
                        description = dto.description
                    )
                }
                logger.debug { "Loaded ${aliases.size} project aliases from DB" }
                return ProjectAliasesConfig(
                    workspaceRoot = workspaceRoot ?: "\${WORKSPACE_PATH:-\$HOME/workspace}",
                    aliases = aliases
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load aliases from DB, falling back to file" }
        }

        // 2. 파일에서 로드 (폴백)
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
}

// ==================== DTOs ====================

/**
 * RAG 검색 결과
 */
data class RagSearchResult(
    val context: String,
    val projectInfos: List<DetectedProjectInfo>
)

data class EnrichedPrompt(
    val originalPrompt: String,
    val enrichedPrompt: String,
    val detectedProjects: List<DetectedProjectInfo>,
    val contextInjected: Boolean
)

data class DetectedProjectInfo(
    val projectId: String,
    val matchedPattern: String,
    val description: String?,
    val path: String,
    val contextSize: Int
)

data class DetectedProject(
    val projectId: String,
    val matchedPattern: String,
    val description: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProjectAliasesConfig(
    val workspaceRoot: String = "\${WORKSPACE_PATH:-\$HOME/workspace}",
    val aliases: Map<String, ProjectAlias> = emptyMap(),
    val suffixes: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProjectAlias(
    val patterns: List<String> = emptyList(),
    val description: String = ""
)
