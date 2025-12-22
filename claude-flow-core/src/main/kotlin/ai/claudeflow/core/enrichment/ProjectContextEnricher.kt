package ai.claudeflow.core.enrichment

import ai.claudeflow.core.rag.KnowledgeVectorService
import ai.claudeflow.core.storage.Storage
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * 프로젝트 컨텍스트 Enricher
 *
 * RAG 기반 시맨틱 검색과 패턴 매칭을 통해 프로젝트 컨텍스트를 주입합니다.
 *
 * 동작 방식:
 * 1. RAG 시맨틱 검색 (Qdrant + Ollama 임베딩)
 * 2. 패턴 기반 프로젝트명 탐지
 * 3. 프로젝트 디렉토리에서 CLAUDE.md, README.md 추출
 *
 * @see ContextEnricher
 */
class ProjectContextEnricher(
    private val knowledgeVectorService: KnowledgeVectorService?,
    private val storage: Storage,
    private val objectMapper: ObjectMapper,
    private val configPath: String? = null,
    private val workspaceRoot: String? = null
) : ContextEnricher {

    override val id: String = "project-context"
    override val name: String = "Project Context Enricher"
    override val priority: Int = 10  // 높은 우선순위 (먼저 실행)

    private val configDir: File by lazy {
        val path = configPath ?: "${System.getProperty("user.dir")}/.claude/config"
        File(path)
    }

    private val aliasesFile: File
        get() = File(configDir, "project-aliases.json")

    private val exampleFile: File
        get() = File(configDir, "project-aliases.example.json")

    /**
     * 항상 적용 (RAG 또는 패턴 매칭 시도)
     */
    override fun shouldEnrich(context: EnrichmentContext): Boolean {
        // 이미 프로젝트 컨텍스트가 주입된 경우 스킵
        return !context.hasContextOfType(EnricherType.PROJECT)
    }

    /**
     * 프로젝트 컨텍스트 주입
     */
    override suspend fun enrich(context: EnrichmentContext): EnrichmentContext {
        val prompt = context.originalPrompt
        var enrichedContext = context

        // 1. RAG 기반 시맨틱 검색
        val ragResult = searchProjectsWithRag(prompt)
        if (ragResult != null) {
            enrichedContext = enrichedContext.withAddedContext(
                enricherId = id,
                enricherType = EnricherType.PROJECT,
                content = ragResult.content,
                metadata = mapOf(
                    "source" to "rag",
                    "projectIds" to ragResult.projectIds,
                    "projectCount" to ragResult.projectIds.size
                )
            )

            // 작업 디렉토리 설정 (빈 경로 제외)
            ragResult.workingDirectory?.let { dir ->
                if (dir.isNotEmpty()) {
                    enrichedContext = enrichedContext.withWorkingDirectory(dir)
                }
            }

            logger.info { "RAG search found ${ragResult.projectIds.size} projects: ${ragResult.projectIds}" }
        }

        // 2. 패턴 기반 프로젝트 탐지 (RAG에서 못 찾은 경우 보완)
        val config = loadAliasesConfig()
        val detectedProjects = detectProjects(prompt, config)
        val ragProjectIds = ragResult?.projectIds?.toSet() ?: emptySet()

        for (detected in detectedProjects) {
            // RAG에서 이미 찾은 프로젝트는 스킵
            if (detected.projectId in ragProjectIds) continue

            val projectDir = findProjectDirectory(detected.projectId, config)
            if (projectDir != null && projectDir.exists()) {
                val projectContext = extractProjectContext(projectDir)
                if (projectContext.isNotEmpty()) {
                    val content = """
                        |[Detected Project: ${detected.projectId}]
                        |Path: ${projectDir.absolutePath}
                        |Matched: "${detected.matchedPattern}"
                        |--- Project Context ---
                        |$projectContext
                        |--- End Project Context ---
                    """.trimMargin()

                    enrichedContext = enrichedContext.withAddedContext(
                        enricherId = id,
                        enricherType = EnricherType.PROJECT,
                        content = content,
                        metadata = mapOf(
                            "source" to "pattern",
                            "projectId" to detected.projectId,
                            "path" to projectDir.absolutePath
                        )
                    )

                    // 작업 디렉토리 설정
                    enrichedContext = enrichedContext.withWorkingDirectory(projectDir.absolutePath)

                    logger.info { "Pattern matched project: ${detected.projectId}" }
                }
            }
        }

        return enrichedContext
    }

    // ==================== RAG 검색 ====================

    private fun searchProjectsWithRag(prompt: String): RagSearchResult? {
        if (knowledgeVectorService == null) return null

        return try {
            val results = knowledgeVectorService.searchProjects(prompt, topK = 3, minScore = 0.4f)
            if (results.isEmpty()) return null

            val contentParts = mutableListOf<String>()
            val projectIds = mutableListOf<String>()
            var workingDirectory: String? = null

            for (result in results) {
                when (result.type) {
                    KnowledgeVectorService.TYPE_PROJECT_LIST -> {
                        contentParts.add("""
                            |[Project List from RAG]
                            |${result.content}
                        """.trimMargin())
                        projectIds.add("project-list")
                    }
                    KnowledgeVectorService.TYPE_PROJECT -> {
                        contentParts.add("""
                            |[Project from RAG: ${result.docId}]
                            |Relevance: ${String.format("%.2f", result.score)}
                            |${result.content}
                        """.trimMargin())
                        projectIds.add(result.docId)

                        // 첫 번째 유효한 작업 디렉토리 사용
                        if (workingDirectory == null) {
                            val dir = result.metadata["working_directory"] as? String
                            if (!dir.isNullOrEmpty()) {
                                workingDirectory = dir
                            }
                        }
                    }
                }
            }

            if (contentParts.isEmpty()) return null

            RagSearchResult(
                content = contentParts.joinToString("\n\n"),
                projectIds = projectIds,
                workingDirectory = workingDirectory
            )
        } catch (e: Exception) {
            logger.warn { "RAG search failed: ${e.message}" }
            null
        }
    }

    // ==================== 패턴 기반 탐지 ====================

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
                    break
                }
            }
        }

        return detected
    }

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

        // 2. 대소문자 무시 검색
        root.listFiles()?.find {
            it.isDirectory && it.name.equals(projectId, ignoreCase = true)
        }?.let { return it }

        // 3. 하위 디렉토리 검색 (두 레벨)
        root.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory) {
                subDir.listFiles()?.find {
                    it.isDirectory && it.name.equals(projectId, ignoreCase = true)
                }?.let { return it }
            }
        }

        return null
    }

    // ==================== 컨텍스트 추출 ====================

    private fun extractProjectContext(projectDir: File): String {
        val parts = mutableListOf<String>()

        // 1. CLAUDE.md (최우선)
        val claudeMd = File(projectDir, "CLAUDE.md")
        if (claudeMd.exists()) {
            val content = claudeMd.readText().take(3000)
            parts.add("## CLAUDE.md\n$content")
        }

        // 2. README.md (CLAUDE.md 없을 때)
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

        // 4. 디렉토리 구조
        val structure = getDirectoryStructure(projectDir, maxDepth = 2)
        if (structure.isNotEmpty()) {
            parts.add("## Directory Structure\n```\n$structure\n```")
        }

        return parts.joinToString("\n\n")
    }

    private fun detectTechStack(projectDir: File): String {
        val stack = mutableListOf<String>()

        if (File(projectDir, "build.gradle.kts").exists() || File(projectDir, "build.gradle").exists()) {
            stack.add("Gradle")
        }
        if (File(projectDir, "pom.xml").exists()) stack.add("Maven")
        if (File(projectDir, "package.json").exists()) stack.add("Node.js")
        if (File(projectDir, "Cargo.toml").exists()) stack.add("Rust")
        if (File(projectDir, "go.mod").exists()) stack.add("Go")
        if (File(projectDir, "requirements.txt").exists() || File(projectDir, "pyproject.toml").exists()) {
            stack.add("Python")
        }

        projectDir.walkTopDown().maxDepth(3).find { it.extension == "kt" }?.let { stack.add("Kotlin") }
        projectDir.walkTopDown().maxDepth(3).find { it.extension == "java" }?.let { stack.add("Java") }

        return stack.joinToString(", ")
    }

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

    // ==================== 설정 로드 ====================

    private fun resolveWorkspaceRoot(configRoot: String): File {
        var resolved = configRoot
        if (resolved.contains("\${WORKSPACE_PATH")) {
            resolved = System.getenv("WORKSPACE_PATH") ?: System.getProperty("user.home") + "/projects"
        }
        if (resolved.contains("\$HOME")) {
            resolved = resolved.replace("\$HOME", System.getProperty("user.home"))
        }

        workspaceRoot?.let { return File(it) }
        return File(resolved)
    }

    private fun loadAliasesConfig(): ProjectAliasesConfig {
        // 1. DB에서 조회
        try {
            val dbAliases = storage.projectAliasRepository.findAllAsMap()
            if (dbAliases.isNotEmpty()) {
                val aliases = dbAliases.mapValues { (_, dto) ->
                    ProjectAlias(
                        patterns = dto.patterns,
                        description = dto.description
                    )
                }
                return ProjectAliasesConfig(
                    workspaceRoot = workspaceRoot ?: "\${WORKSPACE_PATH:-\$HOME/workspace}",
                    aliases = aliases
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load aliases from DB" }
        }

        // 2. 파일에서 로드
        val file = when {
            aliasesFile.exists() -> aliasesFile
            exampleFile.exists() -> exampleFile
            else -> return ProjectAliasesConfig()
        }

        return try {
            objectMapper.readValue(file)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse aliases config" }
            ProjectAliasesConfig()
        }
    }
}

// ==================== DTOs ====================

private data class RagSearchResult(
    val content: String,
    val projectIds: List<String>,
    val workingDirectory: String?
)

private data class DetectedProject(
    val projectId: String,
    val matchedPattern: String,
    val description: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ProjectAliasesConfig(
    val workspaceRoot: String = "\${WORKSPACE_PATH:-\$HOME/workspace}",
    val aliases: Map<String, ProjectAlias> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ProjectAlias(
    val patterns: List<String> = emptyList(),
    val description: String = ""
)
