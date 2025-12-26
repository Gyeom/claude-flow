package ai.claudeflow.core.knowledge

import java.time.Instant

/**
 * Design-Aware Code Review를 위한 API Spec 모델
 *
 * Figma 기획서에서 추출한 서버 개발 관점의 스펙:
 * - API 엔드포인트
 * - Request/Response 필드
 * - Validation 규칙
 * - 비즈니스 로직
 * - 에러 케이스
 */

/**
 * 화면별 API 스펙 문서
 */
data class ScreenApiSpec(
    val screenId: String,              // Figma Frame ID
    val screenName: String,            // 화면명 (예: "로그인", "회원가입")
    val imageUrl: String?,             // 화면 스크린샷 URL
    val figmaFileKey: String,          // Figma 파일 키
    val projectId: String?,            // 프로젝트 ID

    // API 스펙
    val apis: List<ApiEndpointSpec>,
    val businessRules: List<String>,
    val validations: List<ValidationRule>,
    val uiStates: List<String>,        // UI 상태 (default, loading, error, success)
    val relatedScreens: List<String>,  // 연관 화면

    // 메타데이터
    val analyzedAt: Instant,
    val rawAnalysis: String? = null    // Vision AI 원본 응답 (디버깅용)
)

/**
 * API 엔드포인트 스펙
 */
data class ApiEndpointSpec(
    val method: String,                // GET, POST, PUT, DELETE, PATCH
    val path: String,                  // /auth/login, /users/{id}
    val description: String,           // API 설명
    val requestFields: List<FieldSpec>,
    val responseFields: List<FieldSpec>,
    val errorCases: List<ErrorCase>,
    val authRequired: Boolean = true,  // 인증 필요 여부
    val notes: List<String> = emptyList()
)

/**
 * Request/Response 필드 스펙
 */
data class FieldSpec(
    val name: String,                  // 필드명
    val type: String,                  // string, number, boolean, object, array
    val required: Boolean = false,
    val description: String? = null,
    val validations: List<String> = emptyList(),  // min:8, email_format, regex:xxx
    val example: String? = null
)

/**
 * Validation 규칙
 */
data class ValidationRule(
    val field: String,
    val rules: List<String>,           // required, email_format, min:8, max:100, regex:xxx
    val errorMessage: String? = null
)

/**
 * 에러 케이스
 */
data class ErrorCase(
    val code: Int,                     // HTTP 상태 코드
    val errorCode: String? = null,     // 애플리케이션 에러 코드 (예: AUTH_001)
    val condition: String,             // 발생 조건
    val message: String? = null        // 에러 메시지
)

/**
 * Vision AI 분석 요청 옵션
 */
data class FigmaAnalysisOptions(
    val analyzeWithVision: Boolean = true,    // Vision AI 분석 사용 여부
    val extractApiSpecs: Boolean = true,      // API 스펙 추출
    val extractValidations: Boolean = true,   // Validation 규칙 추출
    val extractBusinessRules: Boolean = true, // 비즈니스 규칙 추출
    val includeRawAnalysis: Boolean = false   // 원본 응답 포함 여부
)

/**
 * Figma 분석 Job 상태
 */
enum class FigmaJobStatus {
    PENDING,      // 대기 중
    PROCESSING,   // 분석 진행 중
    COMPLETED,    // 완료
    FAILED        // 실패
}

/**
 * Figma 분석 Job (비동기 배치 처리)
 */
data class FigmaAnalysisJob(
    val id: String,                           // Job ID (UUID)
    val figmaUrl: String,                     // 분석할 Figma URL
    val figmaFileKey: String,                 // Figma 파일 키
    val fileName: String,                     // 파일명
    val projectId: String?,                   // 프로젝트 ID

    // 상태
    val status: FigmaJobStatus,
    val progress: JobProgress,

    // 결과 (완료 시)
    val result: EnhancedFigmaAnalysisResult? = null,
    val errorMessage: String? = null,

    // 타임스탬프
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null
)

/**
 * Job 진행 상황
 */
data class JobProgress(
    val totalFrames: Int,                     // 전체 프레임 수
    val analyzedFrames: Int,                  // 분석 완료된 프레임 수
    val currentFrame: String? = null,         // 현재 분석 중인 프레임명
    val percentage: Int = if (totalFrames > 0) (analyzedFrames * 100 / totalFrames) else 0
)

/**
 * Figma 분석 결과 (향상된 버전)
 */
data class EnhancedFigmaAnalysisResult(
    val fileName: String,
    val fileKey: String,
    val lastModified: String,
    val totalFrames: Int,

    // API Spec 기반 분석 결과
    val screenSpecs: List<ScreenApiSpec>,

    // 기존 호환성
    val allTextContent: String,
    val comments: List<String>,

    // 통계
    val totalApis: Int,
    val totalValidations: Int,
    val totalBusinessRules: Int,
    val analyzedFrames: Int,
    val skippedFrames: Int,

    // 처리 시간
    val processingTimeMs: Long
)

/**
 * MR 리뷰용 Design Gap 분석 결과
 */
data class DesignGapAnalysis(
    val mrId: String,
    val project: String,
    val analyzedAt: Instant,

    // 매칭된 스펙
    val matchedSpecs: List<MatchedSpec>,

    // Gap 목록
    val gaps: List<DesignGap>,

    // 요약
    val summary: GapSummary
)

/**
 * 코드와 매칭된 스펙
 */
data class MatchedSpec(
    val codeFile: String,              // 변경된 파일
    val codeMethod: String?,           // 메서드명 (있으면)
    val designSpec: ScreenApiSpec,     // 매칭된 기획서 스펙
    val matchScore: Float              // 매칭 점수 (0.0 ~ 1.0)
)

/**
 * Design Gap (기획서와 코드 불일치)
 */
data class DesignGap(
    val type: GapType,
    val severity: GapSeverity,
    val title: String,
    val description: String,
    val codeLocation: String?,         // 코드 위치 (파일:라인)
    val designRef: String,             // 기획서 참조 (화면명)
    val suggestion: String? = null     // 수정 제안
)

enum class GapType {
    MISSING_API,              // API 미구현
    MISSING_VALIDATION,       // Validation 누락
    MISSING_BUSINESS_RULE,    // 비즈니스 규칙 미구현
    MISSING_ERROR_HANDLING,   // 에러 처리 누락
    FIELD_MISMATCH,           // 필드 불일치
    TYPE_MISMATCH,            // 타입 불일치
    EXTRA_IMPLEMENTATION      // 기획서에 없는 구현
}

enum class GapSeverity {
    HIGH,      // 핵심 기능 누락
    MEDIUM,    // 보조 기능 누락
    LOW,       // 권장 사항
    INFO       // 참고 정보
}

/**
 * Gap 요약
 */
data class GapSummary(
    val totalGaps: Int,
    val highSeverity: Int,
    val mediumSeverity: Int,
    val lowSeverity: Int,
    val byType: Map<GapType, Int>,
    val overallScore: Float            // 기획서 준수율 (0.0 ~ 1.0)
)
