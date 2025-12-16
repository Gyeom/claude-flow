# Claude Flow RAG 구현 계획서

> 버전: 1.0
> 작성일: 2025-12-16
> 작성자: Claude Code

## 1. 개요

### 1.1 목적

Claude Flow 프로젝트에 RAG(Retrieval-Augmented Generation)를 도입하여 다음 목표를 달성합니다:

- **개인화된 응답**: 사용자별 과거 대화 컨텍스트를 활용한 맞춤형 응답
- **라우팅 정확도 향상**: 유사 질문 기반 에이전트 선택 최적화
- **지식 축적**: 팀 대화 히스토리를 검색 가능한 지식베이스로 구축
- **MR 리뷰 품질 개선**: 관련 코드/리뷰 이력 자동 참조

### 1.2 현재 상태 분석

```
┌─────────────────────────────────────────────────────────────┐
│                    현재 구현 상태                              │
├─────────────────────────────────────────────────────────────┤
│ ✅ 구현됨                                                    │
│    • Qdrant + Ollama 연동 (SemanticRouter)                  │
│    • 에이전트 예제 벡터 인덱싱                                │
│    • 한국어 최적화 라우팅 (초성, 동의어, 오타 교정)            │
│    • 사용자 컨텍스트 저장 (UserContextRepository)            │
│    • 대화 요약 (수동, 분산 잠금)                              │
│    • 피드백 추적 (thumbsup/thumbsdown)                       │
├─────────────────────────────────────────────────────────────┤
│ ❌ 미구현 (RAG 필요)                                         │
│    • 대화 히스토리 벡터화                                     │
│    • 유사 대화 시맨틱 검색                                    │
│    • 피드백 기반 라우팅 학습                                  │
│    • 자동 대화 요약 생성                                      │
│    • 코드베이스 지식 인덱싱                                   │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 기술 스택

| 구성요소 | 현재 | RAG 확장 |
|---------|------|----------|
| 벡터 DB | Qdrant (에이전트 전용) | Qdrant (대화/코드 추가) |
| 임베딩 | Ollama nomic-embed-text | 동일 (768차원) |
| 저장소 | SQLite | SQLite + 벡터 메타데이터 |
| 캐시 | Caffeine | 동일 + 임베딩 캐시 |

---

## 2. 아키텍처 설계

### 2.1 전체 RAG 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Slack / API 요청                             │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        RAG Pipeline                                  │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────────────────┐     │
│  │   Query     │───▶│   Retrieval  │───▶│    Augmentation     │     │
│  │  Analysis   │    │    Engine    │    │      Builder        │     │
│  └─────────────┘    └──────────────┘    └─────────────────────┘     │
│        │                   │                      │                  │
│        │                   ▼                      │                  │
│        │         ┌──────────────────┐            │                  │
│        │         │  Vector Search   │            │                  │
│        │         │  ┌────────────┐  │            │                  │
│        │         │  │  Qdrant    │  │            │                  │
│        │         │  │ Collections│  │            │                  │
│        │         │  └────────────┘  │            │                  │
│        │         └──────────────────┘            │                  │
│        │                   │                      │                  │
│        ▼                   ▼                      ▼                  │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                    Context Assembly                          │    │
│  │  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌──────────┐  │    │
│  │  │ User      │  │ Similar   │  │ Agent     │  │ Code     │  │    │
│  │  │ Context   │  │ Convos    │  │ Examples  │  │ Context  │  │    │
│  │  └───────────┘  └───────────┘  └───────────┘  └──────────┘  │    │
│  └─────────────────────────────────────────────────────────────┘    │
└───────────────────────────────────┬─────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Claude Executor (LLM)                            │
│                 (Augmented Prompt + Retrieved Context)               │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 Qdrant 컬렉션 설계

```yaml
Collections:
  # 1. 기존 - 에이전트 라우팅용
  claude-flow-agents:
    vectors:
      size: 768
      distance: Cosine
    payload:
      - agent_id: keyword
      - example_text: text
      - priority: integer

  # 2. 신규 - 대화 히스토리용
  claude-flow-conversations:
    vectors:
      size: 768
      distance: Cosine
    payload:
      - execution_id: keyword
      - user_id: keyword
      - agent_id: keyword
      - prompt_summary: text
      - feedback_score: float
      - created_at: datetime
    indexes:
      - user_id (필터링용)
      - created_at (시간 범위)

  # 3. 신규 - 코드/문서 지식베이스
  claude-flow-knowledge:
    vectors:
      size: 768
      distance: Cosine
    payload:
      - source_type: keyword (code/doc/wiki)
      - file_path: text
      - chunk_index: integer
      - content_preview: text
      - project: keyword
      - last_updated: datetime
```

### 2.3 데이터 흐름

```
[사용자 질문]
      │
      ▼
┌─────────────────┐
│ 1. 쿼리 임베딩   │ ─────────────────────────────────────┐
│   (Ollama)      │                                       │
└─────────────────┘                                       │
      │                                                   │
      ▼                                                   ▼
┌─────────────────┐                            ┌─────────────────┐
│ 2. 에이전트     │                            │ 3. 유사 대화    │
│    라우팅       │                            │    검색         │
│  (agents 컬렉션)│                            │ (conversations) │
└─────────────────┘                            └─────────────────┘
      │                                                   │
      │   ┌───────────────────────────────────────────────┘
      │   │
      ▼   ▼
┌─────────────────┐
│ 4. 컨텍스트     │
│    조합         │
│  • 사용자 정보  │
│  • 유사 대화    │
│  • 사용자 규칙  │
└─────────────────┘
      │
      ▼
┌─────────────────┐
│ 5. Claude 호출  │
│  (Augmented)    │
└─────────────────┘
      │
      ▼
┌─────────────────┐
│ 6. 응답 저장    │
│  & 인덱싱       │
└─────────────────┘
```

---

## 3. 구현 단계

### Phase 1: 대화 벡터화 기반 구축 (1주차)

#### 3.1.1 목표
- 모든 대화를 벡터 DB에 저장
- 유사 대화 검색 API 구현

#### 3.1.2 신규 컴포넌트

**ConversationVectorService.kt**
```kotlin
// 파일: claude-flow-core/src/main/kotlin/ai/claudeflow/core/rag/ConversationVectorService.kt

package ai.claudeflow.core.rag

import ai.claudeflow.core.storage.ExecutionRecord
import kotlinx.coroutines.flow.Flow

/**
 * 대화 벡터화 및 검색 서비스
 */
interface ConversationVectorService {

    /**
     * 실행 기록을 벡터화하여 저장
     */
    suspend fun indexExecution(execution: ExecutionRecord)

    /**
     * 배치 인덱싱 (기존 데이터 마이그레이션용)
     */
    suspend fun indexExecutions(executions: List<ExecutionRecord>): Int

    /**
     * 유사 대화 검색
     * @param query 검색 쿼리
     * @param userId 사용자 ID (선택적 필터)
     * @param topK 반환할 최대 개수
     * @param minScore 최소 유사도 점수 (0.0 ~ 1.0)
     */
    suspend fun findSimilarConversations(
        query: String,
        userId: String? = null,
        topK: Int = 5,
        minScore: Float = 0.6f
    ): List<SimilarConversation>

    /**
     * 사용자별 대화 요약 생성
     */
    suspend fun generateUserSummary(userId: String): String

    /**
     * 컬렉션 통계
     */
    suspend fun getStats(): VectorCollectionStats
}

data class SimilarConversation(
    val executionId: String,
    val prompt: String,
    val result: String,
    val agentId: String,
    val score: Float,
    val createdAt: String
)

data class VectorCollectionStats(
    val totalVectors: Long,
    val uniqueUsers: Int,
    val lastIndexedAt: String?
)
```

**ConversationVectorServiceImpl.kt**
```kotlin
// 파일: claude-flow-core/src/main/kotlin/ai/claudeflow/core/rag/ConversationVectorServiceImpl.kt

package ai.claudeflow.core.rag

import ai.claudeflow.core.routing.EmbeddingService
import ai.claudeflow.core.storage.ExecutionRecord
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class ConversationVectorServiceImpl(
    private val httpClient: HttpClient,
    private val embeddingService: EmbeddingService,
    private val qdrantUrl: String,
    private val collectionName: String = "claude-flow-conversations"
) : ConversationVectorService {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun indexExecution(execution: ExecutionRecord) {
        // 1. 프롬프트 + 결과 결합하여 임베딩 생성
        val textToEmbed = buildEmbeddingText(execution)
        val embedding = embeddingService.embed(textToEmbed)

        // 2. Qdrant에 저장
        val payload = buildJsonObject {
            put("execution_id", execution.id)
            put("user_id", execution.userId ?: "anonymous")
            put("agent_id", execution.agentId ?: "unknown")
            put("prompt_summary", execution.prompt.take(200))
            put("feedback_score", execution.feedbackScore ?: 0.0)
            put("created_at", execution.createdAt.toString())
        }

        httpClient.put("$qdrantUrl/collections/$collectionName/points") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("points") {
                    addJsonObject {
                        put("id", execution.id.hashCode().toLong())
                        putJsonArray("vector") {
                            embedding.forEach { add(it) }
                        }
                        put("payload", payload)
                    }
                }
            }.toString())
        }

        logger.debug("Indexed execution: ${execution.id}")
    }

    override suspend fun findSimilarConversations(
        query: String,
        userId: String?,
        topK: Int,
        minScore: Float
    ): List<SimilarConversation> {
        val queryEmbedding = embeddingService.embed(query)

        val filter = userId?.let {
            buildJsonObject {
                putJsonObject("must") {
                    putJsonObject("key") { put("user_id", it) }
                }
            }
        }

        val response = httpClient.post("$qdrantUrl/collections/$collectionName/points/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("vector") { queryEmbedding.forEach { add(it) } }
                put("limit", topK)
                put("score_threshold", minScore)
                put("with_payload", true)
                filter?.let { put("filter", it) }
            }.toString())
        }

        // 결과 파싱 및 반환
        return parseSearchResults(response.bodyAsText())
    }

    private fun buildEmbeddingText(execution: ExecutionRecord): String {
        return """
            질문: ${execution.prompt}
            답변: ${execution.result?.take(500) ?: ""}
        """.trimIndent()
    }

    // ... 나머지 구현
}
```

#### 3.1.3 데이터베이스 스키마 확장

```sql
-- 파일: claude-flow-core/src/main/resources/db/migration/V2__rag_support.sql

-- 벡터 인덱싱 상태 추적
CREATE TABLE IF NOT EXISTS vector_index_status (
    id TEXT PRIMARY KEY,
    execution_id TEXT NOT NULL UNIQUE,
    indexed_at TEXT NOT NULL,
    vector_id INTEGER,
    collection TEXT NOT NULL DEFAULT 'claude-flow-conversations',
    FOREIGN KEY (execution_id) REFERENCES executions(id)
);

CREATE INDEX idx_vector_status_execution ON vector_index_status(execution_id);

-- 사용자 라우팅 학습 데이터
CREATE TABLE IF NOT EXISTS user_routing_preferences (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    query_pattern TEXT NOT NULL,  -- 쿼리 임베딩 해시
    preferred_agent_id TEXT NOT NULL,
    success_count INTEGER DEFAULT 1,
    total_count INTEGER DEFAULT 1,
    last_used_at TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_routing_pref_user ON user_routing_preferences(user_id);
CREATE INDEX idx_routing_pref_pattern ON user_routing_preferences(query_pattern);
```

#### 3.1.4 작업 목록

| 순서 | 작업 | 파일 | 예상 시간 |
|------|------|------|-----------|
| 1 | Qdrant 컬렉션 생성 스크립트 | `scripts/setup-qdrant-collections.sh` | 1h |
| 2 | ConversationVectorService 인터페이스 | `core/rag/ConversationVectorService.kt` | 2h |
| 3 | ConversationVectorServiceImpl 구현 | `core/rag/ConversationVectorServiceImpl.kt` | 4h |
| 4 | DB 마이그레이션 스크립트 | `resources/db/migration/V2__rag_support.sql` | 1h |
| 5 | 기존 데이터 마이그레이션 Job | `app/job/VectorMigrationJob.kt` | 3h |
| 6 | 단위 테스트 작성 | `core/rag/ConversationVectorServiceTest.kt` | 2h |

---

### Phase 2: 컨텍스트 증강 시스템 (2주차)

#### 3.2.1 목표
- 사용자 질문에 관련 과거 대화 자동 포함
- 동적 프롬프트 구성

#### 3.2.2 신규 컴포넌트

**ContextAugmentationService.kt**
```kotlin
// 파일: claude-flow-core/src/main/kotlin/ai/claudeflow/core/rag/ContextAugmentationService.kt

package ai.claudeflow.core.rag

import ai.claudeflow.core.context.UserContextManager
import ai.claudeflow.core.storage.UserRule

/**
 * RAG 기반 컨텍스트 증강 서비스
 */
interface ContextAugmentationService {

    /**
     * 사용자 메시지에 대한 증강된 컨텍스트 생성
     */
    suspend fun buildAugmentedContext(
        userId: String,
        message: String,
        options: AugmentationOptions = AugmentationOptions()
    ): AugmentedContext
}

data class AugmentationOptions(
    val includeSimilarConversations: Boolean = true,
    val includeUserRules: Boolean = true,
    val includeUserSummary: Boolean = true,
    val maxSimilarConversations: Int = 3,
    val minSimilarityScore: Float = 0.65f
)

data class AugmentedContext(
    val systemPrompt: String,
    val relevantConversations: List<RelevantConversation>,
    val userRules: List<UserRule>,
    val userSummary: String?,
    val metadata: AugmentationMetadata
)

data class RelevantConversation(
    val question: String,
    val answer: String,
    val similarity: Float,
    val wasHelpful: Boolean?  // 피드백 기반
)

data class AugmentationMetadata(
    val retrievalTimeMs: Long,
    val totalCandidates: Int,
    val selectedCount: Int
)
```

**ContextAugmentationServiceImpl.kt**
```kotlin
// 파일: claude-flow-core/src/main/kotlin/ai/claudeflow/core/rag/ContextAugmentationServiceImpl.kt

package ai.claudeflow.core.rag

import ai.claudeflow.core.context.UserContextManager
import ai.claudeflow.core.storage.repository.UserContextRepository
import kotlin.system.measureTimeMillis

class ContextAugmentationServiceImpl(
    private val conversationVectorService: ConversationVectorService,
    private val userContextManager: UserContextManager,
    private val userContextRepository: UserContextRepository
) : ContextAugmentationService {

    override suspend fun buildAugmentedContext(
        userId: String,
        message: String,
        options: AugmentationOptions
    ): AugmentedContext {
        var similarConversations = emptyList<SimilarConversation>()
        var retrievalTimeMs = 0L

        // 1. 유사 대화 검색
        if (options.includeSimilarConversations) {
            retrievalTimeMs = measureTimeMillis {
                similarConversations = conversationVectorService.findSimilarConversations(
                    query = message,
                    userId = userId,
                    topK = options.maxSimilarConversations,
                    minScore = options.minSimilarityScore
                )
            }
        }

        // 2. 사용자 규칙 조회
        val userRules = if (options.includeUserRules) {
            userContextRepository.getUserRules(userId)
        } else emptyList()

        // 3. 사용자 요약 조회
        val userSummary = if (options.includeUserSummary) {
            userContextRepository.findById(userId)?.summary
        } else null

        // 4. 시스템 프롬프트 구성
        val systemPrompt = buildSystemPrompt(
            userId = userId,
            similarConversations = similarConversations,
            userRules = userRules,
            userSummary = userSummary
        )

        return AugmentedContext(
            systemPrompt = systemPrompt,
            relevantConversations = similarConversations.map { conv ->
                RelevantConversation(
                    question = conv.prompt,
                    answer = conv.result,
                    similarity = conv.score,
                    wasHelpful = null // 피드백 조회 필요
                )
            },
            userRules = userRules,
            userSummary = userSummary,
            metadata = AugmentationMetadata(
                retrievalTimeMs = retrievalTimeMs,
                totalCandidates = similarConversations.size,
                selectedCount = similarConversations.size
            )
        )
    }

    private fun buildSystemPrompt(
        userId: String,
        similarConversations: List<SimilarConversation>,
        userRules: List<UserRule>,
        userSummary: String?
    ): String {
        return buildString {
            appendLine("## 사용자 컨텍스트")

            // 사용자 요약
            userSummary?.let {
                appendLine("\n### 사용자 배경")
                appendLine(it)
            }

            // 사용자 규칙
            if (userRules.isNotEmpty()) {
                appendLine("\n### 적용할 규칙")
                userRules.forEach { rule ->
                    appendLine("- ${rule.rule}")
                }
            }

            // 관련 대화
            if (similarConversations.isNotEmpty()) {
                appendLine("\n### 관련 이전 대화 (참고용)")
                similarConversations.forEachIndexed { index, conv ->
                    appendLine("\n**대화 ${index + 1}** (유사도: ${(conv.score * 100).toInt()}%)")
                    appendLine("Q: ${conv.prompt.take(200)}")
                    appendLine("A: ${conv.result.take(300)}")
                }
            }
        }
    }
}
```

#### 3.2.3 SessionContextBuilder 확장

```kotlin
// 파일: claude-flow-core/src/main/kotlin/ai/claudeflow/core/session/SessionContextBuilder.kt
// 기존 파일 수정

class SessionContextBuilder(
    private val userContextManager: UserContextManager,
    private val contextAugmentationService: ContextAugmentationService  // 추가
) {

    suspend fun buildContext(
        userId: String,
        message: String,
        sessionId: String?
    ): SessionContext {
        // 기존 로직 + RAG 증강
        val augmentedContext = contextAugmentationService.buildAugmentedContext(
            userId = userId,
            message = message
        )

        return SessionContext(
            userId = userId,
            sessionId = sessionId,
            systemPrompt = augmentedContext.systemPrompt,
            retrievedContext = augmentedContext.relevantConversations,
            // ...
        )
    }
}
```

#### 3.2.4 작업 목록

| 순서 | 작업 | 파일 | 예상 시간 |
|------|------|------|-----------|
| 1 | ContextAugmentationService 인터페이스 | `core/rag/ContextAugmentationService.kt` | 1h |
| 2 | ContextAugmentationServiceImpl 구현 | `core/rag/ContextAugmentationServiceImpl.kt` | 4h |
| 3 | SessionContextBuilder 확장 | `core/session/SessionContextBuilder.kt` | 2h |
| 4 | 프롬프트 템플릿 최적화 | `core/rag/PromptTemplates.kt` | 2h |
| 5 | 통합 테스트 | `core/rag/ContextAugmentationServiceTest.kt` | 2h |
| 6 | 설정 옵션 추가 | `app/config/RagConfiguration.kt` | 1h |

---

### Phase 3: 피드백 기반 라우팅 학습 (3주차)

#### 3.3.1 목표
- 사용자 피드백을 라우팅 결정에 반영
- 개인화된 에이전트 선호도 학습

#### 3.3.2 신규 컴포넌트

**FeedbackLearningService.kt**
```kotlin
// 파일: claude-flow-core/src/main/kotlin/ai/claudeflow/core/rag/FeedbackLearningService.kt

package ai.claudeflow.core.rag

/**
 * 피드백 기반 학습 서비스
 */
interface FeedbackLearningService {

    /**
     * 피드백 기록 및 학습
     */
    suspend fun recordFeedback(
        executionId: String,
        userId: String,
        agentId: String,
        isPositive: Boolean
    )

    /**
     * 사용자별 에이전트 선호도 점수 조회
     */
    suspend fun getAgentPreference(
        userId: String,
        queryEmbedding: FloatArray
    ): Map<String, Float>  // agentId -> preference score

    /**
     * 라우팅 점수 조정
     */
    suspend fun adjustRoutingScore(
        userId: String,
        agentId: String,
        baseScore: Float,
        queryEmbedding: FloatArray
    ): Float
}
```

**AgentRouter 확장**
```kotlin
// 파일: claude-flow-core/src/main/kotlin/ai/claudeflow/core/routing/AgentRouter.kt
// 기존 파일 수정 - route 메서드 확장

class AgentRouter(
    // ... 기존 의존성
    private val feedbackLearningService: FeedbackLearningService? = null
) {

    suspend fun route(message: String, userId: String?): RoutingResult {
        // 기존 라우팅 로직
        var result = performBaseRouting(message)

        // 피드백 기반 점수 조정
        if (userId != null && feedbackLearningService != null) {
            val queryEmbedding = embeddingService.embed(message)
            val adjustedConfidence = feedbackLearningService.adjustRoutingScore(
                userId = userId,
                agentId = result.agentId,
                baseScore = result.confidence,
                queryEmbedding = queryEmbedding
            )
            result = result.copy(
                confidence = adjustedConfidence,
                metadata = result.metadata + ("feedback_adjusted" to "true")
            )
        }

        return result
    }
}
```

#### 3.3.3 학습 알고리즘

```
피드백 기반 점수 조정 알고리즘:

1. 유사 쿼리 검색 (임베딩 유사도 > 0.8)
2. 해당 사용자의 과거 피드백 집계
3. 성공률 계산: success_rate = positive / total
4. 점수 조정:
   - 성공률 > 0.7: boost = 1.0 + (success_rate - 0.5) × 0.2
   - 성공률 < 0.3: penalty = 1.0 - (0.5 - success_rate) × 0.3
5. 최종 점수 = base_score × adjustment_factor
```

#### 3.3.4 작업 목록

| 순서 | 작업 | 파일 | 예상 시간 |
|------|------|------|-----------|
| 1 | FeedbackLearningService 인터페이스 | `core/rag/FeedbackLearningService.kt` | 1h |
| 2 | FeedbackLearningServiceImpl 구현 | `core/rag/FeedbackLearningServiceImpl.kt` | 4h |
| 3 | AgentRouter 피드백 통합 | `core/routing/AgentRouter.kt` | 3h |
| 4 | 피드백 집계 쿼리 최적화 | `core/storage/repository/FeedbackRepository.kt` | 2h |
| 5 | A/B 테스트 프레임워크 | `core/rag/ABTestingService.kt` | 3h |
| 6 | 메트릭 수집 | `core/analytics/RagAnalytics.kt` | 2h |

---

### Phase 4: 코드베이스 지식 RAG (4주차)

#### 3.4.1 목표
- GitLab 프로젝트 코드 인덱싱
- MR 리뷰 시 관련 코드 자동 참조

#### 3.4.2 신규 컴포넌트

**CodeKnowledgeService.kt**
```kotlin
// 파일: claude-flow-core/src/main/kotlin/ai/claudeflow/core/rag/CodeKnowledgeService.kt

package ai.claudeflow.core.rag

/**
 * 코드베이스 지식 검색 서비스
 */
interface CodeKnowledgeService {

    /**
     * 프로젝트 코드 인덱싱
     */
    suspend fun indexProject(
        projectId: String,
        gitlabUrl: String,
        branch: String = "main"
    ): IndexingResult

    /**
     * 관련 코드 검색
     */
    suspend fun findRelevantCode(
        query: String,
        projectId: String? = null,
        filePatterns: List<String> = emptyList(),
        topK: Int = 5
    ): List<CodeChunk>

    /**
     * 파일 변경사항과 관련된 리뷰 규칙 검색
     */
    suspend fun findReviewGuidelines(
        diffContent: String,
        projectId: String
    ): List<ReviewGuideline>
}

data class CodeChunk(
    val filePath: String,
    val content: String,
    val startLine: Int,
    val endLine: Int,
    val language: String,
    val score: Float
)

data class ReviewGuideline(
    val rule: String,
    val category: String,  // security, performance, style, etc.
    val severity: String,  // error, warning, info
    val applicablePatterns: List<String>
)

data class IndexingResult(
    val projectId: String,
    val filesIndexed: Int,
    val chunksCreated: Int,
    val durationMs: Long
)
```

#### 3.4.3 청킹 전략

```
코드 청킹 전략:

1. 파일 유형별 분할:
   - Kotlin/Java: 클래스/함수 단위
   - TypeScript/JS: 모듈/함수 단위
   - 설정 파일: 전체 파일

2. 청크 크기: 500-1500 토큰
3. 오버랩: 100 토큰 (문맥 유지)

4. 메타데이터:
   - file_path
   - language
   - chunk_type (class/function/config)
   - imports (의존성)
   - comments (문서화)
```

#### 3.4.4 MR 리뷰 통합

```kotlin
// 파일: claude-flow-core/src/main/kotlin/ai/claudeflow/core/plugin/GitLabMRReviewPlugin.kt
// 기존 플러그인 확장

class GitLabMRReviewPlugin(
    private val codeKnowledgeService: CodeKnowledgeService
) : Plugin {

    override suspend fun execute(command: String, args: Map<String, Any>): PluginResult {
        val mrId = args["mr_id"] as String
        val projectId = args["project_id"] as String

        // 1. MR diff 조회
        val diff = gitlabClient.getMRDiff(projectId, mrId)

        // 2. 관련 코드 검색 (RAG)
        val relevantCode = codeKnowledgeService.findRelevantCode(
            query = diff.summary,
            projectId = projectId
        )

        // 3. 리뷰 가이드라인 검색 (RAG)
        val guidelines = codeKnowledgeService.findReviewGuidelines(
            diffContent = diff.content,
            projectId = projectId
        )

        // 4. 증강된 프롬프트로 리뷰 생성
        val reviewPrompt = buildReviewPrompt(diff, relevantCode, guidelines)

        return executeReview(reviewPrompt)
    }
}
```

#### 3.4.5 작업 목록

| 순서 | 작업 | 파일 | 예상 시간 |
|------|------|------|-----------|
| 1 | CodeKnowledgeService 인터페이스 | `core/rag/CodeKnowledgeService.kt` | 1h |
| 2 | CodeChunker 구현 | `core/rag/CodeChunker.kt` | 4h |
| 3 | GitLab 코드 크롤러 | `core/rag/GitLabCodeCrawler.kt` | 3h |
| 4 | Qdrant knowledge 컬렉션 | `scripts/setup-knowledge-collection.sh` | 1h |
| 5 | MR 리뷰 플러그인 확장 | `core/plugin/GitLabMRReviewPlugin.kt` | 4h |
| 6 | 인덱싱 스케줄러 | `app/job/CodeIndexingJob.kt` | 2h |
| 7 | 통합 테스트 | `core/rag/CodeKnowledgeServiceTest.kt` | 2h |

---

## 4. API 설계

### 4.1 RAG 관련 REST API

```yaml
# 파일: claude-flow-api/src/main/resources/openapi/rag-api.yaml

openapi: 3.0.3
info:
  title: Claude Flow RAG API
  version: 1.0.0

paths:
  /api/v1/rag/search:
    post:
      summary: 유사 대화 검색
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                query:
                  type: string
                userId:
                  type: string
                topK:
                  type: integer
                  default: 5
                minScore:
                  type: number
                  default: 0.6
      responses:
        '200':
          description: 검색 결과
          content:
            application/json:
              schema:
                type: object
                properties:
                  results:
                    type: array
                    items:
                      $ref: '#/components/schemas/SimilarConversation'
                  metadata:
                    $ref: '#/components/schemas/SearchMetadata'

  /api/v1/rag/index:
    post:
      summary: 대화 인덱싱
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                executionId:
                  type: string
      responses:
        '202':
          description: 인덱싱 시작됨

  /api/v1/rag/stats:
    get:
      summary: RAG 통계
      responses:
        '200':
          description: 통계 정보
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/RagStats'

  /api/v1/rag/knowledge/index:
    post:
      summary: 코드베이스 인덱싱
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                projectId:
                  type: string
                gitlabUrl:
                  type: string
                branch:
                  type: string
                  default: main
      responses:
        '202':
          description: 인덱싱 시작됨

  /api/v1/rag/knowledge/search:
    post:
      summary: 코드 검색
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                query:
                  type: string
                projectId:
                  type: string
                filePatterns:
                  type: array
                  items:
                    type: string
                topK:
                  type: integer
                  default: 5
      responses:
        '200':
          description: 검색 결과

components:
  schemas:
    SimilarConversation:
      type: object
      properties:
        executionId:
          type: string
        prompt:
          type: string
        result:
          type: string
        score:
          type: number
        agentId:
          type: string
        createdAt:
          type: string
          format: date-time

    SearchMetadata:
      type: object
      properties:
        retrievalTimeMs:
          type: integer
        totalCandidates:
          type: integer
        selectedCount:
          type: integer

    RagStats:
      type: object
      properties:
        conversationsIndexed:
          type: integer
        knowledgeChunks:
          type: integer
        lastIndexedAt:
          type: string
          format: date-time
        collectionSizes:
          type: object
          additionalProperties:
            type: integer
```

### 4.2 Controller 구현

```kotlin
// 파일: claude-flow-api/src/main/kotlin/ai/claudeflow/api/rest/RagController.kt

package ai.claudeflow.api.rest

import ai.claudeflow.core.rag.*
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v1/rag")
class RagController(
    private val conversationVectorService: ConversationVectorService,
    private val codeKnowledgeService: CodeKnowledgeService
) {

    @PostMapping("/search")
    fun searchSimilarConversations(
        @RequestBody request: SearchRequest
    ): Mono<SearchResponse> = mono {
        val results = conversationVectorService.findSimilarConversations(
            query = request.query,
            userId = request.userId,
            topK = request.topK ?: 5,
            minScore = request.minScore ?: 0.6f
        )
        SearchResponse(results = results)
    }

    @PostMapping("/index")
    fun indexExecution(
        @RequestBody request: IndexRequest
    ): Mono<Unit> = mono {
        // 비동기 인덱싱
        conversationVectorService.indexExecution(request.executionId)
    }

    @GetMapping("/stats")
    fun getStats(): Mono<RagStatsResponse> = mono {
        val stats = conversationVectorService.getStats()
        RagStatsResponse(
            conversationsIndexed = stats.totalVectors,
            lastIndexedAt = stats.lastIndexedAt
        )
    }

    @PostMapping("/knowledge/search")
    fun searchCode(
        @RequestBody request: CodeSearchRequest
    ): Mono<CodeSearchResponse> = mono {
        val chunks = codeKnowledgeService.findRelevantCode(
            query = request.query,
            projectId = request.projectId,
            filePatterns = request.filePatterns ?: emptyList(),
            topK = request.topK ?: 5
        )
        CodeSearchResponse(chunks = chunks)
    }
}
```

---

## 5. 설정 및 배포

### 5.1 환경 변수

```bash
# 파일: docker-compose/.env.example (추가)

# RAG 설정
RAG_ENABLED=true
RAG_MIN_SIMILARITY_SCORE=0.65
RAG_MAX_SIMILAR_CONVERSATIONS=3
RAG_AUTO_INDEX=true

# Qdrant 설정
QDRANT_URL=http://qdrant:6333
QDRANT_API_KEY=

# Ollama 설정
OLLAMA_URL=http://ollama:11434
OLLAMA_EMBEDDING_MODEL=nomic-embed-text

# 코드 인덱싱 설정
CODE_INDEX_ENABLED=false
CODE_INDEX_CRON=0 0 2 * * *  # 매일 새벽 2시
CODE_INDEX_PROJECTS=  # 콤마로 구분된 프로젝트 ID
```

### 5.2 Docker Compose 확장

```yaml
# 파일: docker-compose/docker-compose.yml (추가)

services:
  # 기존 서비스...

  qdrant:
    image: qdrant/qdrant:v1.7.4
    ports:
      - "6333:6333"
      - "6334:6334"
    volumes:
      - qdrant_storage:/qdrant/storage
    environment:
      - QDRANT__SERVICE__GRPC_PORT=6334
    restart: unless-stopped

  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama_models:/root/.ollama
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: all
              capabilities: [gpu]
    restart: unless-stopped
    entrypoint: ["/bin/sh", "-c"]
    command: ["ollama serve & sleep 5 && ollama pull nomic-embed-text && wait"]

volumes:
  qdrant_storage:
  ollama_models:
```

### 5.3 초기화 스크립트

```bash
#!/bin/bash
# 파일: scripts/setup-rag.sh

set -e

QDRANT_URL=${QDRANT_URL:-http://localhost:6333}

echo "Setting up RAG collections..."

# 1. conversations 컬렉션 생성
curl -X PUT "$QDRANT_URL/collections/claude-flow-conversations" \
  -H "Content-Type: application/json" \
  -d '{
    "vectors": {
      "size": 768,
      "distance": "Cosine"
    },
    "optimizers_config": {
      "default_segment_number": 2
    },
    "replication_factor": 1
  }'

# 2. knowledge 컬렉션 생성
curl -X PUT "$QDRANT_URL/collections/claude-flow-knowledge" \
  -H "Content-Type: application/json" \
  -d '{
    "vectors": {
      "size": 768,
      "distance": "Cosine"
    },
    "optimizers_config": {
      "default_segment_number": 4
    },
    "replication_factor": 1
  }'

# 3. 인덱스 생성
curl -X PUT "$QDRANT_URL/collections/claude-flow-conversations/index" \
  -H "Content-Type: application/json" \
  -d '{
    "field_name": "user_id",
    "field_schema": "keyword"
  }'

curl -X PUT "$QDRANT_URL/collections/claude-flow-knowledge/index" \
  -H "Content-Type: application/json" \
  -d '{
    "field_name": "project_id",
    "field_schema": "keyword"
  }'

echo "RAG setup complete!"
```

---

## 6. 모니터링 및 메트릭

### 6.1 Prometheus 메트릭

```kotlin
// 파일: claude-flow-core/src/main/kotlin/ai/claudeflow/core/rag/RagMetrics.kt

package ai.claudeflow.core.rag

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

class RagMetrics(private val meterRegistry: MeterRegistry) {

    // 검색 지연 시간
    val searchLatency: Timer = Timer.builder("rag.search.latency")
        .description("RAG search latency")
        .tags("collection", "conversations")
        .register(meterRegistry)

    // 인덱싱 카운터
    val indexedCount = meterRegistry.counter(
        "rag.indexed.total",
        "type", "conversation"
    )

    // 캐시 히트율
    val cacheHitRate = meterRegistry.gauge(
        "rag.cache.hit_rate",
        AtomicDouble(0.0)
    )

    // 컬렉션 크기
    fun recordCollectionSize(collection: String, size: Long) {
        meterRegistry.gauge(
            "rag.collection.size",
            listOf(Tag.of("collection", collection)),
            size
        )
    }
}
```

### 6.2 대시보드 위젯

```typescript
// 파일: dashboard/src/components/RagStats.tsx

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../lib/api';

export function RagStats() {
  const { data: stats } = useQuery({
    queryKey: ['rag-stats'],
    queryFn: () => api.getRagStats(),
    refetchInterval: 30000
  });

  return (
    <div className="grid grid-cols-3 gap-4">
      <StatCard
        title="인덱싱된 대화"
        value={stats?.conversationsIndexed ?? 0}
        icon={<ChatIcon />}
      />
      <StatCard
        title="코드 청크"
        value={stats?.knowledgeChunks ?? 0}
        icon={<CodeIcon />}
      />
      <StatCard
        title="평균 검색 시간"
        value={`${stats?.avgSearchLatencyMs ?? 0}ms`}
        icon={<ClockIcon />}
      />
    </div>
  );
}
```

---

## 7. 테스트 전략

### 7.1 단위 테스트

```kotlin
// 파일: claude-flow-core/src/test/kotlin/ai/claudeflow/core/rag/ConversationVectorServiceTest.kt

package ai.claudeflow.core.rag

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.*

class ConversationVectorServiceTest : DescribeSpec({

    val mockHttpClient = mockk<HttpClient>()
    val mockEmbeddingService = mockk<EmbeddingService>()

    val service = ConversationVectorServiceImpl(
        httpClient = mockHttpClient,
        embeddingService = mockEmbeddingService,
        qdrantUrl = "http://localhost:6333"
    )

    describe("findSimilarConversations") {

        it("should return similar conversations above threshold") {
            // Given
            val query = "코드 리뷰 해줘"
            val embedding = FloatArray(768) { 0.1f }

            coEvery { mockEmbeddingService.embed(query) } returns embedding
            coEvery { mockHttpClient.post(any<String>()) } returns mockk {
                coEvery { bodyAsText() } returns """
                    {"result": [
                        {"id": 1, "score": 0.85, "payload": {"execution_id": "exec-1", "prompt_summary": "리뷰 요청"}}
                    ]}
                """.trimIndent()
            }

            // When
            val results = service.findSimilarConversations(query, topK = 5)

            // Then
            results shouldHaveSize 1
            results[0].score shouldBe 0.85f
        }

        it("should filter by userId when provided") {
            // Given
            val userId = "user-123"

            coEvery { mockEmbeddingService.embed(any()) } returns FloatArray(768) { 0.1f }

            val requestSlot = slot<HttpRequestBuilder.() -> Unit>()
            coEvery { mockHttpClient.post(any<String>(), capture(requestSlot)) } returns mockk {
                coEvery { bodyAsText() } returns """{"result": []}"""
            }

            // When
            service.findSimilarConversations("test", userId = userId)

            // Then
            // Verify filter was included in request
            verify { mockHttpClient.post(any<String>(), any()) }
        }
    }

    describe("indexExecution") {

        it("should index execution with correct payload") {
            // Given
            val execution = ExecutionRecord(
                id = "exec-1",
                prompt = "테스트 질문",
                result = "테스트 답변",
                userId = "user-1",
                agentId = "general"
            )

            coEvery { mockEmbeddingService.embed(any()) } returns FloatArray(768) { 0.1f }
            coEvery { mockHttpClient.put(any<String>()) } returns mockk()

            // When
            service.indexExecution(execution)

            // Then
            coVerify { mockHttpClient.put(match { it.contains("claude-flow-conversations") }) }
        }
    }
})
```

### 7.2 통합 테스트

```kotlin
// 파일: claude-flow-core/src/test/kotlin/ai/claudeflow/core/rag/RagIntegrationTest.kt

package ai.claudeflow.core.rag

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

class RagIntegrationTest : DescribeSpec({

    val qdrantContainer = GenericContainer("qdrant/qdrant:v1.7.4")
        .withExposedPorts(6333)
        .waitingFor(Wait.forHttp("/").forStatusCode(200))

    beforeSpec {
        qdrantContainer.start()
    }

    afterSpec {
        qdrantContainer.stop()
    }

    describe("End-to-end RAG pipeline") {

        it("should index and retrieve conversations") {
            // Given
            val qdrantUrl = "http://${qdrantContainer.host}:${qdrantContainer.getMappedPort(6333)}"
            val service = createService(qdrantUrl)

            // Index test data
            val execution = ExecutionRecord(
                id = "test-1",
                prompt = "Kotlin에서 코루틴 사용법 알려줘",
                result = "코루틴은 suspend 함수로...",
                userId = "user-1"
            )
            service.indexExecution(execution)

            // When
            val results = service.findSimilarConversations(
                query = "코틀린 비동기 프로그래밍",
                topK = 5
            )

            // Then
            results.isNotEmpty() shouldBe true
            results[0].executionId shouldBe "test-1"
        }
    }
})
```

---

## 8. 마이그레이션 계획

### 8.1 기존 데이터 마이그레이션

```kotlin
// 파일: claude-flow-app/src/main/kotlin/ai/claudeflow/app/job/VectorMigrationJob.kt

package ai.claudeflow.app.job

import ai.claudeflow.core.rag.ConversationVectorService
import ai.claudeflow.core.storage.repository.ExecutionRepository
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class VectorMigrationJob(
    private val executionRepository: ExecutionRepository,
    private val conversationVectorService: ConversationVectorService
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * 시작 시 한 번 실행 (기존 데이터 마이그레이션)
     */
    suspend fun migrateExistingData() {
        logger.info("Starting vector migration...")

        var totalMigrated = 0
        var offset = 0
        val batchSize = 100

        while (true) {
            val executions = executionRepository.findAll(
                page = PageRequest(offset = offset, limit = batchSize)
            )

            if (executions.isEmpty()) break

            val migrated = conversationVectorService.indexExecutions(executions)
            totalMigrated += migrated
            offset += batchSize

            logger.info("Migrated $totalMigrated executions so far...")
        }

        logger.info("Vector migration complete. Total: $totalMigrated")
    }

    /**
     * 새 실행 기록 자동 인덱싱 (이벤트 기반)
     */
    suspend fun onExecutionCreated(execution: ExecutionRecord) {
        try {
            conversationVectorService.indexExecution(execution)
        } catch (e: Exception) {
            logger.error("Failed to index execution ${execution.id}", e)
        }
    }
}
```

### 8.2 롤아웃 전략

```
Phase 1: Shadow Mode (1주)
├─ RAG 활성화하되 결과는 로깅만
├─ 기존 로직과 병렬 실행
├─ 성능 메트릭 수집
└─ 정확도 비교

Phase 2: Canary Release (1주)
├─ 10% 트래픽에 RAG 적용
├─ A/B 테스트 실행
├─ 사용자 피드백 수집
└─ 문제 시 즉시 롤백

Phase 3: Gradual Rollout (1주)
├─ 25% → 50% → 75% → 100%
├─ 단계별 모니터링
└─ 성능 튜닝

Phase 4: Full Production
├─ 전체 트래픽 RAG 적용
├─ Shadow Mode 제거
└─ 최적화 지속
```

---

## 9. 성능 최적화

### 9.1 캐싱 전략

```kotlin
// 파일: claude-flow-core/src/main/kotlin/ai/claudeflow/core/rag/EmbeddingCache.kt

package ai.claudeflow.core.rag

import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration

class EmbeddingCache(
    maxSize: Long = 10_000,
    expireAfter: Duration = Duration.ofHours(1)
) {
    private val cache = Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(expireAfter)
        .recordStats()
        .build<String, FloatArray>()

    fun get(text: String): FloatArray? = cache.getIfPresent(text.hashCode().toString())

    fun put(text: String, embedding: FloatArray) {
        cache.put(text.hashCode().toString(), embedding)
    }

    fun stats() = cache.stats()
}
```

### 9.2 배치 처리

```kotlin
// 배치 임베딩 생성
suspend fun batchEmbed(texts: List<String>): List<FloatArray> {
    return texts.chunked(32).flatMap { batch ->
        embeddingService.embedBatch(batch)
    }
}

// 배치 인덱싱
suspend fun batchIndex(executions: List<ExecutionRecord>) {
    val points = executions.map { exec ->
        val embedding = embeddingService.embed(buildEmbeddingText(exec))
        QdrantPoint(
            id = exec.id.hashCode().toLong(),
            vector = embedding,
            payload = buildPayload(exec)
        )
    }

    qdrantClient.upsertBatch(collectionName, points)
}
```

### 9.3 쿼리 최적화

```kotlin
// Qdrant 쿼리 최적화
val searchParams = SearchParams(
    hnsw_ef = 128,        // 검색 정확도 (높을수록 정확, 느림)
    exact = false,        // 근사 검색 사용
    indexed_only = true   // 인덱싱된 필드만 필터링
)

// 페이로드 선택적 반환
val withPayload = WithPayloadSelector(
    include = listOf("execution_id", "prompt_summary", "score")
)
```

---

## 10. 일정 요약

```
┌──────────────────────────────────────────────────────────────────┐
│                     RAG 구현 일정                                  │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Week 1: 대화 벡터화 기반 구축                                     │
│  ├─ Qdrant 컬렉션 설정                                            │
│  ├─ ConversationVectorService 구현                                │
│  ├─ DB 마이그레이션                                                │
│  └─ 기존 데이터 인덱싱                                             │
│                                                                   │
│  Week 2: 컨텍스트 증강 시스템                                       │
│  ├─ ContextAugmentationService 구현                               │
│  ├─ SessionContextBuilder 확장                                    │
│  └─ 프롬프트 템플릿 최적화                                         │
│                                                                   │
│  Week 3: 피드백 기반 라우팅 학습                                    │
│  ├─ FeedbackLearningService 구현                                  │
│  ├─ AgentRouter 통합                                              │
│  └─ A/B 테스트 프레임워크                                          │
│                                                                   │
│  Week 4: 코드베이스 지식 RAG                                        │
│  ├─ CodeKnowledgeService 구현                                     │
│  ├─ GitLab 코드 크롤러                                             │
│  └─ MR 리뷰 플러그인 확장                                          │
│                                                                   │
│  Week 5: 통합 및 최적화                                             │
│  ├─ 전체 통합 테스트                                               │
│  ├─ 성능 최적화                                                    │
│  └─ 문서화                                                         │
│                                                                   │
│  Week 6: 롤아웃                                                    │
│  ├─ Shadow Mode 배포                                              │
│  ├─ Canary Release                                                │
│  └─ 전체 배포                                                      │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 11. 리스크 및 대응

| 리스크 | 영향도 | 대응 방안 |
|--------|--------|----------|
| Qdrant 장애 | 높음 | 폴백 로직 (기존 키워드 라우팅) |
| 임베딩 지연 | 중간 | 캐싱 + 비동기 인덱싱 |
| 메모리 부족 | 중간 | 배치 크기 조절 + 스트리밍 |
| 검색 정확도 저하 | 중간 | 임계값 튜닝 + 하이브리드 검색 |
| 비용 증가 | 낮음 | 캐싱 최적화 + 사용량 모니터링 |

---

## 12. 참고 자료

- [Qdrant Documentation](https://qdrant.tech/documentation/)
- [Ollama Embedding Models](https://ollama.ai/library)
- [RAG Best Practices](https://www.anthropic.com/research/rag-best-practices)
- [Korean NLP Optimization](https://github.com/ko-nlp/Korpora)

---

## 부록 A: 파일 구조

```
claude-flow/
├── claude-flow-core/
│   └── src/main/kotlin/ai/claudeflow/core/
│       └── rag/                              # 신규 패키지
│           ├── ConversationVectorService.kt
│           ├── ConversationVectorServiceImpl.kt
│           ├── ContextAugmentationService.kt
│           ├── ContextAugmentationServiceImpl.kt
│           ├── FeedbackLearningService.kt
│           ├── FeedbackLearningServiceImpl.kt
│           ├── CodeKnowledgeService.kt
│           ├── CodeKnowledgeServiceImpl.kt
│           ├── CodeChunker.kt
│           ├── EmbeddingCache.kt
│           ├── RagMetrics.kt
│           └── PromptTemplates.kt
│
├── claude-flow-api/
│   └── src/main/kotlin/ai/claudeflow/api/
│       └── rest/
│           └── RagController.kt              # 신규
│
├── claude-flow-app/
│   └── src/main/kotlin/ai/claudeflow/app/
│       ├── config/
│       │   └── RagConfiguration.kt           # 신규
│       └── job/
│           ├── VectorMigrationJob.kt         # 신규
│           └── CodeIndexingJob.kt            # 신규
│
├── scripts/
│   ├── setup-rag.sh                          # 신규
│   └── setup-knowledge-collection.sh         # 신규
│
└── docs/
    └── RAG_IMPLEMENTATION_PLAN.md            # 본 문서
```

---

*문서 끝*
