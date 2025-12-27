# Claude Flow 데이터 통합 분석 보고서

> 작성일: 2025-12-27
> 분석 범위: Slack, Dashboard Chat, REST API, n8n 워크플로우 간 데이터 흐름

## 요약

Claude Flow는 여러 데이터 소스(Slack, Dashboard Chat, API)에서 입력을 받지만, **각 기능별로 데이터 통합이 불완전한 Vertical Silo 문제**가 있습니다.

### 핵심 문제

| 기능 | Slack | Chat | API | 통합도 | 문제점 |
|------|:-----:|:----:|:---:|:------:|--------|
| 실행 기록 | ✅ | ✅ | ✅ | 95% | source 필드 명시 안됨 |
| 피드백 수집 | ✅ | ❌ | ✅ | **70%** | **Chat에서 피드백 저장 안됨** |
| 사용자 컨텍스트 | ✅ | ❌ | ✅ | **65%** | **Chat에서 업데이트 안됨** |
| 세션 관리 | ⚠️ | ⚠️ | ⚠️ | **50%** | 테이블 있으나 활용 부족 |
| 에이전트 라우팅 | ✅ | ✅ | ✅ | 100% | - |
| RAG 검색 | ✅ | ❌ | ✅ | **60%** | **Chat에서 RAG 미사용** |

---

## 1. 실행 기록 (Executions)

### 현재 상태: ✅ 양호 (95%)

```
┌─────────────┐     ┌─────────────┐     ┌─────────────────────┐
│   Slack     │────▶│    n8n      │────▶│  /execute-with-     │
│   멘션      │     │  workflow   │     │    routing          │
└─────────────┘     └─────────────┘     └──────────┬──────────┘
                                                   │
┌─────────────┐                                    │
│  Dashboard  │────────────────────────────────────┤
│    Chat     │     /chat/stream                   │
└─────────────┘                                    ▼
                                          ┌─────────────────┐
┌─────────────┐                           │   executions    │
│  REST API   │──────────────────────────▶│    테이블       │
│   직접      │                           │  source 필드    │
└─────────────┘                           │  (slack/chat/   │
                                          │   api/webhook)  │
                                          └─────────────────┘
```

| 소스 | 경로 | source 값 | 저장 여부 |
|------|------|----------|---------|
| Slack | n8n → `/api/v1/execute-with-routing` | "slack" | ✅ |
| Dashboard Chat | `/api/v1/chat/stream` | "chat" | ✅ |
| REST API | `/api/v1/execute` 또는 `/execute-with-routing` | "api" | ✅ |

### 문제점

1. **source 필드 명시 안됨**: n8n 워크플로우에서 `source: "slack"` 명시 설정 없음 (API 기본값 의존)
2. **Chat의 channel 정보 누락**: `ChatStreamController`에서 `channel=null` 설정

### 관련 코드

- `ClaudeFlowController.kt:564-588` - Slack/API 실행 저장
- `ChatStreamController.kt:658-700` - Chat 실행 저장
- `slack-mention-handler.json:169` - n8n에서 source 전달

---

## 2. 피드백 수집 (Feedback) ⚠️

### 현재 상태: 부분 통합 (70%)

```
┌─────────────┐     ┌─────────────┐     ┌─────────────────────┐
│   Slack     │────▶│    n8n      │────▶│  /api/v1/feedback   │
│   리액션    │     │  feedback   │     │                     │
│   👍 👎     │     │  handler    │     │                     │
└─────────────┘     └─────────────┘     └──────────┬──────────┘
                                                   │
┌─────────────┐                                    │
│  Dashboard  │                                    │
│    Chat     │     피드백 버튼 없음 ❌            │
└─────────────┘                                    │
                                                   ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────────┐
│   GitLab    │────▶│    n8n      │────▶│    feedback     │
│   이모지    │     │  gitlab-    │     │     테이블      │
│   MR 노트   │     │  feedback   │     │                 │
└─────────────┘     │  poller     │     │  executionId    │
                    └─────────────┘     │  userId         │
                                        │  reaction       │
                                        │  category       │
                                        └─────────────────┘
```

| 소스 | 저장 여부 | 비고 |
|------|---------|------|
| Slack 리액션 | ✅ | n8n slack-feedback-handler 경유 |
| Dashboard Chat | ❌ **미구현** | 피드백 버튼 없음 |
| GitLab 이모지/노트 | ✅ | n8n gitlab-feedback-poller 경유 |

### 🔴 문제점

1. **Dashboard Chat에서 피드백 수집 안됨**
   - Chat.tsx에 피드백 버튼 없음
   - ChatStreamController에 피드백 저장 로직 없음

2. **source 필드 없음**
   - Slack vs GitLab 피드백 구분은 `gitlab_project_id` 존재 여부로만 가능
   - 명시적인 source 컬럼 없음

### 영향

- Chat 사용자의 만족도 데이터 누락
- 전체 피드백 통계에서 Chat 채널 제외
- Verified Feedback 계산 시 Chat 제외

### 관련 코드

- `slack-feedback-handler.json` - Slack 피드백 처리
- `FeedbackRepository.kt:39-52` - 피드백 저장
- `Chat.tsx` - 피드백 UI **없음**

---

## 3. 사용자 컨텍스트 (User Context) ⚠️

### 현재 상태: 부분 통합 (65%)

```
┌─────────────┐     ┌─────────────────────┐     ┌─────────────────┐
│   Slack     │────▶│  execute-with-      │────▶│  user_contexts  │
│   실행 후   │     │  routing            │     │                 │
└─────────────┘     │                     │     │  totalInteract- │
                    │ updateUserInter-    │     │  ions           │
                    │ action() 호출 ✅    │     │  totalChars     │
┌─────────────┐     └─────────────────────┘     │  summary        │
│  Dashboard  │                                 │  lastSeen       │
│    Chat     │─────────────────────────────────│                 │
│             │     updateUserInteraction()     │                 │
│             │     호출 ❌ 안함                │                 │
└─────────────┘                                 └─────────────────┘
```

| 소스 | 컨텍스트 업데이트 | 메서드 |
|------|---------------|--------|
| Slack | ✅ 매 요청마다 | `storage.updateUserInteraction()` |
| Dashboard Chat | ❌ **미호출** | - |
| REST API | ✅ userId 있으면 | `storage.updateUserInteraction()` |

### 🔴 문제점

1. **ChatStreamController에서 updateUserInteraction() 미호출**
   - `ClaudeFlowController.kt:623-632`에서는 호출함
   - `ChatStreamController.kt:658-700`에서는 **생략됨**

2. **대화 요약(summary)이 Chat 대화 반영 안함**
   - AutoSummaryService는 totalChars 기준으로 요약 생성
   - Chat 사용량이 totalChars에 미반영

### 영향

- Chat 사용자의 상호작용 이력 미추적
- 사용자별 통계에서 Chat 사용량 누락
- RAG 컨텍스트 증강 시 Chat 대화 제외

### 관련 코드

- `ClaudeFlowController.kt:623-632` - Slack/API 사용자 컨텍스트 업데이트
- `ChatStreamController.kt:658-700` - **updateUserInteraction 누락**

---

## 4. 세션/대화 히스토리 (Sessions) ⚠️

### 현재 상태: 분리됨 (50%)

```
┌─────────────┐     ┌─────────────────────┐
│   Slack     │────▶│     sessions        │
│   스레드    │     │  session_messages   │
│   (threadTs)│     │     테이블          │
└─────────────┘     └─────────────────────┘
                            ▲
                            │ 저장됨
                            │
┌─────────────┐     ┌─────────────────────┐
│  Dashboard  │────▶│   React State       │
│    Chat     │     │   (메모리만)        │◀──── 테이블 저장 ❌
│             │     │   ChatContext.tsx   │
└─────────────┘     └─────────────────────┘
```

| 소스 | 세션 저장 | 메시지 저장 |
|------|---------|----------|
| Slack 스레드 | ✅ sessions | ✅ session_messages |
| Dashboard Chat | ❌ 메모리만 | ❌ 메모리만 |

### 🔴 문제점

1. **Dashboard Chat 세션 미저장**
   - `ChatContext.tsx`에서 React state로만 관리
   - 새로고침 시 대화 기록 소실
   - `sessions`, `session_messages` 테이블 미사용

2. **통합 대화 히스토리 없음**
   - Slack 스레드와 Chat 세션이 분리됨
   - 동일 사용자의 크로스 플랫폼 대화 추적 불가

### 영향

- Chat 대화 기록 영속성 없음
- 사용자별 전체 대화 히스토리 조회 불가

### 관련 코드

- `SessionRepository.kt` - 세션 저장 (Slack용)
- `ChatContext.tsx:84-112` - Chat 세션 (메모리만)

---

## 5. 에이전트 라우팅 (Routing)

### 현재 상태: ✅ 완전 통합 (100%)

```
┌─────────────┐
│   Slack     │─────┐
└─────────────┘     │
                    │
┌─────────────┐     │     ┌─────────────────┐     ┌─────────────────┐
│  Dashboard  │─────┼────▶│   AgentRouter   │────▶│ routing_metrics │
│    Chat     │     │     │                 │     │                 │
└─────────────┘     │     │  - keyword      │     │  method         │
                    │     │  - pattern      │     │  confidence     │
┌─────────────┐     │     │  - semantic     │     │  latency_ms     │
│  REST API   │─────┘     │  - feedback     │     │                 │
└─────────────┘           └─────────────────┘     └─────────────────┘
```

| 소스 | AgentRouter 사용 | 메트릭 저장 |
|------|---------------|----------|
| Slack | ✅ | ✅ |
| Dashboard Chat | ✅ | ✅ |
| REST API | ✅ | ✅ |

### 양호한 이유

- 모든 소스에서 동일한 `AgentRouter` 사용
- `routing_metrics` 테이블에 통합 저장
- Dashboard에서 라우팅 효율 분석 가능

---

## 6. RAG/Knowledge Base ⚠️

### 현재 상태: 부분 통합 (60%)

```
┌─────────────┐     ┌─────────────────────┐     ┌─────────────────┐
│   Slack     │────▶│ contextAugmentation │────▶│    Qdrant       │
│             │     │ Service             │     │   벡터 DB       │
└─────────────┘     │                     │     │                 │
                    │ conversationVector  │     │  임베딩 저장    │
                    │ Service             │     │  유사도 검색    │
┌─────────────┐     └─────────────────────┘     └─────────────────┘
│  Dashboard  │                                        ▲
│    Chat     │────▶ ContextEnrichmentPipeline         │
│             │     사용하지만 RAG 미사용 ❌           │
└─────────────┘     conversationVectorService 호출 안함│
                                                       │
┌─────────────┐     ┌─────────────────────┐            │
│  REST API   │────▶│ contextAugmentation │────────────┘
│             │     │ Service             │
└─────────────┘     └─────────────────────┘
```

| 소스 | RAG 컨텍스트 증강 | 임베딩 저장 |
|------|---------------|----------|
| Slack | ✅ | ✅ indexExecution 호출 |
| Dashboard Chat | ❌ **미사용** | ❌ 미호출 |
| REST API | ✅ | ✅ |

### 🔴 문제점

1. **ChatStreamController에서 RAG 미사용**
   - `ContextEnrichmentPipeline`은 사용하지만 `conversationVectorService` 미사용
   - Chat 대화가 벡터 DB에 인덱싱 안됨

2. **Chat 대화 검색 불가**
   - Slack 대화만 유사도 검색 가능
   - Chat 대화는 RAG 컨텍스트에서 제외

### 관련 코드

- `ClaudeFlowController.kt:597-608` - RAG 인덱싱 (Slack/API)
- `ChatStreamController.kt:658-700` - **RAG 인덱싱 누락**

---

## 권장 개선 사항

### 🔴 P0 (즉시 개선 필요)

#### 1. Dashboard Chat 피드백 저장

```tsx
// Chat.tsx 또는 ChatMessage.tsx에 추가
const handleFeedback = async (reaction: 'thumbsup' | 'thumbsdown') => {
  await feedbackApi.save({
    executionId: message.executionId,
    userId: currentUser.id,
    reaction,
    source: 'chat'  // 신규 필드
  });
};
```

```kotlin
// ChatStreamController.kt - saveExecutionRecord 반환값으로 executionId 전달
// 프론트에서 피드백 버튼 클릭 시 사용
```

#### 2. Chat 사용자 컨텍스트 업데이트

```kotlin
// ChatStreamController.kt:693 근처에 추가
storage?.let { store ->
    request.userId?.let { userId ->
        CoroutineScope(Dispatchers.IO).launch {
            store.updateUserInteraction(
                userId = userId,
                promptLength = prompt.length,
                responseLength = event.result?.length ?: 0
            )
        }
    }
}
```

#### 3. Chat RAG 인덱싱 활성화

```kotlin
// ChatStreamController.kt - saveExecutionRecord 내부에 추가
if (event.success && conversationVectorService != null) {
    conversationVectorService.indexExecution(record)
}
```

### 🟡 P1 (중기 개선)

#### 4. Chat 세션 영속화

- `sessions` 테이블에 Chat 세션 저장
- React state → API → DB 저장 추가
- 세션 복원 기능

#### 5. 피드백 source 필드 추가

```sql
ALTER TABLE feedback ADD COLUMN source TEXT DEFAULT 'unknown';
-- 값: slack_reaction, gitlab_emoji, gitlab_note, chat, api
```

#### 6. n8n 워크플로우 source 명시

```json
// slack-mention-handler.json의 execute-claude 노드
"body": {
  "source": "slack",
  ...
}
```

### 🟢 P2 (추후 개선)

7. Dashboard에 "데이터 소스별" 필터 추가
8. 크로스 플랫폼 대화 추적 (동일 사용자의 Slack + Chat)
9. 통합 세션 뷰 (모든 채널의 대화 통합)

---

## 테이블 스키마 참고

### executions

```sql
CREATE TABLE executions (
    id TEXT PRIMARY KEY,
    prompt TEXT NOT NULL,
    result TEXT,
    status TEXT NOT NULL,
    agent_id TEXT,
    project_id TEXT,
    user_id TEXT,
    channel TEXT,           -- Slack: 채널 ID, Chat: null
    thread_ts TEXT,
    reply_ts TEXT,
    duration_ms INTEGER,
    input_tokens INTEGER,
    output_tokens INTEGER,
    cost REAL,
    error TEXT,
    model TEXT,
    source TEXT,            -- 'slack', 'chat', 'api', 'webhook'
    routing_method TEXT,
    routing_confidence REAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### feedback

```sql
CREATE TABLE feedback (
    id TEXT PRIMARY KEY,
    execution_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    reaction TEXT NOT NULL,  -- 'thumbsup', 'thumbsdown'
    category TEXT DEFAULT 'feedback',
    is_verified BOOLEAN DEFAULT FALSE,
    gitlab_project_id TEXT,  -- GitLab 피드백만
    gitlab_mr_iid INTEGER,   -- GitLab 피드백만
    source TEXT,             -- 💡 추가 권장: 'slack', 'chat', 'gitlab_emoji'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### user_contexts

```sql
CREATE TABLE user_contexts (
    user_id TEXT PRIMARY KEY,
    display_name TEXT,
    preferred_language TEXT DEFAULT 'ko',
    domain TEXT,
    last_seen TIMESTAMP,
    total_interactions INTEGER DEFAULT 0,  -- 💡 Chat 포함 필요
    summary TEXT,
    summary_updated_at TIMESTAMP,
    total_chars INTEGER DEFAULT 0,         -- 💡 Chat 포함 필요
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## 관련 파일 위치

| 영역 | 파일 |
|------|------|
| Slack 실행 | `claude-flow-api/.../ClaudeFlowController.kt` |
| Chat 실행 | `claude-flow-api/.../ChatStreamController.kt` |
| 피드백 저장 | `claude-flow-core/.../FeedbackRepository.kt` |
| 사용자 컨텍스트 | `claude-flow-core/.../UserContextRepository.kt` |
| RAG 서비스 | `claude-flow-core/.../rag/ConversationVectorService.kt` |
| n8n 워크플로우 | `docker-compose/n8n-workflows/*.json` |
| Dashboard Chat | `dashboard/src/pages/Chat.tsx` |
| Chat Context | `dashboard/src/contexts/ChatContext.tsx` |
