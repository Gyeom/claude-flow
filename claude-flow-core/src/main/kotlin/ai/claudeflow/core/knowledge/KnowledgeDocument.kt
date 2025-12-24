package ai.claudeflow.core.knowledge

import java.time.Instant

/**
 * Knowledge Base 문서
 *
 * 파일 업로드, URL 수집, 이미지 등 다양한 소스의 지식을 관리합니다.
 */
data class KnowledgeDocument(
    val id: String,
    val title: String,
    val content: String,                    // 원본 또는 추출된 텍스트
    val source: SourceType,
    val sourceUrl: String? = null,          // URL, Confluence 링크 등
    val sourceFile: String? = null,         // 업로드된 파일 경로
    val mimeType: String? = null,           // image/png, application/pdf, etc.
    val status: IndexStatus = IndexStatus.PENDING,
    val chunkCount: Int = 0,
    val errorMessage: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val projectId: String? = null,          // 프로젝트별 지식 분리
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val lastIndexedAt: Instant? = null,
    val lastSyncedAt: Instant? = null       // URL 소스의 마지막 동기화 시간
)

/**
 * 문서 소스 타입
 */
enum class SourceType {
    UPLOAD,         // 파일 업로드
    URL,            // 웹 URL
    CONFLUENCE,     // Confluence 페이지
    NOTION,         // Notion 페이지
    FIGMA,          // Figma 디자인 파일
    IMAGE           // 이미지 (Figma 스크린샷 등)
}

/**
 * 인덱싱 상태
 */
enum class IndexStatus {
    PENDING,        // 대기 중
    PROCESSING,     // 처리 중
    INDEXED,        // 인덱싱 완료
    OUTDATED,       // 소스 변경됨, 재인덱싱 필요
    ERROR           // 오류 발생
}

/**
 * 문서 청크
 */
data class DocumentChunk(
    val id: String,
    val documentId: String,
    val content: String,
    val chunkIndex: Int,
    val metadata: Map<String, Any> = emptyMap(),
    val embedding: List<Float>? = null
)

/**
 * 이미지 분석 결과
 */
data class ImageAnalysisResult(
    val description: String,                // 전체 설명
    val extractedText: String?,             // OCR 텍스트
    val uiComponents: List<UIComponent>,    // UI 컴포넌트 목록
    val designSpecs: DesignSpecs?,          // 디자인 스펙
    val functionalSpecs: List<String>,      // 기능 명세
    val rawAnalysis: String                 // Claude 원본 응답
)

/**
 * UI 컴포넌트 정보
 */
data class UIComponent(
    val name: String,
    val type: String,           // Button, Input, Modal, etc.
    val description: String?,
    val properties: Map<String, String> = emptyMap()
)

/**
 * 디자인 스펙
 */
data class DesignSpecs(
    val colors: List<String>,
    val fonts: List<String>,
    val spacing: List<String>,
    val layout: String?
)

/**
 * Knowledge 통계
 */
data class KnowledgeStats(
    val totalDocuments: Int,
    val totalChunks: Int,
    val bySource: Map<SourceType, Int>,
    val byStatus: Map<IndexStatus, Int>,
    val recentQueries: Int,
    val lastUpdated: Instant?
)

/**
 * 문서 업로드 요청
 */
data class DocumentUploadRequest(
    val title: String? = null,
    val projectId: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * URL 수집 요청
 */
data class UrlFetchRequest(
    val url: String,
    val title: String? = null,
    val sourceType: SourceType = SourceType.URL,
    val projectId: String? = null,
    val autoSync: Boolean = false,          // 자동 동기화 여부
    val syncIntervalHours: Int = 24         // 동기화 주기
)

/**
 * Figma Frame 정보
 *
 * Figma 파일에서 추출한 각 Frame의 정보를 담습니다.
 * Vision AI 분석을 통해 시각적 컨텍스트까지 포함합니다.
 */
data class FigmaFrame(
    val id: String,                         // Figma node ID (예: "11250-126016")
    val name: String,                       // Frame 이름 (예: "대시보드_홈")
    val imageUrl: String?,                  // 렌더링된 이미지 URL (30일간 유효)
    val description: String?,               // Vision AI가 생성한 설명
    val textContent: String?,               // 추출된 텍스트 콘텐츠
    val uiComponents: List<String> = emptyList(),  // 감지된 UI 컴포넌트
    val functionalSpecs: List<String> = emptyList() // 기능 명세
)

/**
 * Figma 분석 결과
 *
 * Frame 이미지 + Vision 분석 + 모든 텍스트 노드 추출을 결합합니다.
 */
data class FigmaAnalysisResult(
    val fileName: String,
    val lastModified: String,
    val frames: List<FigmaFrame>,
    val comments: List<String> = emptyList(),
    val allTextContent: String = ""  // 모든 TEXT 노드에서 추출한 콘텐츠 (검색용)
)
