package ai.claudeflow.core.enrichment

/**
 * 컨텍스트 Enricher 인터페이스
 *
 * Chain of Responsibility 패턴 기반으로 프롬프트에 컨텍스트를 주입합니다.
 * 각 Enricher는 독립적으로 동작하며, Pipeline에 의해 순차적으로 실행됩니다.
 *
 * @see ContextEnrichmentPipeline
 * @see EnrichmentContext
 */
interface ContextEnricher {

    /**
     * Enricher 고유 ID
     */
    val id: String

    /**
     * Enricher 이름 (로깅/디버깅용)
     */
    val name: String

    /**
     * 실행 우선순위 (낮을수록 먼저 실행)
     * 기본값: 100
     */
    val priority: Int get() = 100

    /**
     * 이 Enricher가 현재 컨텍스트에 적용되어야 하는지 판단
     *
     * @param context 현재 컨텍스트
     * @return true면 enrich() 호출, false면 스킵
     */
    fun shouldEnrich(context: EnrichmentContext): Boolean

    /**
     * 컨텍스트를 풍부하게 만듦
     *
     * 주의: EnrichmentContext는 불변 객체이므로 새 객체를 반환해야 함
     *
     * @param context 현재 컨텍스트
     * @return 풍부해진 새 컨텍스트
     */
    suspend fun enrich(context: EnrichmentContext): EnrichmentContext
}

/**
 * Enricher 타입 분류
 */
enum class EnricherType {
    /** 프로젝트 관련 컨텍스트 (RAG 기반) */
    PROJECT,

    /** 사용자 규칙/선호도 */
    USER_RULES,

    /** 대화 히스토리 요약 */
    CONVERSATION_HISTORY,

    /** 팀/조직 가이드라인 */
    TEAM_GUIDELINES,

    /** 외부 도구 결과 */
    TOOL_OUTPUT,

    /** 기타 커스텀 */
    CUSTOM
}
