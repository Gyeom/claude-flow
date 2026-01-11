package ai.claudeflow.api.service

import ai.claudeflow.api.rest.*
import ai.claudeflow.core.registry.ProjectRegistry
import ai.claudeflow.executor.ClaudeExecutor
import ai.claudeflow.executor.ExecutionRequest
import ai.claudeflow.executor.ExecutionStatus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Convention 서비스
 *
 * CONVENTION.md 기반 코드 스캔 및 자동 수정을 담당합니다.
 */
@Service
class ConventionService(
    private val projectRegistry: ProjectRegistry,
    private val claudeExecutor: ClaudeExecutor
) {
    private val objectMapper = jacksonObjectMapper()

    /**
     * 프로젝트의 CONVENTION.md 조회
     */
    fun getConvention(projectId: String): ConventionResponse? {
        val project = projectRegistry.get(projectId) ?: return null
        val workDir = File(project.workingDirectory)
        val conventionFile = File(workDir, "CONVENTION.md")

        if (!conventionFile.exists()) {
            logger.warn { "CONVENTION.md not found for project $projectId at ${conventionFile.absolutePath}" }
            return null
        }

        val content = conventionFile.readText()
        val rules = parseConventionRules(content)

        return ConventionResponse(
            projectId = projectId,
            content = content,
            rules = rules,
            lastModified = Instant.ofEpochMilli(conventionFile.lastModified()).toString()
        )
    }

    /**
     * 프로젝트의 CONVENTION_VIOLATIONS.md 조회
     */
    fun getViolations(projectId: String): ViolationsResponse {
        val project = projectRegistry.get(projectId)
            ?: return ViolationsResponse(
                projectId = projectId,
                content = null,
                violations = emptyList(),
                summary = ViolationSummary(0, 0, 0, 0, 0, 0),
                lastScanned = null
            )

        val workDir = File(project.workingDirectory)
        val violationsFile = File(workDir, "CONVENTION_VIOLATIONS.md")

        if (!violationsFile.exists()) {
            return ViolationsResponse(
                projectId = projectId,
                content = null,
                violations = emptyList(),
                summary = ViolationSummary(0, 0, 0, 0, 0, 0),
                lastScanned = null
            )
        }

        val content = violationsFile.readText()
        val violations = parseViolationsFromMarkdown(content)
        val summary = calculateSummary(violations)

        return ViolationsResponse(
            projectId = projectId,
            content = content,
            violations = violations,
            summary = summary,
            lastScanned = Instant.ofEpochMilli(violationsFile.lastModified()).toString()
        )
    }

    /**
     * Convention 위반 스캔 실행
     */
    suspend fun scanViolations(projectId: String, request: ConventionScanRequest): ScanResultResponse {
        val startTime = System.currentTimeMillis()

        val project = projectRegistry.get(projectId)
            ?: return ScanResultResponse(
                success = false,
                projectId = projectId,
                violations = emptyList(),
                summary = ViolationSummary(0, 0, 0, 0, 0, 0),
                scannedAt = Instant.now().toString(),
                duration = 0,
                message = "Project not found: $projectId"
            )

        val workDir = File(project.workingDirectory)
        val conventionFile = File(workDir, "CONVENTION.md")

        if (!conventionFile.exists()) {
            return ScanResultResponse(
                success = false,
                projectId = projectId,
                violations = emptyList(),
                summary = ViolationSummary(0, 0, 0, 0, 0, 0),
                scannedAt = Instant.now().toString(),
                duration = 0,
                message = "CONVENTION.md not found in project"
            )
        }

        val conventionContent = conventionFile.readText()

        // Claude Code로 스캔 실행
        val scanPrompt = buildScanPrompt(conventionContent, request.scope)

        try {
            val result = claudeExecutor.execute(
                ExecutionRequest(
                    prompt = scanPrompt,
                    workingDirectory = project.workingDirectory,
                    userId = "convention-scanner"
                )
            )

            if (result.status != ExecutionStatus.SUCCESS) {
                return ScanResultResponse(
                    success = false,
                    projectId = projectId,
                    violations = emptyList(),
                    summary = ViolationSummary(0, 0, 0, 0, 0, 0),
                    scannedAt = Instant.now().toString(),
                    duration = System.currentTimeMillis() - startTime,
                    message = "Scan failed: ${result.error}"
                )
            }

            // 결과 파싱
            val violations = parseClaudeScanResult(result.result ?: "")
            val summary = calculateSummary(violations)

            // CONVENTION_VIOLATIONS.md 업데이트
            updateViolationsFile(workDir, violations, summary)

            return ScanResultResponse(
                success = true,
                projectId = projectId,
                violations = violations,
                summary = summary,
                scannedAt = Instant.now().toString(),
                duration = System.currentTimeMillis() - startTime,
                message = "Scan completed successfully"
            )

        } catch (e: Exception) {
            logger.error(e) { "Convention scan failed for $projectId" }
            return ScanResultResponse(
                success = false,
                projectId = projectId,
                violations = emptyList(),
                summary = ViolationSummary(0, 0, 0, 0, 0, 0),
                scannedAt = Instant.now().toString(),
                duration = System.currentTimeMillis() - startTime,
                message = "Scan error: ${e.message}"
            )
        }
    }

    /**
     * 자동 수정 실행 및 MR 생성
     */
    suspend fun fixViolations(projectId: String, request: ConventionFixRequest): FixResultResponse {
        val project = projectRegistry.get(projectId)
            ?: return FixResultResponse(
                success = false,
                projectId = projectId,
                fixedCount = 0,
                failedCount = 0,
                mrUrl = null,
                branchName = null,
                fixedViolations = emptyList(),
                failedViolations = emptyList(),
                message = "Project not found: $projectId"
            )

        // 위반 사항 조회
        val violationsResponse = getViolations(projectId)
        val targetViolations = if (request.violationIds.isNotEmpty()) {
            violationsResponse.violations.filter { it.id in request.violationIds }
        } else {
            // autoFixable인 것만 선택
            violationsResponse.violations.filter { it.autoFixable }
        }

        if (targetViolations.isEmpty()) {
            return FixResultResponse(
                success = true,
                projectId = projectId,
                fixedCount = 0,
                failedCount = 0,
                mrUrl = null,
                branchName = null,
                fixedViolations = emptyList(),
                failedViolations = emptyList(),
                message = "No auto-fixable violations found"
            )
        }

        // 수정 개수 제한 (한 번에 최대 5개)
        val limitedViolations = targetViolations.take(request.maxFixes)

        // Claude Code로 수정 실행
        val fixPrompt = buildFixPrompt(limitedViolations, project.defaultBranch)

        try {
            val result = claudeExecutor.execute(
                ExecutionRequest(
                    prompt = fixPrompt,
                    workingDirectory = project.workingDirectory,
                    userId = "convention-fixer"
                )
            )

            if (result.status != ExecutionStatus.SUCCESS) {
                return FixResultResponse(
                    success = false,
                    projectId = projectId,
                    fixedCount = 0,
                    failedCount = limitedViolations.size,
                    mrUrl = null,
                    branchName = null,
                    fixedViolations = emptyList(),
                    failedViolations = limitedViolations.map { FailedFix(it.id, result.error ?: "Unknown error") },
                    message = "Fix failed: ${result.error}"
                )
            }

            // 결과 파싱
            val fixResult = parseClaudeFixResult(result.result ?: "")

            return FixResultResponse(
                success = true,
                projectId = projectId,
                fixedCount = fixResult.fixedCount,
                failedCount = fixResult.failedCount,
                mrUrl = fixResult.mrUrl,
                branchName = fixResult.branchName,
                fixedViolations = fixResult.fixedViolations,
                failedViolations = fixResult.failedViolations,
                message = fixResult.message
            )

        } catch (e: Exception) {
            logger.error(e) { "Convention fix failed for $projectId" }
            return FixResultResponse(
                success = false,
                projectId = projectId,
                fixedCount = 0,
                failedCount = limitedViolations.size,
                mrUrl = null,
                branchName = null,
                fixedViolations = emptyList(),
                failedViolations = limitedViolations.map { FailedFix(it.id, e.message ?: "Unknown error") },
                message = "Fix error: ${e.message}"
            )
        }
    }

    /**
     * Convention 활성화된 프로젝트 목록 조회
     */
    fun getEnabledProjects(): List<ConventionEnabledProject> {
        return projectRegistry.listAll().mapNotNull { project ->
            val workDir = File(project.workingDirectory)
            if (!workDir.exists()) return@mapNotNull null

            val conventionFile = File(workDir, "CONVENTION.md")
            val violationsFile = File(workDir, "CONVENTION_VIOLATIONS.md")

            val violationCount = if (violationsFile.exists()) {
                val violations = parseViolationsFromMarkdown(violationsFile.readText())
                violations.count { it.status == "open" }
            } else 0

            ConventionEnabledProject(
                projectId = project.id,
                projectName = project.name,
                workingDirectory = project.workingDirectory,
                hasConventionMd = conventionFile.exists(),
                lastScanned = if (violationsFile.exists()) {
                    Instant.ofEpochMilli(violationsFile.lastModified()).toString()
                } else null,
                violationCount = violationCount
            )
        }.filter { it.hasConventionMd }
    }

    // ==================== Private Methods ====================

    private fun parseConventionRules(content: String): List<ConventionRule> {
        val rules = mutableListOf<ConventionRule>()
        var currentCategory = "general"
        var ruleIndex = 0

        content.lines().forEach { line ->
            when {
                line.startsWith("## ") -> {
                    currentCategory = line.removePrefix("## ").trim()
                }
                line.startsWith("- ") -> {
                    ruleIndex++
                    rules.add(ConventionRule(
                        id = "rule-$ruleIndex",
                        category = currentCategory,
                        text = line.removePrefix("- ").trim()
                    ))
                }
            }
        }

        return rules
    }

    private fun parseViolationsFromMarkdown(content: String): List<ViolationItem> {
        // 간단한 파싱 - 실제로는 더 정교한 파싱 필요
        val violations = mutableListOf<ViolationItem>()
        val regex = Regex("""### (CV-\d+): (.+)\n- \*\*파일\*\*: `(.+?)`(?::(\d+))?\n- \*\*규칙\*\*: (.+)\n- \*\*심각도\*\*: (.+)\n- \*\*자동수정\*\*: (.+)\n- \*\*상태\*\*: (.+)""")

        regex.findAll(content).forEach { match ->
            violations.add(ViolationItem(
                id = match.groupValues[1],
                description = match.groupValues[2],
                file = match.groupValues[3],
                line = match.groupValues[4].toIntOrNull(),
                rule = match.groupValues[5],
                severity = match.groupValues[6].lowercase(),
                autoFixable = match.groupValues[7].contains("가능"),
                status = if (match.groupValues[8].contains("해결")) "fixed" else "open",
                suggestion = null,
                codeSnippet = null
            ))
        }

        return violations
    }

    private fun calculateSummary(violations: List<ViolationItem>): ViolationSummary {
        return ViolationSummary(
            total = violations.size,
            critical = violations.count { it.severity == "critical" },
            warning = violations.count { it.severity == "warning" },
            info = violations.count { it.severity == "info" },
            autoFixable = violations.count { it.autoFixable && it.status == "open" },
            fixed = violations.count { it.status == "fixed" }
        )
    }

    private fun buildScanPrompt(conventionContent: String, scope: String): String {
        return """
## Convention 검사 요청

### 컨벤션 규칙
$conventionContent

### 스캔 범위
$scope

### 판단 기준

각 위반을 발견하면, 다음 기준으로 **자동 수정 가능 여부**를 판단하세요:

**✅ 자동 수정 (autoFixable: true)**
- 네이밍 변경 (대소문자, 케이스 변환)
- 포맷팅 (들여쓰기, 공백, 줄바꿈)
- 단순 코드 변환 (var→val, !!→?.let)
- import 정리
- 불필요한 코드 제거

**❌ 수동 검토 필요 (autoFixable: false)**
- 아키텍처 변경 (레이어 분리, 클래스 추출)
- 비즈니스 로직 이동
- 설계 결정이 필요한 리팩토링
- 테스트 영향 범위가 큰 변경

### 심각도 기준

**critical**: 버그 가능성, 보안 이슈, 아키텍처 위반
**warning**: 유지보수성 저하, 가독성 문제
**info**: 스타일 권장사항, 개선 제안

### 출력 형식 (JSON)

```json
{
  "violations": [
    {
      "id": "CV-001",
      "file": "src/service/UserService.kt",
      "line": 45,
      "rule": "var 대신 val 우선 사용",
      "severity": "warning",
      "autoFixable": true,
      "description": "변경되지 않는 변수에 var 사용",
      "suggestion": "var userCount → val userCount",
      "codeSnippet": "var userCount = 0"
    }
  ]
}
```

프로젝트 전체를 스캔하고 위반 사항을 JSON 형식으로 출력해주세요.
src, main 디렉토리의 Kotlin 파일을 대상으로 합니다.
        """.trimIndent()
    }

    private fun buildFixPrompt(violations: List<ViolationItem>, baseBranch: String): String {
        val violationsJson = objectMapper.writeValueAsString(violations)
        val timestamp = System.currentTimeMillis()

        return """
## Convention 위반 자동 수정

### 수정 대상
$violationsJson

### 작업 순서

1. 현재 브랜치 확인 및 develop 최신화
   ```bash
   git checkout $baseBranch
   git pull origin $baseBranch
   ```

2. 새 브랜치 생성
   ```bash
   git checkout -b refactor/convention-fix-$timestamp
   ```

3. 각 위반 항목 수정
   - 파일을 열어 해당 라인 수정
   - 수정 내용이 정확한지 확인

4. 변경사항 커밋
   ```bash
   git add -A
   git commit -m "refactor: fix convention violations

   Fixed violations:
   ${violations.joinToString("\n") { "- ${it.id}: ${it.description}" }}

   🤖 Generated with Claude Code"
   ```

5. 푸시 및 MR 생성
   ```bash
   git push origin refactor/convention-fix-$timestamp
   ```

   gh 또는 glab 명령어로 MR 생성

### 출력 형식 (JSON)

수정 완료 후 다음 형식으로 결과를 출력해주세요:

```json
{
  "fixedCount": 3,
  "failedCount": 0,
  "branchName": "refactor/convention-fix-xxx",
  "mrUrl": "https://gitlab.../merge_requests/123",
  "fixedViolations": ["CV-001", "CV-002", "CV-003"],
  "failedViolations": [],
  "message": "Successfully fixed 3 violations"
}
```
        """.trimIndent()
    }

    private fun updateViolationsFile(workDir: File, violations: List<ViolationItem>, summary: ViolationSummary) {
        val violationsFile = File(workDir, "CONVENTION_VIOLATIONS.md")
        val content = buildViolationsMarkdown(violations, summary)
        violationsFile.writeText(content)
        logger.info { "Updated CONVENTION_VIOLATIONS.md with ${violations.size} violations" }
    }

    private fun buildViolationsMarkdown(violations: List<ViolationItem>, summary: ViolationSummary): String {
        return buildString {
            appendLine("# Convention Violations")
            appendLine()
            appendLine("> 자동 생성됨: ${Instant.now()}")
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine("| 구분 | 개수 |")
            appendLine("|------|------|")
            appendLine("| 🔴 Critical | ${summary.critical} |")
            appendLine("| 🟡 Warning | ${summary.warning} |")
            appendLine("| ℹ️ Info | ${summary.info} |")
            appendLine("| ✅ Auto-fixable | ${summary.autoFixable} |")
            appendLine("| ✓ Fixed | ${summary.fixed} |")
            appendLine()

            // Critical
            val critical = violations.filter { it.severity == "critical" && it.status == "open" }
            if (critical.isNotEmpty()) {
                appendLine("## Critical (즉시 수정 필요)")
                appendLine()
                critical.forEach { v ->
                    appendLine("### ${v.id}: ${v.description}")
                    appendLine("- **파일**: `${v.file}${v.line?.let { ":$it" } ?: ""}`")
                    appendLine("- **규칙**: ${v.rule}")
                    appendLine("- **심각도**: critical")
                    appendLine("- **자동수정**: ${if (v.autoFixable) "가능" else "불가"}")
                    appendLine("- **상태**: 🔴 미해결")
                    v.suggestion?.let { appendLine("- **제안**: $it") }
                    appendLine()
                }
            }

            // Warning
            val warnings = violations.filter { it.severity == "warning" && it.status == "open" }
            if (warnings.isNotEmpty()) {
                appendLine("## Warning (리뷰 시 확인)")
                appendLine()
                warnings.forEach { v ->
                    appendLine("### ${v.id}: ${v.description}")
                    appendLine("- **파일**: `${v.file}${v.line?.let { ":$it" } ?: ""}`")
                    appendLine("- **규칙**: ${v.rule}")
                    appendLine("- **심각도**: warning")
                    appendLine("- **자동수정**: ${if (v.autoFixable) "가능" else "불가"}")
                    appendLine("- **상태**: 🟡 미해결")
                    v.suggestion?.let { appendLine("- **제안**: $it") }
                    appendLine()
                }
            }

            // Fixed
            val fixed = violations.filter { it.status == "fixed" }
            if (fixed.isNotEmpty()) {
                appendLine("## Recently Fixed")
                appendLine()
                fixed.forEach { v ->
                    appendLine("- ✅ ${v.id}: ${v.description} (`${v.file}`)")
                }
            }
        }
    }

    private fun parseClaudeScanResult(output: String): List<ViolationItem> {
        return try {
            // JSON 블록 추출
            val jsonMatch = Regex("""\{[\s\S]*"violations"[\s\S]*\}""").find(output)
            if (jsonMatch != null) {
                val result: ClaudeScanOutput = objectMapper.readValue(jsonMatch.value)
                result.violations.map { v ->
                    ViolationItem(
                        id = v.id,
                        file = v.file,
                        line = v.line,
                        rule = v.rule,
                        severity = v.severity,
                        autoFixable = v.autoFixable,
                        description = v.description,
                        suggestion = v.suggestion,
                        codeSnippet = v.codeSnippet,
                        status = "open"
                    )
                }
            } else {
                logger.warn { "Could not parse Claude scan result: no JSON found" }
                emptyList()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse Claude scan result" }
            emptyList()
        }
    }

    private fun parseClaudeFixResult(output: String): ClaudeFixOutput {
        return try {
            val jsonMatch = Regex("""\{[\s\S]*"fixedCount"[\s\S]*\}""").find(output)
            if (jsonMatch != null) {
                objectMapper.readValue(jsonMatch.value)
            } else {
                ClaudeFixOutput(
                    fixedCount = 0,
                    failedCount = 0,
                    branchName = null,
                    mrUrl = null,
                    fixedViolations = emptyList(),
                    failedViolations = emptyList(),
                    message = "Could not parse fix result"
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse Claude fix result" }
            ClaudeFixOutput(
                fixedCount = 0,
                failedCount = 0,
                branchName = null,
                mrUrl = null,
                fixedViolations = emptyList(),
                failedViolations = emptyList(),
                message = "Parse error: ${e.message}"
            )
        }
    }
}

// ==================== Request DTOs ====================

data class ConventionScanRequest(
    val scope: String = "full",  // full, changed, directory
    val directory: String? = null
)

data class ConventionFixRequest(
    val violationIds: List<String> = emptyList(),  // 빈 리스트면 모든 autoFixable 대상
    val maxFixes: Int = 5,
    val createMr: Boolean = true
)

// ==================== Claude Output DTOs ====================

private data class ClaudeScanOutput(
    val violations: List<ClaudeViolation> = emptyList()
)

private data class ClaudeViolation(
    val id: String,
    val file: String,
    val line: Int?,
    val rule: String,
    val severity: String,
    val autoFixable: Boolean,
    val description: String,
    val suggestion: String?,
    val codeSnippet: String?
)

private data class ClaudeFixOutput(
    val fixedCount: Int,
    val failedCount: Int,
    val branchName: String?,
    val mrUrl: String?,
    val fixedViolations: List<String>,
    val failedViolations: List<FailedFix>,
    val message: String?
)
