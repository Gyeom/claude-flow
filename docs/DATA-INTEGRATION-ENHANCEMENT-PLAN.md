# Claude Flow 데이터 통합 고도화 기획서

> 문서 버전: 1.1
> 작성일: 2025-12-27
> 최종 수정: 2025-12-27 (n8n 워크플로우 분석 추가)
> 상태: Draft

---

## 1. 개요

### 1.1 배경

Claude Flow는 다양한 데이터 소스(Slack, Dashboard Chat, REST API, n8n 워크플로우)에서 입력을 받아 처리합니다. 그러나 현재 **각 소스별로 데이터 처리가 분리되어 Vertical Silo 문제**가 발생하고 있습니다.

### 1.2 목적

- 모든 데이터 소스에서 **동일한 수준의 데이터 수집 및 통합**
- **통합 분석 및 리포팅** 가능한 데이터 구조 확립
- **사용자 경험 일관성** 확보 (어느 채널에서든 동일한 기능)

### 1.3 범위

| 구분 | 포함 | 제외 |
|------|------|------|
| 데이터 소스 | Slack, Dashboard Chat, REST API, n8n 워크플로우 | 외부 시스템 직접 연동 |
| 기능 | 실행, 피드백, 사용자 컨텍스트, 세션, RAG, DLQ | 새 기능 개발 |
| 시스템 | Backend (Kotlin), Frontend (React), n8n 워크플로우 9개 | 인프라 변경 |

---

## 2. 현황 분석

### 2.1 데이터 흐름 현황

```
                         ┌─────────────────────────────────────┐
                         │         Claude Flow Backend         │
                         │                                     │
    ┌─────────┐          │  ┌─────────────────────────────┐   │
    │  Slack  │──n8n────▶│  │  ClaudeFlowController      │   │
    │  멘션   │          │  │  ├─ saveExecution ✅        │   │
    │  리액션 │          │  │  ├─ saveFeedback ✅         │   │
    └─────────┘          │  │  ├─ updateUserContext ✅    │   │
                         │  │  ├─ saveRoutingMetric ✅    │   │
                         │  │  └─ indexToRAG ✅           │   │
                         │  └─────────────────────────────┘   │
                         │                                     │
    ┌─────────┐          │  ┌─────────────────────────────┐   │
    │Dashboard│─────────▶│  │  ChatStreamController       │   │
    │  Chat   │          │  │  ├─ saveExecution ✅        │   │
    │         │          │  │  ├─ saveFeedback ❌         │   │
    └─────────┘          │  │  ├─ updateUserContext ❌    │   │
                         │  │  ├─ saveRoutingMetric ❌    │   │
                         │  │  └─ indexToRAG ❌           │   │
                         │  └─────────────────────────────┘   │
                         │                                     │
    ┌─────────┐          │  ┌─────────────────────────────┐   │
    │  REST   │─────────▶│  │  ClaudeFlowController      │   │
    │   API   │          │  │  (Slack과 동일)             │   │
    └─────────┘          │  └─────────────────────────────┘   │
                         └─────────────────────────────────────┘
```

### 2.2 기능별 통합 현황

| 기능 | Slack | Dashboard Chat | REST API | 통합도 |
|------|:-----:|:--------------:|:--------:|:------:|
| 실행 기록 저장 | ✅ | ✅ | ✅ | **95%** |
| 피드백 수집 | ✅ | ❌ | ✅ | **70%** |
| 사용자 컨텍스트 | ✅ | ❌ | ✅ | **65%** |
| 라우팅 메트릭 | ✅ | ❌ | ✅ | **65%** |
| 세션 관리 | ⚠️ | ❌ | ⚠️ | **50%** |
| RAG 인덱싱 | ✅ | ❌ | ✅ | **60%** |

### 2.3 문제점 상세

#### 2.3.1 Dashboard Chat 데이터 누락

**ChatStreamController.kt**에서 다음 기능이 누락됨:

| 누락 기능 | 영향 | 심각도 |
|----------|------|:------:|
| `updateUserInteraction()` | 사용자 통계에서 Chat 사용량 제외 | 🔴 높음 |
| `saveRoutingMetric()` | 라우팅 효율 분석에서 Chat 제외 | 🟡 중간 |
| `conversationVectorService.indexExecution()` | RAG 검색에서 Chat 대화 제외 | 🔴 높음 |
| 피드백 UI | Chat 사용자 만족도 데이터 없음 | 🔴 높음 |
| 세션 영속화 | 새로고침 시 대화 기록 소실 | 🟡 중간 |

#### 2.3.2 source 필드 불명확

| 문제 | 현재 상태 | 영향 |
|------|----------|------|
| executions.source | n8n에서 명시 안함, 기본값 의존 | 소스별 분석 어려움 |
| feedback.source | 필드 없음 | Slack/GitLab/Chat 구분 불가 |

#### 2.3.3 세션 관리 분리

| 소스 | 세션 저장 위치 | 문제 |
|------|-------------|------|
| Slack | `sessions` 테이블 | - |
| Chat | React state (메모리) | 새로고침 시 소실 |
| API | 없음 | - |

---

## 3. 고도화 목표

### 3.1 목표 상태

```
┌─────────────────────────────────────────────────────────────────┐
│                    통합 데이터 파이프라인                        │
├─────────────────┬─────────┬──────────┬─────────┬────────────────┤
│ 기능            │ Slack   │ Chat     │ API     │ 통합도         │
├─────────────────┼─────────┼──────────┼─────────┼────────────────┤
│ 실행 기록       │ ✅      │ ✅       │ ✅      │ 100%           │
│ 피드백 수집     │ ✅      │ ✅       │ ✅      │ 100%           │
│ 사용자 컨텍스트 │ ✅      │ ✅       │ ✅      │ 100%           │
│ 라우팅 메트릭   │ ✅      │ ✅       │ ✅      │ 100%           │
│ 세션 관리       │ ✅      │ ✅       │ ✅      │ 100%           │
│ RAG 인덱싱      │ ✅      │ ✅       │ ✅      │ 100%           │
└─────────────────┴─────────┴──────────┴─────────┴────────────────┘
```

### 3.2 핵심 원칙

1. **단일 진입점**: 모든 실행은 공통 서비스 레이어를 통과
2. **명시적 source**: 모든 데이터에 출처 명시
3. **통합 조회**: Dashboard에서 모든 소스 데이터 통합 조회
4. **일관된 UX**: 어느 채널에서든 동일한 기능 제공

---

## 4. 개선 계획

### 4.1 우선순위 정의

| 우선순위 | 정의 | 기준 |
|:--------:|------|------|
| **P0** | 즉시 필요 | 데이터 누락, 분석 왜곡 |
| **P1** | 중기 개선 | 사용자 경험, 기능 완성도 |
| **P2** | 추후 개선 | 고급 기능, 최적화 |

### 4.2 P0: 즉시 개선 (1주)

#### 4.2.1 ChatStreamController 데이터 수집 완성

**목표**: Dashboard Chat에서 누락된 4가지 데이터 수집 추가

| 항목 | 파일 | 변경 내용 |
|------|------|----------|
| 사용자 컨텍스트 | `ChatStreamController.kt` | `updateUserInteraction()` 호출 추가 |
| 라우팅 메트릭 | `ChatStreamController.kt` | `saveRoutingMetric()` 호출 추가 |
| RAG 인덱싱 | `ChatStreamController.kt` | `indexExecution()` 호출 추가 |

**구현 상세**:

```kotlin
// ChatStreamController.kt - saveExecutionRecord() 함수 수정

private fun saveExecutionRecord(
    event: StreamingEvent.Done,
    prompt: String,
    agentMatch: AgentMatch,
    projectId: String,
    userId: String?,
    model: String
) {
    storage?.let { store ->
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val record = ExecutionRecord(/* ... 기존 코드 ... */)
                store.saveExecution(record)

                // ✅ 추가 1: 사용자 컨텍스트 업데이트
                userId?.let { uid ->
                    store.updateUserInteraction(
                        userId = uid,
                        promptLength = prompt.length,
                        responseLength = event.result?.length ?: 0
                    )
                }

                // ✅ 추가 2: 라우팅 메트릭 저장
                store.saveRoutingMetric(
                    executionId = event.requestId,
                    routingMethod = agentMatch.method.name.lowercase(),
                    agentId = agentMatch.agent.id,
                    confidence = agentMatch.confidence,
                    latencyMs = 0  // 또는 측정값
                )

                // ✅ 추가 3: RAG 인덱싱
                if (conversationVectorService != null) {
                    conversationVectorService.indexExecution(record)
                }

            } catch (e: Exception) {
                logger.warn { "Failed to save complete execution data: ${e.message}" }
            }
        }
    }
}
```

#### 4.2.2 Chat 피드백 UI 추가

**목표**: Dashboard Chat에서 응답에 대한 피드백 수집

| 항목 | 파일 | 변경 내용 |
|------|------|----------|
| 피드백 버튼 | `ChatMessage.tsx` (신규) | 👍/👎 버튼 컴포넌트 |
| 피드백 API 호출 | `ChatContext.tsx` | `sendFeedback()` 함수 추가 |
| 실행 ID 전달 | `ChatStreamController.kt` | done 이벤트에 executionId 포함 |

**UI 설계**:

```
┌────────────────────────────────────────────────────┐
│ Assistant                                          │
│                                                    │
│ 분석 결과입니다...                                 │
│                                                    │
│ ┌──────┐ ┌──────┐                                 │
│ │  👍  │ │  👎  │  ← 피드백 버튼 추가             │
│ └──────┘ └──────┘                                 │
└────────────────────────────────────────────────────┘
```

**구현 상세**:

```tsx
// ChatMessage.tsx

interface FeedbackButtonsProps {
  executionId: string;
  onFeedback: (reaction: 'thumbsup' | 'thumbsdown') => void;
}

function FeedbackButtons({ executionId, onFeedback }: FeedbackButtonsProps) {
  const [submitted, setSubmitted] = useState<string | null>(null);

  const handleClick = async (reaction: 'thumbsup' | 'thumbsdown') => {
    await feedbackApi.save({
      executionId,
      reaction,
      source: 'chat'
    });
    setSubmitted(reaction);
  };

  return (
    <div className="flex gap-2 mt-2">
      <button
        onClick={() => handleClick('thumbsup')}
        disabled={submitted !== null}
        className={submitted === 'thumbsup' ? 'bg-green-100' : ''}
      >
        👍
      </button>
      <button
        onClick={() => handleClick('thumbsdown')}
        disabled={submitted !== null}
        className={submitted === 'thumbsdown' ? 'bg-red-100' : ''}
      >
        👎
      </button>
    </div>
  );
}
```

#### 4.2.3 source 필드 명시화

**목표**: 모든 데이터에 명시적 source 값 설정

| 항목 | 파일 | 변경 내용 |
|------|------|----------|
| n8n Slack source | `slack-mention-handler.json` | `source: "slack"` 추가 |
| feedback source | `FeedbackRecord.kt` | `source` 필드 추가 |
| DB 마이그레이션 | `Storage.kt` | feedback 테이블에 source 컬럼 추가 |

**n8n 워크플로우 수정**:

```json
// slack-mention-handler.json - Execute Claude 노드
{
  "jsonBody": {
    "prompt": "...",
    "source": "slack",  // ✅ 추가
    "channel": "...",
    "userId": "..."
  }
}
```

**FeedbackRecord 수정**:

```kotlin
// FeedbackRecord.kt
data class FeedbackRecord(
    val id: String,
    val executionId: String,
    val userId: String,
    val reaction: String,
    val category: String = "feedback",
    val source: String = "unknown",  // ✅ 추가: slack, chat, gitlab_emoji, api
    val isVerified: Boolean = false,
    // ...
)
```

---

### 4.3 P1: 중기 개선 (2-3주)

#### 4.3.1 세션 영속화

**목표**: Dashboard Chat 세션을 DB에 저장하여 새로고침 후에도 유지

| 항목 | 파일 | 변경 내용 |
|------|------|----------|
| 세션 생성 API | `ChatStreamController.kt` | `POST /api/v1/chat/sessions` |
| 세션 조회 API | `ChatStreamController.kt` | `GET /api/v1/chat/sessions/{id}` |
| 세션 저장 | `ChatContext.tsx` | API 호출로 세션 저장 |
| 세션 복원 | `ChatContext.tsx` | 페이지 로드 시 최근 세션 복원 |

**API 설계**:

```
POST /api/v1/chat/sessions
  Request: { userId, projectId }
  Response: { sessionId, createdAt }

GET /api/v1/chat/sessions/{sessionId}/messages
  Response: [{ role, content, metadata, createdAt }]

POST /api/v1/chat/sessions/{sessionId}/messages
  Request: { role, content, metadata }
```

**데이터 모델**:

```kotlin
// 기존 sessions, session_messages 테이블 활용
data class ChatSession(
    val id: String,
    val userId: String,
    val projectId: String?,
    val source: String = "chat",  // Slack: threadTs, Chat: 생성된 ID
    val createdAt: Instant,
    val lastMessageAt: Instant
)
```

#### 4.3.2 통합 분석 Dashboard 강화

**목표**: 소스별 필터링 및 비교 분석 기능 추가

| 항목 | 파일 | 변경 내용 |
|------|------|----------|
| 소스 필터 | `Dashboard.tsx` | 소스별 드롭다운 필터 |
| 소스별 차트 | `Dashboard.tsx` | 소스별 요청량 비교 차트 |
| API 확장 | `AnalyticsController.kt` | source 파라미터 추가 |

**UI 설계**:

```
┌─────────────────────────────────────────────────────────────┐
│  Dashboard                          Source: [All ▼]         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  요청량 by Source                                    │   │
│  │  ████████████████████ Slack (65%)                   │   │
│  │  ██████████ Chat (25%)                              │   │
│  │  ████ API (10%)                                     │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  피드백 by Source                                    │   │
│  │  Slack: 👍 85 / 👎 12 (87.6%)                       │   │
│  │  Chat:  👍 -- / 👎 -- (N/A) ← 현재 데이터 없음      │   │
│  │  GitLab: 👍 23 / 👎 5 (82.1%)                       │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### 4.3.3 Dead Letter Queue 관리 UI

**목표**: 실패한 메시지 조회 및 재처리 기능

| 항목 | 파일 | 변경 내용 |
|------|------|----------|
| DLQ 조회 API | `SystemController.kt` | `GET /api/v1/system/dlq` |
| DLQ 재시도 API | `SystemController.kt` | `POST /api/v1/system/dlq/{id}/retry` |
| DLQ 삭제 API | `SystemController.kt` | `DELETE /api/v1/system/dlq/{id}` |
| DLQ 페이지 | `DeadLetterQueue.tsx` (신규) | 관리 UI |

---

### 4.4 P2: 추후 개선 (1개월+)

#### 4.4.1 크로스 플랫폼 사용자 추적

**목표**: 동일 사용자의 Slack/Chat/API 사용 통합 추적

| 항목 | 설명 |
|------|------|
| 사용자 매핑 | Slack userId ↔ Dashboard userId 매핑 테이블 |
| 통합 프로필 | 모든 채널 사용 이력 통합 조회 |
| 선호도 학습 | 채널별 사용 패턴 분석 |

#### 4.4.2 실시간 동기화

**목표**: Slack 대화와 Dashboard 실시간 동기화

| 항목 | 설명 |
|------|------|
| WebSocket | 실시간 메시지 동기화 |
| 알림 | 다른 채널에서의 응답 알림 |

#### 4.4.3 고급 분석

**목표**: ML 기반 인사이트

| 항목 | 설명 |
|------|------|
| 사용자 이탈 예측 | 피드백 패턴 기반 이탈 위험 사용자 식별 |
| 에이전트 추천 | 사용 패턴 기반 최적 에이전트 추천 |
| 비용 최적화 | 모델/토큰 사용 최적화 제안 |

---

## 5. 구현 로드맵

### 5.1 Phase 1: P0 구현 (Week 1)

```
Day 1-2: ChatStreamController 데이터 수집 완성
  └─ updateUserInteraction, saveRoutingMetric, indexExecution 추가

Day 3-4: Chat 피드백 UI
  └─ ChatMessage.tsx 피드백 버튼, ChatContext.tsx 피드백 함수

Day 5: source 필드 명시화
  └─ n8n 워크플로우 수정, feedback 테이블 마이그레이션

Day 6-7: 테스트 및 검증
  └─ 통합 테스트, Dashboard 확인
```

### 5.2 Phase 2: P1 구현 (Week 2-3)

```
Week 2:
  Day 1-3: 세션 영속화 API
  Day 4-5: ChatContext 세션 저장/복원

Week 3:
  Day 1-3: Dashboard 소스별 필터
  Day 4-5: DLQ 관리 UI
```

### 5.3 Phase 3: P2 구현 (Week 4+)

```
Week 4+:
  - 크로스 플랫폼 사용자 추적
  - 실시간 동기화 (WebSocket)
  - 고급 분석
```

---

## 6. 기대 효과

### 6.1 정량적 효과

| 지표 | 현재 | 목표 | 개선율 |
|------|------|------|:------:|
| 데이터 통합도 | 65% | 100% | +35% |
| 피드백 수집률 | 70% | 100% | +30% |
| 사용자 컨텍스트 정확도 | 65% | 100% | +35% |
| RAG 검색 커버리지 | 60% | 100% | +40% |

### 6.2 정성적 효과

| 영역 | 효과 |
|------|------|
| **분석 정확성** | 모든 채널 데이터 통합으로 실제 사용 현황 파악 |
| **사용자 경험** | 어느 채널에서든 동일한 기능 제공 |
| **운영 효율** | DLQ 관리로 실패 메시지 재처리 가능 |
| **RAG 품질** | 더 많은 대화 데이터로 컨텍스트 증강 개선 |

---

## 7. 리스크 및 대응

| 리스크 | 영향 | 대응 방안 |
|--------|------|----------|
| Chat 트래픽 증가로 DB 부하 | 중간 | 배치 저장, 인덱스 최적화 |
| 세션 데이터 증가 | 낮음 | 오래된 세션 자동 삭제 정책 |
| 피드백 스팸 | 낮음 | Rate limiting 적용 |
| 마이그레이션 실패 | 높음 | 롤백 스크립트 준비 |

---

## 8. 검증 방법

### 8.1 단위 테스트

```kotlin
// ChatStreamControllerTest.kt
@Test
fun `saveExecutionRecord should update user context`() {
    // Given
    val userId = "test-user"

    // When
    chatStreamController.saveExecutionRecord(...)

    // Then
    verify(storage).updateUserInteraction(userId, any(), any())
}
```

### 8.2 통합 테스트

| 시나리오 | 검증 항목 |
|----------|----------|
| Chat 실행 | executions 저장, user_contexts 업데이트, routing_metrics 저장 |
| Chat 피드백 | feedback 저장, source='chat' |
| 세션 복원 | 새로고침 후 대화 기록 유지 |

### 8.3 E2E 테스트

```
1. Dashboard Chat에서 메시지 전송
2. History 페이지에서 source='chat' 확인
3. Feedback 페이지에서 Chat 피드백 확인
4. Analytics에서 Chat 데이터 포함 확인
```

---

## 9. 참고 자료

### 9.1 관련 문서

- [DATA-INTEGRATION-ANALYSIS.md](./DATA-INTEGRATION-ANALYSIS.md) - 상세 분석 보고서
- [ARCHITECTURE.md](./ARCHITECTURE.md) - 시스템 아키텍처
- [CLAUDE.md](../CLAUDE.md) - 프로젝트 컨텍스트

### 9.2 수정 대상 파일

#### Backend (Kotlin)

| 우선순위 | 파일 | 변경 유형 |
|:--------:|------|----------|
| P0 | `claude-flow-api/.../ChatStreamController.kt` | 데이터 수집 완성 |
| P0 | `claude-flow-core/.../FeedbackRecord.kt` | source 필드 추가 |
| P0 | `claude-flow-core/.../Storage.kt` | feedback 테이블 마이그레이션 |
| P1 | `claude-flow-api/.../ChatStreamController.kt` | 세션 API 추가 |
| P1 | `claude-flow-api/.../SystemController.kt` | DLQ API 추가 |

#### Frontend (React)

| 우선순위 | 파일 | 변경 유형 |
|:--------:|------|----------|
| P0 | `dashboard/src/components/ChatMessage.tsx` | 신규 (피드백 버튼) |
| P0 | `dashboard/src/contexts/ChatContext.tsx` | 피드백 함수 추가 |
| P1 | `dashboard/src/pages/Dashboard.tsx` | 소스별 필터 |
| P1 | `dashboard/src/pages/DeadLetterQueue.tsx` | 신규 (DLQ 관리) |

#### n8n 워크플로우

| 우선순위 | 파일 | 변경 유형 |
|:--------:|------|----------|
| P0 | `docker-compose/n8n-workflows/slack-feedback-handler.json` | source 필드 추가 |
| P0 | `docker-compose/n8n-workflows/slack-action-handler.json` | source 필드 추가 |
| P0 | `docker-compose/n8n-workflows/slack-mr-review-v2.json` | source 필드 추가 |
| P0 | `docker-compose/n8n-workflows/scheduled-mr-review.json` | source + 엔드포인트 변경 |
| P1 | `docker-compose/n8n-workflows/gitlab-feedback-poller.json` | source 추가 + 활성화 |
| P1 | 모든 활성 워크플로우 | DLQ 노드 추가 |
| P2 | `docker-compose/n8n-workflows/alert-*.json` | 재설계 |

---

## 10. 승인

| 역할 | 이름 | 승인일 |
|------|------|--------|
| 기획 | - | - |
| 개발 | - | - |
| 검토 | - | - |

---

## 11. n8n 워크플로우 개선 계획

### 11.1 현황 분석

총 9개의 n8n 워크플로우가 존재하며, 데이터 통합 관점에서 여러 문제가 확인됨:

| 워크플로우 | 상태 | source 설정 | 데이터 저장 | 문제점 |
|-----------|:----:|:-----------:|:-----------:|--------|
| `slack-mention-handler` | ✅ 활성 | ✅ `"slack"` | ✅ | - |
| `slack-feedback-handler` | ✅ 활성 | ❌ 없음 | ⚠️ 일부 | source 미전달 |
| `slack-action-handler` | ✅ 활성 | ❌ 없음 | ✅ | source 필드 없음 |
| `slack-mr-review-v2` | ✅ 활성 | ❌ 없음 | ✅ | source 필드 없음 |
| `scheduled-mr-review` | ✅ 활성 | ❌ 없음 | ✅ | 다른 API 엔드포인트 사용 |
| `gitlab-feedback-poller` | ⏸️ 비활성 | ❌ 없음 | ⚠️ 일부 | source 필드 없음 |
| `alert-channel-monitor` | ⏸️ 비활성 | ❌ 없음 | ❌ 없음 | 저장 로직 없음 |
| `alert-to-mr-pipeline` | ⏸️ 비활성 | ❌ 없음 | ❌ 없음 | 저장 로직 없음 |
| `user-context-handler` | ⏸️ 비활성 | ❌ 없음 | ⚠️ 일부 | 비활성 상태 |

### 11.2 주요 문제점

#### 11.2.1 source 필드 불일치

**문제**: 9개 중 1개만 source 필드 설정

```
┌─────────────────────────────────────────────────────────────┐
│                       n8n Workflows                          │
├─────────────────────────┬───────────────────────────────────┤
│ slack-mention-handler   │ source: "slack" ✅               │
├─────────────────────────┼───────────────────────────────────┤
│ slack-feedback-handler  │ source: (없음) ❌                 │
│ slack-action-handler    │ source: (없음) ❌                 │
│ slack-mr-review-v2      │ source: (없음) ❌                 │
│ scheduled-mr-review     │ source: (없음) ❌                 │
│ gitlab-feedback-poller  │ source: (없음) ❌                 │
│ 기타 3개                 │ source: (없음) ❌                 │
└─────────────────────────┴───────────────────────────────────┘
```

**영향**:
- `executions` 테이블에서 소스별 분석 불가
- `feedback` 테이블에서 Slack/GitLab 피드백 구분 불가

#### 11.2.2 API 엔드포인트 불일치

| 워크플로우 | 사용 엔드포인트 | 문제 |
|-----------|----------------|------|
| `slack-mention-handler` | `/api/v1/execute-with-routing` | ✅ 표준 |
| `slack-mr-review-v2` | `/api/v1/execute-with-routing` | ✅ 표준 |
| `scheduled-mr-review` | `/api/v1/chat/execute` | ⚠️ 다른 엔드포인트 |

**영향**:
- `ChatStreamController`와 `ClaudeFlowController`의 데이터 수집 차이
- 일부 메트릭 누락 가능성

#### 11.2.3 피드백 API 불완전

**현재 `slack-feedback-handler.json`**:
```json
{
  "url": "/api/v1/feedback",
  "jsonBody": {
    "executionId": "...",
    "reaction": "...",
    "userId": "..."
    // ❌ source 필드 없음
  }
}
```

**영향**: Slack 피드백과 다른 소스의 피드백 구분 불가

#### 11.2.4 Dead Letter Queue 미구현

- 모든 워크플로우에 DLQ 노드 없음
- 실패한 요청 재처리 불가
- 오류 추적 어려움

### 11.3 개선 계획

#### 11.3.1 P0: source 필드 표준화

**수정 대상 워크플로우**:

| 워크플로우 | 변경 내용 | source 값 |
|-----------|----------|----------|
| `slack-feedback-handler` | feedback API에 source 추가 | `"slack"` |
| `slack-action-handler` | execute API에 source 추가 | `"slack"` |
| `slack-mr-review-v2` | execute API에 source 추가 | `"slack_mr"` |
| `scheduled-mr-review` | execute API에 source 추가 | `"scheduled"` |
| `gitlab-feedback-poller` | feedback API에 source 추가 | `"gitlab_emoji"` |

**slack-feedback-handler.json 수정**:

```json
// 수정 전
{
  "jsonBody": "={{ JSON.stringify({ executionId: $json.executionId, reaction: $json.reaction, userId: $json.userId }) }}"
}

// 수정 후
{
  "jsonBody": "={{ JSON.stringify({ executionId: $json.executionId, reaction: $json.reaction, userId: $json.userId, source: 'slack' }) }}"
}
```

**slack-action-handler.json 수정**:

```json
// Execute Claude 노드에 source 추가
{
  "jsonBody": {
    "prompt": "={{ $json.prompt }}",
    "projectId": "={{ $json.projectId }}",
    "userId": "={{ $json.userId }}",
    "source": "slack"  // ✅ 추가
  }
}
```

#### 11.3.2 P0: API 엔드포인트 통일

**scheduled-mr-review.json 수정**:

```json
// 수정 전
{
  "url": "={{ $env.CLAUDE_FLOW_API_URL }}/api/v1/chat/execute"
}

// 수정 후
{
  "url": "={{ $env.CLAUDE_FLOW_API_URL }}/api/v1/execute-with-routing",
  "jsonBody": {
    // ...
    "source": "scheduled"
  }
}
```

**또는**: `ChatStreamController`를 `ClaudeFlowController`와 동일한 수준으로 개선 (4.2.1 참조)

#### 11.3.3 P1: Dead Letter Queue 추가

**모든 활성 워크플로우에 DLQ 패턴 적용**:

```json
{
  "name": "Error Handler",
  "type": "n8n-nodes-base.errorTrigger",
  "notes": "실패한 요청 캐치"
},
{
  "name": "Save to DLQ",
  "type": "n8n-nodes-base.httpRequest",
  "parameters": {
    "method": "POST",
    "url": "={{ $env.CLAUDE_FLOW_API_URL }}/api/v1/system/dlq",
    "jsonBody": {
      "workflow": "slack-mention-handler",
      "error": "={{ $json.error.message }}",
      "payload": "={{ JSON.stringify($json) }}",
      "timestamp": "={{ $now.toISO() }}"
    }
  }
}
```

#### 11.3.4 P1: 비활성 워크플로우 정리

| 워크플로우 | 현재 상태 | 권장 조치 |
|-----------|----------|----------|
| `user-context-handler` | 비활성 | 기능 검토 후 활성화 또는 삭제 |
| `alert-channel-monitor` | 비활성 | 데이터 저장 로직 추가 후 활성화 |
| `alert-to-mr-pipeline` | 비활성 | 전체 파이프라인 재설계 필요 |
| `gitlab-feedback-poller` | 비활성 | source 수정 후 활성화 |

### 11.4 수정 대상 파일 요약

| 우선순위 | 파일 | 변경 유형 |
|:--------:|------|----------|
| P0 | `docker-compose/n8n-workflows/slack-feedback-handler.json` | source 추가 |
| P0 | `docker-compose/n8n-workflows/slack-action-handler.json` | source 추가 |
| P0 | `docker-compose/n8n-workflows/slack-mr-review-v2.json` | source 추가 |
| P0 | `docker-compose/n8n-workflows/scheduled-mr-review.json` | source + 엔드포인트 변경 |
| P1 | `docker-compose/n8n-workflows/gitlab-feedback-poller.json` | source 추가 + 활성화 |
| P1 | 모든 활성 워크플로우 | DLQ 노드 추가 |
| P2 | `docker-compose/n8n-workflows/alert-*.json` | 재설계 |

### 11.5 구현 순서

```
Week 1 (P0와 병행):
├── Day 1: slack-feedback-handler.json source 추가
├── Day 2: slack-action-handler.json source 추가
├── Day 3: slack-mr-review-v2.json source 추가
├── Day 4: scheduled-mr-review.json 수정
└── Day 5: n8n에 워크플로우 재배포, 테스트

Week 2-3 (P1과 병행):
├── DLQ 노드 템플릿 생성
├── 각 워크플로우에 DLQ 적용
├── gitlab-feedback-poller 수정 및 활성화
└── DLQ 관리 API 구현
```

---

## Appendix A: 테이블 스키마 변경

### A.1 feedback 테이블

```sql
-- 기존
CREATE TABLE feedback (
    id TEXT PRIMARY KEY,
    execution_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    reaction TEXT NOT NULL,
    category TEXT DEFAULT 'feedback',
    is_verified BOOLEAN DEFAULT FALSE,
    gitlab_project_id TEXT,
    gitlab_mr_iid INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 변경 후
ALTER TABLE feedback ADD COLUMN source TEXT DEFAULT 'unknown';
-- 값: 'slack', 'chat', 'gitlab_emoji', 'gitlab_note', 'api'
```

### A.2 sessions 테이블 (Chat 확장)

```sql
-- 기존 테이블 그대로 사용
-- source 컬럼으로 Slack/Chat 구분
-- Slack: source = threadTs (예: "1234567890.123456")
-- Chat: source = "chat-{uuid}"
```

---

## Appendix B: API 변경사항

### B.1 신규 API

```
POST /api/v1/chat/sessions
GET  /api/v1/chat/sessions/{sessionId}/messages
POST /api/v1/chat/sessions/{sessionId}/messages

GET  /api/v1/system/dlq
POST /api/v1/system/dlq/{id}/retry
DELETE /api/v1/system/dlq/{id}
```

### B.2 수정 API

```
GET /api/v1/analytics/dashboard
  + Query param: source (optional) - 'slack', 'chat', 'api', 'all'

GET /api/v1/analytics/feedback
  + Query param: source (optional)

POST /api/v1/feedback
  + Body: source (required) - 'slack', 'chat', 'gitlab_emoji', 'api'
```
