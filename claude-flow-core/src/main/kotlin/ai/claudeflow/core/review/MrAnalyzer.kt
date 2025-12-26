package ai.claudeflow.core.review

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * MR(Merge Request) 분석기
 *
 * GitLab API 응답의 플래그를 직접 활용하여 효율적으로 MR을 분석합니다.
 *
 * ## Best Practice 적용
 * - API 응답의 `renamed_file`, `new_file`, `deleted_file` 플래그 직접 사용
 * - diff 텍스트 파싱 대신 구조화된 데이터 활용
 * - 단일 API 호출로 필요한 모든 정보 수집
 * - 2-Pass 리뷰 아키텍처 (규칙 기반 + AI 분석)
 */
class MrAnalyzer {

    private val objectMapper = jacksonObjectMapper()

    /**
     * GitLab /changes API 응답에서 MR 분석 결과 생성
     *
     * @param mrInfo MR 기본 정보 (/merge_requests/:iid)
     * @param changesResponse /changes API 응답
     * @return MrAnalysisResult
     */
    fun analyze(mrInfo: Map<String, Any>, changesResponse: Map<String, Any>): MrAnalysisResult {
        val changes = extractChanges(changesResponse)

        // Pass 1: 규칙 기반 빠른 분석 (GitLab API 플래그 활용)
        val fileAnalysis = analyzeFiles(changes)
        val quickIssues = detectQuickIssues(fileAnalysis, mrInfo)

        // Pass 2 준비: AI 분석용 컨텍스트 생성
        val reviewContext = buildReviewContext(mrInfo, fileAnalysis)

        return MrAnalysisResult(
            mrInfo = extractMrSummary(mrInfo),
            fileAnalysis = fileAnalysis,
            quickIssues = quickIssues,
            reviewContext = reviewContext,
            summary = generateSummary(fileAnalysis)
        )
    }

    /**
     * /changes 응답에서 변경 목록 추출
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractChanges(changesResponse: Map<String, Any>): List<Map<String, Any>> {
        return changesResponse["changes"] as? List<Map<String, Any>> ?: emptyList()
    }

    /**
     * 파일 분석 - GitLab API 플래그 직접 활용
     *
     * 핵심: renamed_file, new_file, deleted_file 플래그로 변경 유형 판단
     * diff 텍스트 파싱 불필요!
     */
    private fun analyzeFiles(changes: List<Map<String, Any>>): FileAnalysis {
        val renamed = mutableListOf<FileChange>()
        val added = mutableListOf<FileChange>()
        val deleted = mutableListOf<FileChange>()
        val modified = mutableListOf<FileChange>()

        for (change in changes) {
            val oldPath = change["old_path"] as? String ?: ""
            val newPath = change["new_path"] as? String ?: ""
            val diff = change["diff"] as? String ?: ""

            // GitLab API 플래그 직접 사용 (diff 파싱 불필요!)
            val isRenamed = change["renamed_file"] == true
            val isNew = change["new_file"] == true
            val isDeleted = change["deleted_file"] == true

            val fileChange = FileChange(
                oldPath = oldPath,
                newPath = newPath,
                changeType = when {
                    isRenamed -> ChangeType.RENAMED
                    isNew -> ChangeType.ADDED
                    isDeleted -> ChangeType.DELETED
                    else -> ChangeType.MODIFIED
                },
                additions = countLines(diff, '+'),
                deletions = countLines(diff, '-'),
                diffPreview = truncateDiff(diff, 500)
            )

            when (fileChange.changeType) {
                ChangeType.RENAMED -> renamed.add(fileChange)
                ChangeType.ADDED -> added.add(fileChange)
                ChangeType.DELETED -> deleted.add(fileChange)
                ChangeType.MODIFIED -> modified.add(fileChange)
            }
        }

        logger.info {
            "File analysis: ${renamed.size} renamed, ${added.size} added, " +
            "${deleted.size} deleted, ${modified.size} modified"
        }

        return FileAnalysis(
            renamed = renamed,
            added = added,
            deleted = deleted,
            modified = modified,
            totalFiles = changes.size,
            totalAdditions = (renamed + added + modified).sumOf { it.additions },
            totalDeletions = (renamed + deleted + modified).sumOf { it.deletions }
        )
    }

    /**
     * Pass 1: 규칙 기반 빠른 이슈 감지
     */
    private fun detectQuickIssues(
        analysis: FileAnalysis,
        mrInfo: Map<String, Any>
    ): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()

        // 1. 파일명 vs 클래스명 불일치 검사
        for (file in analysis.renamed) {
            val oldClassName = extractClassName(file.oldPath)
            val newClassName = extractClassName(file.newPath)
            if (oldClassName != newClassName) {
                // 클래스명이 변경되면 import/참조도 확인 필요
                issues.add(ReviewIssue(
                    severity = IssueSeverity.INFO,
                    category = "naming",
                    message = "파일명 변경: ${file.oldPath} → ${file.newPath}",
                    suggestion = "관련 import 문과 참조가 모두 업데이트되었는지 확인하세요"
                ))
            }
        }

        // 2. API 엔드포인트 변경 감지 (Breaking Change)
        val controllerFiles = (analysis.modified + analysis.renamed)
            .filter { it.newPath.contains("Controller") }

        for (file in controllerFiles) {
            if (file.diffPreview.contains("@RequestMapping") ||
                file.diffPreview.contains("@GetMapping") ||
                file.diffPreview.contains("@PostMapping")) {
                issues.add(ReviewIssue(
                    severity = IssueSeverity.WARNING,
                    category = "breaking-change",
                    message = "API 엔드포인트 변경 감지: ${file.newPath}",
                    suggestion = "API 버전 관리 및 클라이언트 호환성을 확인하세요"
                ))
            }
        }

        // 3. 테스트 파일 누락 검사
        val testFiles = (analysis.modified + analysis.added + analysis.renamed)
            .filter { it.newPath.contains("Test") || it.newPath.contains("test") }
        val sourceFiles = (analysis.modified + analysis.added + analysis.renamed)
            .filter { !it.newPath.contains("Test") && !it.newPath.contains("test") }
            .filter { it.newPath.endsWith(".kt") || it.newPath.endsWith(".java") }

        if (sourceFiles.isNotEmpty() && testFiles.isEmpty()) {
            issues.add(ReviewIssue(
                severity = IssueSeverity.INFO,
                category = "testing",
                message = "테스트 파일 변경 없음",
                suggestion = "변경된 코드에 대한 테스트를 추가하거나 업데이트하세요"
            ))
        }

        // 4. 보안 패턴 검사
        val securityPatterns = listOf(
            "password" to "비밀번호 노출 주의",
            "secret" to "비밀 정보 노출 주의",
            "token" to "토큰 노출 주의",
            "apiKey" to "API 키 노출 주의",
            "private_key" to "개인키 노출 주의"
        )

        for (file in analysis.modified + analysis.added) {
            val diffLower = file.diffPreview.lowercase()
            for ((pattern, message) in securityPatterns) {
                if (diffLower.contains(pattern)) {
                    issues.add(ReviewIssue(
                        severity = IssueSeverity.ERROR,
                        category = "security",
                        message = "$message (${file.newPath})",
                        suggestion = "민감 정보가 하드코딩되어 있지 않은지 확인하세요"
                    ))
                }
            }
        }

        // 5. 파일명/폴더명 일관성 검사 (예: Diagnosis vs Diagnostic)
        val allPaths = (analysis.renamed.map { it.newPath } +
                       analysis.added.map { it.newPath } +
                       analysis.modified.map { it.newPath })

        val inconsistencies = findNamingInconsistencies(allPaths)
        for (inconsistency in inconsistencies) {
            issues.add(ReviewIssue(
                severity = IssueSeverity.WARNING,
                category = "naming",
                message = "네이밍 불일치: $inconsistency",
                suggestion = "파일명/폴더명의 일관성을 확인하세요"
            ))
        }

        // 6. 대규모 변경 경고
        if (analysis.totalFiles > 15) {
            issues.add(ReviewIssue(
                severity = IssueSeverity.INFO,
                category = "size",
                message = "대규모 MR: ${analysis.totalFiles}개 파일 변경",
                suggestion = "MR을 더 작은 단위로 분리하는 것을 고려하세요"
            ))
        }

        return issues
    }

    /**
     * AI 리뷰용 컨텍스트 생성
     */
    private fun buildReviewContext(
        mrInfo: Map<String, Any>,
        analysis: FileAnalysis
    ): ReviewContext {
        val sb = StringBuilder()

        sb.appendLine("## MR 개요")
        sb.appendLine("- 제목: ${mrInfo["title"]}")
        sb.appendLine("- 브랜치: ${mrInfo["source_branch"]} → ${mrInfo["target_branch"]}")
        sb.appendLine("- 변경: ${analysis.totalFiles}개 파일 (+${analysis.totalAdditions}/-${analysis.totalDeletions})")
        sb.appendLine()

        sb.appendLine("## 변경 파일 분석")
        sb.appendLine("| 유형 | 파일 | 변경량 |")
        sb.appendLine("|------|------|--------|")

        // 파일명 변경 (가장 중요)
        for (file in analysis.renamed) {
            sb.appendLine("| ✏️ Rename | ${file.oldPath} → ${file.newPath} | +${file.additions}/-${file.deletions} |")
        }
        // 신규 파일
        for (file in analysis.added) {
            sb.appendLine("| ➕ Add | ${file.newPath} | +${file.additions} |")
        }
        // 삭제 파일
        for (file in analysis.deleted) {
            sb.appendLine("| ➖ Delete | ${file.oldPath} | -${file.deletions} |")
        }
        // 수정 파일
        for (file in analysis.modified) {
            sb.appendLine("| 📝 Modify | ${file.newPath} | +${file.additions}/-${file.deletions} |")
        }

        return ReviewContext(
            formattedPrompt = sb.toString(),
            priorityFiles = getPriorityFiles(analysis),
            changeTypes = mapOf(
                "renamed" to analysis.renamed.size,
                "added" to analysis.added.size,
                "deleted" to analysis.deleted.size,
                "modified" to analysis.modified.size
            )
        )
    }

    /**
     * 우선 리뷰 대상 파일 선정
     */
    private fun getPriorityFiles(analysis: FileAnalysis): List<String> {
        val priority = mutableListOf<String>()

        // 1. Controller/API 파일 (Breaking Change 가능)
        priority.addAll(
            (analysis.modified + analysis.renamed + analysis.added)
                .filter { it.newPath.contains("Controller") }
                .map { it.newPath }
        )

        // 2. Service/비즈니스 로직
        priority.addAll(
            (analysis.modified + analysis.renamed + analysis.added)
                .filter { it.newPath.contains("Service") || it.newPath.contains("UseCase") }
                .map { it.newPath }
        )

        // 3. Domain/Entity
        priority.addAll(
            (analysis.modified + analysis.renamed + analysis.added)
                .filter { it.newPath.contains("domain") || it.newPath.contains("entity") }
                .map { it.newPath }
        )

        return priority.distinct().take(10)
    }

    /**
     * 요약 생성
     */
    private fun generateSummary(analysis: FileAnalysis): String {
        val parts = mutableListOf<String>()

        if (analysis.renamed.isNotEmpty()) {
            parts.add("${analysis.renamed.size}개 파일명 변경")
        }
        if (analysis.added.isNotEmpty()) {
            parts.add("${analysis.added.size}개 파일 추가")
        }
        if (analysis.deleted.isNotEmpty()) {
            parts.add("${analysis.deleted.size}개 파일 삭제")
        }
        if (analysis.modified.isNotEmpty()) {
            parts.add("${analysis.modified.size}개 파일 수정")
        }

        return parts.joinToString(", ")
    }

    /**
     * MR 기본 정보 추출
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractMrSummary(mrInfo: Map<String, Any>): MrSummary {
        val author = mrInfo["author"] as? Map<String, Any>
        return MrSummary(
            iid = (mrInfo["iid"] as? Number)?.toInt() ?: 0,
            title = mrInfo["title"] as? String ?: "",
            description = mrInfo["description"] as? String,
            author = author?.get("name") as? String ?: author?.get("username") as? String ?: "unknown",
            sourceBranch = mrInfo["source_branch"] as? String ?: "",
            targetBranch = mrInfo["target_branch"] as? String ?: "",
            state = mrInfo["state"] as? String ?: "",
            webUrl = mrInfo["web_url"] as? String ?: ""
        )
    }

    /**
     * 파일 경로에서 클래스명 추출
     */
    private fun extractClassName(path: String): String {
        return path.substringAfterLast("/")
            .substringBeforeLast(".")
    }

    /**
     * 네이밍 불일치 찾기
     */
    private fun findNamingInconsistencies(paths: List<String>): List<String> {
        val issues = mutableListOf<String>()

        // 유사 단어 패턴 검사 (예: Diagnosis vs Diagnostic)
        val words = paths.flatMap { path ->
            path.split("/", ".", "_", "-")
                .filter { it.length > 3 }
                .map { it.lowercase() }
        }.toSet()

        // 유사어 그룹
        val similarGroups = listOf(
            setOf("diagnosis", "diagnostic", "diagnostics"),
            setOf("auth", "authentication", "authorize", "authorization"),
            setOf("config", "configuration", "configure"),
            setOf("util", "utility", "utilities"),
            setOf("impl", "implementation")
        )

        for (group in similarGroups) {
            val found = words.intersect(group)
            if (found.size > 1) {
                issues.add("${found.joinToString("/")} 혼용")
            }
        }

        return issues
    }

    /**
     * diff에서 라인 수 카운트
     */
    private fun countLines(diff: String, prefix: Char): Int {
        return diff.lines()
            .count { it.startsWith(prefix) && !it.startsWith("$prefix$prefix$prefix") }
    }

    /**
     * diff 미리보기 생성 (길이 제한)
     */
    private fun truncateDiff(diff: String, maxLength: Int): String {
        return if (diff.length > maxLength) {
            diff.take(maxLength) + "\n... (truncated)"
        } else {
            diff
        }
    }
}

// ===================== Data Classes =====================

/**
 * MR 분석 결과
 */
data class MrAnalysisResult(
    val mrInfo: MrSummary,
    val fileAnalysis: FileAnalysis,
    val quickIssues: List<ReviewIssue>,
    val reviewContext: ReviewContext,
    val summary: String
)

/**
 * MR 기본 정보
 */
data class MrSummary(
    val iid: Int,
    val title: String,
    val description: String?,
    val author: String,
    val sourceBranch: String,
    val targetBranch: String,
    val state: String,
    val webUrl: String
)

/**
 * 파일 분석 결과
 */
data class FileAnalysis(
    val renamed: List<FileChange>,
    val added: List<FileChange>,
    val deleted: List<FileChange>,
    val modified: List<FileChange>,
    val totalFiles: Int,
    val totalAdditions: Int,
    val totalDeletions: Int
)

/**
 * 개별 파일 변경 정보
 */
data class FileChange(
    val oldPath: String,
    val newPath: String,
    val changeType: ChangeType,
    val additions: Int,
    val deletions: Int,
    val diffPreview: String
)

/**
 * 변경 유형
 */
enum class ChangeType {
    RENAMED,   // 파일명 변경 (GitLab renamed_file=true)
    ADDED,     // 신규 파일 (GitLab new_file=true)
    DELETED,   // 삭제 파일 (GitLab deleted_file=true)
    MODIFIED   // 내용만 변경
}

/**
 * 빠른 이슈 감지 결과
 */
data class ReviewIssue(
    val severity: IssueSeverity,
    val category: String,
    val message: String,
    val suggestion: String? = null
)

/**
 * 이슈 심각도
 */
enum class IssueSeverity {
    ERROR,    // 즉시 수정 필요
    WARNING,  // 주의 필요
    INFO      // 참고 사항
}

/**
 * AI 리뷰용 컨텍스트
 */
data class ReviewContext(
    val formattedPrompt: String,
    val priorityFiles: List<String>,
    val changeTypes: Map<String, Int>
)
