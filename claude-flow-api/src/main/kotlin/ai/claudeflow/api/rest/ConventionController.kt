package ai.claudeflow.api.rest

import ai.claudeflow.api.service.ConventionService
import ai.claudeflow.api.service.ConventionScanRequest
import ai.claudeflow.api.service.ConventionFixRequest
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

/**
 * Convention 관리 API
 *
 * 프로젝트별 CONVENTION.md 기반 코드 스캔 및 자동 수정을 제공합니다.
 */
@RestController
@RequestMapping("/api/v1/convention")
class ConventionController(
    private val conventionService: ConventionService
) {

    /**
     * 프로젝트의 CONVENTION.md 조회
     */
    @GetMapping("/{projectId}")
    suspend fun getConvention(
        @PathVariable projectId: String
    ): ResponseEntity<ConventionResponse> {
        logger.info { "Getting convention for project: $projectId" }

        val result = conventionService.getConvention(projectId)
        return if (result != null) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * 프로젝트의 CONVENTION_VIOLATIONS.md 조회
     */
    @GetMapping("/{projectId}/violations")
    suspend fun getViolations(
        @PathVariable projectId: String
    ): ResponseEntity<ViolationsResponse> {
        logger.info { "Getting violations for project: $projectId" }

        val result = conventionService.getViolations(projectId)
        return ResponseEntity.ok(result)
    }

    /**
     * Convention 위반 스캔 실행
     *
     * Claude Code를 사용하여 프로젝트 코드를 스캔하고 위반 사항을 탐지합니다.
     */
    @PostMapping("/{projectId}/scan")
    suspend fun scanViolations(
        @PathVariable projectId: String,
        @RequestBody request: ConventionScanRequest = ConventionScanRequest()
    ): ResponseEntity<ScanResultResponse> {
        logger.info { "Scanning conventions for project: $projectId, scope: ${request.scope}" }

        val result = conventionService.scanViolations(projectId, request)
        return ResponseEntity.ok(result)
    }

    /**
     * 자동 수정 가능한 위반 사항 수정 및 MR 생성
     */
    @PostMapping("/{projectId}/fix")
    suspend fun fixViolations(
        @PathVariable projectId: String,
        @RequestBody request: ConventionFixRequest = ConventionFixRequest()
    ): ResponseEntity<FixResultResponse> {
        logger.info { "Fixing conventions for project: $projectId, violationIds: ${request.violationIds}" }

        val result = conventionService.fixViolations(projectId, request)
        return ResponseEntity.ok(result)
    }

    /**
     * Convention 활성화된 프로젝트 목록 조회
     */
    @GetMapping("/enabled-projects")
    suspend fun getEnabledProjects(): ResponseEntity<List<ConventionEnabledProject>> {
        logger.info { "Getting convention-enabled projects" }

        val projects = conventionService.getEnabledProjects()
        return ResponseEntity.ok(projects)
    }
}

// ==================== Response DTOs ====================

data class ConventionResponse(
    val projectId: String,
    val content: String,
    val rules: List<ConventionRule>,
    val lastModified: String?
)

data class ConventionRule(
    val id: String,
    val category: String,
    val text: String,
    val examples: List<String> = emptyList()
)

data class ViolationsResponse(
    val projectId: String,
    val content: String?,
    val violations: List<ViolationItem>,
    val summary: ViolationSummary,
    val lastScanned: String?
)

data class ViolationItem(
    val id: String,
    val file: String,
    val line: Int?,
    val rule: String,
    val severity: String,  // critical, warning, info
    val autoFixable: Boolean,
    val description: String,
    val suggestion: String?,
    val codeSnippet: String?,
    val status: String = "open"  // open, fixed, ignored
)

data class ViolationSummary(
    val total: Int,
    val critical: Int,
    val warning: Int,
    val info: Int,
    val autoFixable: Int,
    val fixed: Int
)

data class ScanResultResponse(
    val success: Boolean,
    val projectId: String,
    val violations: List<ViolationItem>,
    val summary: ViolationSummary,
    val scannedAt: String,
    val duration: Long,
    val message: String?
)

data class FixResultResponse(
    val success: Boolean,
    val projectId: String,
    val fixedCount: Int,
    val failedCount: Int,
    val mrUrl: String?,
    val branchName: String?,
    val fixedViolations: List<String>,
    val failedViolations: List<FailedFix>,
    val message: String?
)

data class FailedFix(
    val violationId: String,
    val reason: String
)

data class ConventionEnabledProject(
    val projectId: String,
    val projectName: String,
    val workingDirectory: String,
    val hasConventionMd: Boolean,
    val lastScanned: String?,
    val violationCount: Int
)
