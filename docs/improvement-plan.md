# claude-flow 고도화 계획

## Phase 1: 안정화 (즉시)

### 1.1 에러 핸들링 강화
```kotlin
// 구조화된 에러 응답
data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, Any>? = null
)

@ControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ApiError> {
        logger.error("Unhandled exception", e)
        return ResponseEntity.status(500).body(
            ApiError("INTERNAL_ERROR", "내부 오류가 발생했습니다")
        )
    }
}
```

### 1.2 n8n 워크플로우 개선
- 모든 HTTP Request 노드에 `continueOnFail: true` 추가
- 에러 브랜치 추가로 graceful degradation
- 타임아웃 명시적 설정

### 1.3 Validation 추가
- 요청 DTO에 `@field:NotBlank`, `@field:Size` 등 추가
- 입력값 검증 실패 시 400 Bad Request 반환

---

## Phase 2: 영속성 (1주차)

### 2.1 SQLite 저장소 추가
```kotlin
// 실행 이력 저장
data class ExecutionRecord(
    val id: String,
    val prompt: String,
    val result: String?,
    val status: ExecutionStatus,
    val agentId: String,
    val projectId: String?,
    val userId: String,
    val channel: String,
    val threadTs: String?,
    val durationMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val cost: Double?,
    val createdAt: Instant
)

interface ExecutionRepository {
    fun save(record: ExecutionRecord)
    fun findById(id: String): ExecutionRecord?
    fun findByChannel(channel: String, limit: Int): List<ExecutionRecord>
    fun findRecent(limit: Int): List<ExecutionRecord>
}
```

### 2.2 프로젝트/채널 매핑 영속화
- 현재 in-memory → SQLite 테이블로 이전
- 서버 재시작 시에도 매핑 유지

---

## Phase 3: 피드백 시스템 (2주차)

### 3.1 리액션 핸들러 추가
```kotlin
// n8n에서 호출하는 피드백 API
@PostMapping("/feedback")
fun recordFeedback(@RequestBody request: FeedbackRequest): ResponseEntity<Unit> {
    // 실행 결과에 대한 사용자 피드백 저장
    // thumbsup, thumbsdown 등
}
```

### 3.2 n8n 피드백 워크플로우
- slack-feedback-handler.json 추가
- 리액션 이벤트 → API 호출 → 피드백 저장

---

## Phase 4: 시맨틱 라우팅 (3주차)

### 4.1 분류 파이프라인 개선
```kotlin
class AgentRouter(
    private val agents: List<Agent>,
    private val semanticSearch: SemanticSearchService? = null,
    private val llmClassifier: LlmClassifier? = null
) {
    fun route(message: String): AgentMatch {
        // 1. 키워드 매칭 (0.95 confidence)
        keywordMatch(message)?.let { return it }

        // 2. 시맨틱 검색 (if enabled)
        semanticSearch?.classify(message)?.let { return it }

        // 3. LLM 분류 (fallback)
        llmClassifier?.classify(message)?.let { return it }

        // 4. 기본 에이전트
        return AgentMatch(defaultAgent, 0.5, null)
    }
}
```

### 4.2 벡터 DB 연동
- Qdrant 또는 Chroma 사용
- 에이전트별 예제 임베딩 저장
- 유사도 기반 라우팅

---

## Phase 5: Rate Limiting (3주차)

### 5.1 프로젝트별 제한
```kotlin
@Component
class RateLimiter {
    private val limiters = ConcurrentHashMap<String, Bucket>()

    fun checkLimit(projectId: String): Boolean {
        val bucket = limiters.computeIfAbsent(projectId) {
            Bucket.builder()
                .addLimit(Bandwidth.simple(60, Duration.ofMinutes(1)))
                .build()
        }
        return bucket.tryConsume(1)
    }
}
```

### 5.2 사용량 초과 시 응답
- 429 Too Many Requests 반환
- Retry-After 헤더 포함

---

## Phase 6: 분석/대시보드 (4주차)

### 6.1 통계 API
```kotlin
@GetMapping("/stats")
fun getStats(): StatsResponse {
    return StatsResponse(
        totalExecutions = executionRepo.count(),
        successRate = executionRepo.successRate(),
        avgDuration = executionRepo.avgDuration(),
        tokenUsage = executionRepo.tokenUsage(),
        topAgents = executionRepo.topAgents(5),
        recentErrors = executionRepo.recentErrors(10)
    )
}
```

### 6.2 간단한 대시보드
- React/Next.js 기반 관리 UI
- 실행 이력 조회
- 프로젝트/에이전트 관리
- 통계 시각화

---

## Phase 7: 스케줄 작업 (5주차)

### 7.1 정기 리뷰 자동화
- GitLab MR 자동 리뷰 (1분 간격)
- Daily Scrum 리포트 (매일 아침)

### 7.2 n8n 스케줄 워크플로우
- mr-review-scheduler.json
- daily-report.json

---

## 구현 우선순위

| 순위 | 항목 | 난이도 | 효과 |
|------|------|--------|------|
| 1 | 에러 핸들링 강화 | 낮음 | 높음 |
| 2 | 데이터 영속성 | 중간 | 높음 |
| 3 | 피드백 시스템 | 중간 | 중간 |
| 4 | Rate Limiting | 낮음 | 중간 |
| 5 | 시맨틱 라우팅 | 높음 | 높음 |
| 6 | 대시보드 | 높음 | 중간 |
| 7 | 스케줄 작업 | 중간 | 낮음 |

---

## 참고: Claudio 주요 특징

1. **다단계 분류**: 키워드 → 시맨틱 → LLM → 폴백
2. **프로젝트별 Rate Limit**: governor 크레이트 사용
3. **피드백 루프**: 리액션 → 품질 메트릭 → 에이전트 개선
4. **구조화된 에러**: ApiError + 컨텍스트 팩토리 메서드
5. **8개 워크플로우**: 멘션, 메시지, 리액션, 피드백, 리뷰, 오토픽스, 리포트, 컨텍스트
