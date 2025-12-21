package ai.claudeflow.core.plugin

import ai.claudeflow.core.rag.CodeChunk
import ai.claudeflow.core.rag.CodeKnowledgeService
import ai.claudeflow.core.rag.ReviewGuideline
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * GitLab 플러그인
 *
 * GitLab API를 통한 MR, 이슈, 파이프라인 관리
 * RAG 기반 컨텍스트 인식 코드 리뷰 지원
 */
class GitLabPlugin(
    private val codeKnowledgeService: CodeKnowledgeService? = null
) : BasePlugin() {
    override val id = "gitlab"
    override val name = "GitLab"
    override val description = "GitLab MR, 이슈, 파이프라인 관리"

    override val commands = listOf(
        // 조회 명령어
        PluginCommand(
            name = "mr-list",
            description = "오픈된 MR 목록 조회",
            usage = "/gitlab mr-list [project]",
            examples = listOf("/gitlab mr-list", "/gitlab mr-list my-project")
        ),
        PluginCommand(
            name = "mr-info",
            description = "MR 상세 정보 조회",
            usage = "/gitlab mr-info <project> <mr_id>",
            examples = listOf("/gitlab mr-info my-project 123")
        ),
        PluginCommand(
            name = "pipeline-status",
            description = "파이프라인 상태 조회",
            usage = "/gitlab pipeline-status <project>",
            examples = listOf("/gitlab pipeline-status my-project")
        ),
        PluginCommand(
            name = "issues",
            description = "이슈 목록 조회",
            usage = "/gitlab issues [project] [state]",
            examples = listOf("/gitlab issues", "/gitlab issues my-project opened")
        ),
        // 쓰기 명령어
        PluginCommand(
            name = "create-branch",
            description = "새 브랜치 생성",
            usage = "/gitlab create-branch <project> <branch_name> [ref]",
            examples = listOf(
                "/gitlab create-branch my-project feature/AUTH-123",
                "/gitlab create-branch my-project hotfix/login-fix main"
            )
        ),
        PluginCommand(
            name = "commit",
            description = "파일 변경 후 커밋",
            usage = "/gitlab commit <project> <branch> <message> <file_path> <content>",
            examples = listOf("/gitlab commit my-project feature/test \"fix: 버그 수정\" src/main.kt \"코드 내용\"")
        ),
        PluginCommand(
            name = "create-mr",
            description = "Merge Request 생성",
            usage = "/gitlab create-mr <project> <source_branch> <target_branch> <title> [description]",
            examples = listOf("/gitlab create-mr my-project feature/AUTH-123 main \"feat: 로그인 기능 추가\"")
        ),
        // RAG 기반 리뷰 명령어
        PluginCommand(
            name = "mr-review",
            description = "MR을 RAG 기반으로 컨텍스트 인식 리뷰",
            usage = "/gitlab mr-review <project> <mr_id>",
            examples = listOf("/gitlab mr-review my-project 123")
        ),
        PluginCommand(
            name = "index-project",
            description = "프로젝트 코드를 RAG 인덱싱",
            usage = "/gitlab index-project <project> [branch]",
            examples = listOf("/gitlab index-project my-project", "/gitlab index-project my-project develop")
        ),
        PluginCommand(
            name = "knowledge-stats",
            description = "프로젝트 RAG 인덱싱 통계 조회",
            usage = "/gitlab knowledge-stats <project>",
            examples = listOf("/gitlab knowledge-stats my-project")
        )
    )

    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private lateinit var baseUrl: String
    private lateinit var token: String

    override suspend fun initialize(config: Map<String, String>) {
        super.initialize(config)
        baseUrl = requireConfig("GITLAB_URL").trimEnd('/')
        token = requireConfig("GITLAB_TOKEN")
        logger.info { "GitLab plugin initialized: $baseUrl" }
    }

    override fun shouldHandle(message: String): Boolean {
        val lower = message.lowercase()
        return lower.startsWith("/gitlab") ||
                lower.contains("mr ") && (lower.contains("목록") || lower.contains("리스트") || lower.contains("조회")) ||
                lower.contains("merge request") ||
                lower.contains("파이프라인") ||
                lower.contains("pipeline")
    }

    override suspend fun execute(command: String, args: Map<String, Any>): PluginResult {
        return when (command) {
            // 조회 명령어
            "mr-list" -> listMergeRequests(args["project"] as? String)
            "mr-info" -> getMergeRequestInfo(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                args["mr_id"] as? Int ?: return PluginResult(false, error = "MR ID required")
            )
            "pipeline-status" -> getPipelineStatus(
                args["project"] as? String ?: return PluginResult(false, error = "Project required")
            )
            "issues" -> listIssues(
                args["project"] as? String,
                args["state"] as? String ?: "opened"
            )
            // 쓰기 명령어
            "create-branch" -> createBranch(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                args["branch"] as? String ?: return PluginResult(false, error = "Branch name required"),
                args["ref"] as? String ?: "main"
            )
            "commit" -> createCommit(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                args["branch"] as? String ?: return PluginResult(false, error = "Branch required"),
                args["message"] as? String ?: return PluginResult(false, error = "Commit message required"),
                args["actions"] as? List<Map<String, String>> ?: return PluginResult(false, error = "Actions required")
            )
            "create-mr" -> createMergeRequest(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                args["source_branch"] as? String ?: return PluginResult(false, error = "Source branch required"),
                args["target_branch"] as? String ?: "main",
                args["title"] as? String ?: return PluginResult(false, error = "Title required"),
                args["description"] as? String
            )
            // RAG 기반 명령어
            "mr-review" -> reviewMergeRequestWithRag(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                args["mr_id"] as? Int ?: return PluginResult(false, error = "MR ID required")
            )
            "index-project" -> indexProjectToKnowledgeBase(
                args["project"] as? String ?: return PluginResult(false, error = "Project required"),
                args["branch"] as? String ?: "main"
            )
            "knowledge-stats" -> getKnowledgeStats(
                args["project"] as? String ?: return PluginResult(false, error = "Project required")
            )
            else -> PluginResult(false, error = "Unknown command: $command")
        }
    }

    private fun listMergeRequests(project: String?): PluginResult {
        val url = if (project != null) {
            "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests?state=opened&per_page=10"
        } else {
            "$baseUrl/api/v4/merge_requests?state=opened&scope=all&per_page=10"
        }

        return try {
            val response = apiGet(url)
            val mrs = mapper.readValue(response, List::class.java) as List<Map<String, Any>>

            val formatted = mrs.map { mr ->
                mapOf(
                    "iid" to mr["iid"],
                    "title" to mr["title"],
                    "author" to (mr["author"] as? Map<*, *>)?.get("name"),
                    "source_branch" to mr["source_branch"],
                    "target_branch" to mr["target_branch"],
                    "web_url" to mr["web_url"],
                    "created_at" to mr["created_at"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${mrs.size} open merge requests"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list MRs" }
            PluginResult(false, error = e.message)
        }
    }

    private fun getMergeRequestInfo(project: String, mrId: Int): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests/$mrId"

        return try {
            val response = apiGet(url)
            val mr = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val info = mapOf(
                "iid" to mr["iid"],
                "title" to mr["title"],
                "description" to mr["description"],
                "author" to (mr["author"] as? Map<*, *>)?.get("name"),
                "state" to mr["state"],
                "source_branch" to mr["source_branch"],
                "target_branch" to mr["target_branch"],
                "merge_status" to mr["merge_status"],
                "has_conflicts" to mr["has_conflicts"],
                "web_url" to mr["web_url"],
                "created_at" to mr["created_at"],
                "updated_at" to mr["updated_at"]
            )

            PluginResult(success = true, data = info)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get MR info" }
            PluginResult(false, error = e.message)
        }
    }

    private fun getPipelineStatus(project: String): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/pipelines?per_page=5"

        return try {
            val response = apiGet(url)
            val pipelines = mapper.readValue(response, List::class.java) as List<Map<String, Any>>

            val formatted = pipelines.map { pipeline ->
                mapOf(
                    "id" to pipeline["id"],
                    "status" to pipeline["status"],
                    "ref" to pipeline["ref"],
                    "sha" to (pipeline["sha"] as? String)?.take(8),
                    "web_url" to pipeline["web_url"],
                    "created_at" to pipeline["created_at"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${pipelines.size} recent pipelines"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get pipeline status" }
            PluginResult(false, error = e.message)
        }
    }

    private fun listIssues(project: String?, state: String): PluginResult {
        val url = if (project != null) {
            "$baseUrl/api/v4/projects/${encodeProject(project)}/issues?state=$state&per_page=10"
        } else {
            "$baseUrl/api/v4/issues?state=$state&scope=all&per_page=10"
        }

        return try {
            val response = apiGet(url)
            val issues = mapper.readValue(response, List::class.java) as List<Map<String, Any>>

            val formatted = issues.map { issue ->
                mapOf(
                    "iid" to issue["iid"],
                    "title" to issue["title"],
                    "state" to issue["state"],
                    "author" to (issue["author"] as? Map<*, *>)?.get("name"),
                    "labels" to issue["labels"],
                    "web_url" to issue["web_url"],
                    "created_at" to issue["created_at"]
                )
            }

            PluginResult(
                success = true,
                data = formatted,
                message = "Found ${issues.size} issues"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list issues" }
            PluginResult(false, error = e.message)
        }
    }

    // ============================================================
    // 쓰기 명령어 구현
    // ============================================================

    /**
     * 브랜치 생성
     * POST /api/v4/projects/:id/repository/branches
     */
    private fun createBranch(project: String, branchName: String, ref: String): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/repository/branches"
        val body = mapOf(
            "branch" to branchName,
            "ref" to ref
        )

        return try {
            val response = apiPost(url, body)
            val branch = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val result = mapOf(
                "name" to branch["name"],
                "commit" to (branch["commit"] as? Map<*, *>)?.let { commit ->
                    mapOf(
                        "id" to (commit["id"] as? String)?.take(8),
                        "message" to commit["message"]
                    )
                },
                "web_url" to branch["web_url"]
            )

            logger.info { "Created branch: $branchName from $ref in $project" }
            PluginResult(
                success = true,
                data = result,
                message = "브랜치 '$branchName'가 생성되었습니다 (base: $ref)"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create branch: $branchName" }
            PluginResult(false, error = "브랜치 생성 실패: ${e.message}")
        }
    }

    /**
     * 커밋 생성 (파일 추가/수정/삭제)
     * POST /api/v4/projects/:id/repository/commits
     *
     * actions 형식:
     * [
     *   { "action": "create|update|delete", "file_path": "...", "content": "..." }
     * ]
     */
    private fun createCommit(
        project: String,
        branch: String,
        message: String,
        actions: List<Map<String, String>>
    ): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/repository/commits"
        val body = mapOf(
            "branch" to branch,
            "commit_message" to message,
            "actions" to actions.map { action ->
                mapOf(
                    "action" to (action["action"] ?: "update"),
                    "file_path" to action["file_path"],
                    "content" to action["content"]
                )
            }
        )

        return try {
            val response = apiPost(url, body)
            val commit = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val result = mapOf(
                "id" to (commit["id"] as? String)?.take(8),
                "short_id" to commit["short_id"],
                "message" to commit["message"],
                "author_name" to commit["author_name"],
                "created_at" to commit["created_at"],
                "web_url" to commit["web_url"]
            )

            logger.info { "Created commit on $branch: ${commit["short_id"]}" }
            PluginResult(
                success = true,
                data = result,
                message = "커밋이 생성되었습니다: ${commit["short_id"]}"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create commit on $branch" }
            PluginResult(false, error = "커밋 생성 실패: ${e.message}")
        }
    }

    /**
     * Merge Request 생성
     * POST /api/v4/projects/:id/merge_requests
     */
    private fun createMergeRequest(
        project: String,
        sourceBranch: String,
        targetBranch: String,
        title: String,
        description: String?
    ): PluginResult {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests"
        val body = mutableMapOf(
            "source_branch" to sourceBranch,
            "target_branch" to targetBranch,
            "title" to title,
            "remove_source_branch" to true
        )
        if (!description.isNullOrBlank()) {
            body["description"] = description
        }

        return try {
            val response = apiPost(url, body)
            val mr = mapper.readValue(response, Map::class.java) as Map<String, Any>

            val result = mapOf(
                "iid" to mr["iid"],
                "title" to mr["title"],
                "state" to mr["state"],
                "source_branch" to mr["source_branch"],
                "target_branch" to mr["target_branch"],
                "web_url" to mr["web_url"],
                "created_at" to mr["created_at"]
            )

            logger.info { "Created MR !${mr["iid"]}: $title" }
            PluginResult(
                success = true,
                data = result,
                message = "MR이 생성되었습니다: !${mr["iid"]} - $title\n${mr["web_url"]}"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create MR: $title" }
            PluginResult(false, error = "MR 생성 실패: ${e.message}")
        }
    }

    // ============================================================
    // RAG 기반 코드 리뷰
    // ============================================================

    /**
     * MR을 RAG 기반으로 컨텍스트 인식 리뷰
     *
     * 1. MR 변경사항(diff) 가져오기
     * 2. 관련 코드베이스 검색 (벡터 유사도)
     * 3. 리뷰 가이드라인 생성
     * 4. 컨텍스트 기반 리뷰 포인트 반환
     */
    private fun reviewMergeRequestWithRag(project: String, mrId: Int): PluginResult {
        if (codeKnowledgeService == null) {
            return PluginResult(
                success = false,
                error = "RAG 서비스가 비활성화되어 있습니다. Qdrant/Ollama가 실행 중인지 확인하세요."
            )
        }

        return try {
            // 1. MR 정보 및 변경사항 가져오기
            val mrInfo = getMergeRequestDetails(project, mrId)
            val changes = getMergeRequestChanges(project, mrId)

            if (changes.isEmpty()) {
                return PluginResult(
                    success = true,
                    data = mapOf("review" to "변경사항이 없습니다."),
                    message = "MR에 변경사항이 없습니다."
                )
            }

            // 2. 변경된 파일들의 diff 분석
            val allDiffs = changes.map { change ->
                "${change["old_path"]} -> ${change["new_path"]}\n${change["diff"]}"
            }.joinToString("\n\n")

            // 3. 관련 코드베이스 검색 (RAG)
            val relatedCode = mutableListOf<CodeChunk>()
            for (change in changes.take(5)) {  // 최대 5개 파일만 분석
                val filePath = change["new_path"] as? String ?: continue
                val fileContext = codeKnowledgeService.findRelevantCode(
                    query = "file: $filePath code changes",
                    projectId = project,
                    topK = 3,
                    minScore = 0.5f
                )
                relatedCode.addAll(fileContext)
            }

            // 4. 리뷰 가이드라인 생성
            val guidelines = codeKnowledgeService.findReviewGuidelines(allDiffs, project)

            // 5. 리뷰 결과 구성
            val reviewResult = buildReviewResult(mrInfo, changes, relatedCode, guidelines)

            PluginResult(
                success = true,
                data = reviewResult,
                message = "MR !$mrId 리뷰가 완료되었습니다. ${guidelines.size}개의 가이드라인, ${relatedCode.size}개의 관련 코드 발견."
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to review MR !$mrId with RAG" }
            PluginResult(false, error = "MR 리뷰 실패: ${e.message}")
        }
    }

    /**
     * MR 상세 정보 (description 포함)
     */
    private fun getMergeRequestDetails(project: String, mrId: Int): Map<String, Any> {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests/$mrId"
        val response = apiGet(url)
        return mapper.readValue(response)
    }

    /**
     * MR 변경사항 (diff) 가져오기
     */
    private fun getMergeRequestChanges(project: String, mrId: Int): List<Map<String, Any>> {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/merge_requests/$mrId/changes"
        val response = apiGet(url)
        val result: Map<String, Any> = mapper.readValue(response)
        @Suppress("UNCHECKED_CAST")
        return result["changes"] as? List<Map<String, Any>> ?: emptyList()
    }

    /**
     * 리뷰 결과 구성
     */
    private fun buildReviewResult(
        mrInfo: Map<String, Any>,
        changes: List<Map<String, Any>>,
        relatedCode: List<CodeChunk>,
        guidelines: List<ReviewGuideline>
    ): Map<String, Any> {
        return mapOf(
            "mr" to mapOf(
                "iid" to mrInfo["iid"],
                "title" to mrInfo["title"],
                "author" to (mrInfo["author"] as? Map<*, *>)?.get("name"),
                "source_branch" to mrInfo["source_branch"],
                "target_branch" to mrInfo["target_branch"],
                "web_url" to mrInfo["web_url"]
            ),
            "summary" to mapOf(
                "files_changed" to changes.size,
                "additions" to changes.sumOf { (it["diff"] as? String)?.count { c -> c == '+' } ?: 0 },
                "deletions" to changes.sumOf { (it["diff"] as? String)?.count { c -> c == '-' } ?: 0 }
            ),
            "files" to changes.map { change ->
                mapOf(
                    "path" to change["new_path"],
                    "old_path" to change["old_path"],
                    "renamed" to change["renamed_file"],
                    "deleted" to change["deleted_file"],
                    "new_file" to change["new_file"]
                )
            },
            "guidelines" to guidelines.map { g ->
                mapOf(
                    "rule" to g.rule,
                    "category" to g.category,
                    "severity" to g.severity
                )
            },
            "related_code" to relatedCode.take(5).map { chunk ->
                mapOf(
                    "file" to chunk.filePath,
                    "lines" to "${chunk.startLine}-${chunk.endLine}",
                    "type" to chunk.chunkType,
                    "relevance" to "%.2f".format(chunk.score),
                    "preview" to chunk.contentPreview.take(100)
                )
            },
            "review_prompt" to generateReviewPrompt(mrInfo, changes, guidelines, relatedCode)
        )
    }

    /**
     * Claude에게 전달할 리뷰 프롬프트 생성
     */
    private fun generateReviewPrompt(
        mrInfo: Map<String, Any>,
        changes: List<Map<String, Any>>,
        guidelines: List<ReviewGuideline>,
        relatedCode: List<CodeChunk>
    ): String {
        val sb = StringBuilder()

        sb.appendLine("## MR 리뷰 요청")
        sb.appendLine("- 제목: ${mrInfo["title"]}")
        sb.appendLine("- 브랜치: ${mrInfo["source_branch"]} → ${mrInfo["target_branch"]}")
        sb.appendLine("- 변경 파일: ${changes.size}개")
        sb.appendLine()

        if (guidelines.isNotEmpty()) {
            sb.appendLine("## 자동 검출된 리뷰 포인트")
            for (g in guidelines) {
                val icon = when (g.severity) {
                    "error" -> "🚨"
                    "warning" -> "⚠️"
                    else -> "ℹ️"
                }
                sb.appendLine("$icon [${g.category}] ${g.rule}")
            }
            sb.appendLine()
        }

        if (relatedCode.isNotEmpty()) {
            sb.appendLine("## 관련 코드베이스 (RAG)")
            for (chunk in relatedCode.take(3)) {
                sb.appendLine("- ${chunk.filePath}:${chunk.startLine}-${chunk.endLine}")
                sb.appendLine("  ${chunk.contentPreview.take(80)}...")
            }
            sb.appendLine()
        }

        sb.appendLine("## 변경된 파일 목록")
        for (change in changes) {
            val status = when {
                change["new_file"] == true -> "[신규]"
                change["deleted_file"] == true -> "[삭제]"
                change["renamed_file"] == true -> "[이름변경]"
                else -> "[수정]"
            }
            sb.appendLine("$status ${change["new_path"]}")
        }

        return sb.toString()
    }

    /**
     * 프로젝트 코드를 RAG 지식 베이스에 인덱싱
     *
     * GitLab API로 프로젝트 파일 목록을 가져와 인덱싱
     */
    private fun indexProjectToKnowledgeBase(project: String, branch: String): PluginResult {
        if (codeKnowledgeService == null) {
            return PluginResult(
                success = false,
                error = "RAG 서비스가 비활성화되어 있습니다."
            )
        }

        return try {
            // 컬렉션 초기화
            codeKnowledgeService.initCollection()

            // 프로젝트 파일 트리 가져오기
            val files = getProjectFileTree(project, branch)

            if (files.isEmpty()) {
                return PluginResult(
                    success = true,
                    data = mapOf("indexed" to 0),
                    message = "인덱싱할 파일이 없습니다."
                )
            }

            var filesProcessed = 0
            var chunksIndexed = 0
            var errorCount = 0

            // 각 파일 내용 가져와서 인덱싱
            for (file in files.take(100)) {  // 최대 100개 파일
                val path = file["path"] as? String ?: continue
                val type = file["type"] as? String

                // blob (파일)만 처리
                if (type != "blob") continue

                // 지원하는 확장자만
                val ext = path.substringAfterLast(".", "")
                if (ext !in CodeKnowledgeService.SUPPORTED_EXTENSIONS) continue

                try {
                    val content = getFileContent(project, path, branch)
                    if (content.isNotBlank()) {
                        val chunks = indexFileContent(project, path, content)
                        if (chunks > 0) {
                            filesProcessed++
                            chunksIndexed += chunks
                        }
                    }
                } catch (e: Exception) {
                    logger.debug { "Failed to index file $path: ${e.message}" }
                    errorCount++
                }

                if (filesProcessed % 20 == 0 && filesProcessed > 0) {
                    logger.info { "Indexed $filesProcessed files ($chunksIndexed chunks) for $project..." }
                }
            }

            logger.info { "Project indexing complete: $filesProcessed files, $chunksIndexed chunks, $errorCount errors" }

            PluginResult(
                success = true,
                data = mapOf(
                    "project" to project,
                    "branch" to branch,
                    "files_indexed" to filesProcessed,
                    "chunks_created" to chunksIndexed,
                    "errors" to errorCount
                ),
                message = "프로젝트 '$project' 인덱싱 완료: ${filesProcessed}개 파일, ${chunksIndexed}개 청크"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to index project $project" }
            PluginResult(false, error = "프로젝트 인덱싱 실패: ${e.message}")
        }
    }

    /**
     * 프로젝트 파일 트리 조회
     */
    private fun getProjectFileTree(project: String, branch: String): List<Map<String, Any>> {
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/repository/tree" +
                "?ref=$branch&recursive=true&per_page=100"
        val response = apiGet(url)
        return mapper.readValue(response)
    }

    /**
     * 파일 내용 조회
     */
    private fun getFileContent(project: String, filePath: String, branch: String): String {
        val encodedPath = java.net.URLEncoder.encode(filePath, "UTF-8")
        val url = "$baseUrl/api/v4/projects/${encodeProject(project)}/repository/files/$encodedPath/raw?ref=$branch"
        return apiGet(url)
    }

    /**
     * 파일 내용 직접 인덱싱
     *
     * @return 인덱싱된 청크 수
     */
    private fun indexFileContent(projectId: String, filePath: String, content: String): Int {
        return codeKnowledgeService?.indexRemoteFile(projectId, filePath, content) ?: 0
    }

    /**
     * 프로젝트 RAG 인덱싱 통계 조회
     */
    private fun getKnowledgeStats(project: String): PluginResult {
        if (codeKnowledgeService == null) {
            return PluginResult(
                success = false,
                error = "RAG 서비스가 비활성화되어 있습니다."
            )
        }

        return try {
            val stats = codeKnowledgeService.getProjectStats(project)

            PluginResult(
                success = true,
                data = mapOf(
                    "project" to stats.projectId,
                    "total_chunks" to stats.totalChunks,
                    "last_updated" to stats.lastUpdated,
                    "rag_enabled" to true
                ),
                message = "프로젝트 '$project': ${stats.totalChunks}개 청크 인덱싱됨"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get knowledge stats for $project" }
            PluginResult(false, error = "통계 조회 실패: ${e.message}")
        }
    }

    // ============================================================
    // HTTP 유틸리티
    // ============================================================

    private fun apiGet(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("GitLab API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    private fun apiPost(url: String, body: Map<String, Any?>): String {
        val jsonBody = mapper.writeValueAsString(body)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("GitLab API error: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    private fun encodeProject(project: String): String {
        return java.net.URLEncoder.encode(project, "UTF-8")
    }
}
