# Claude Flow

**엔터프라이즈급 AI 에이전트 오케스트레이션 플랫폼**

Slack에서 Claude를 호출하고, GitLab MR 리뷰를 자동화하는 AI 에이전트 플랫폼입니다.
5분 만에 설치하고, 팀의 생산성을 10배 높이세요.

## 왜 Claude Flow인가?

- **즉시 시작**: Docker 한 줄로 설치, Slack에서 바로 사용
- **지능형 라우팅**: 키워드 → 시맨틱 → LLM 폴백 자동 분류
- **컨텍스트 기억**: 사용자별 대화 요약 및 규칙 자동 적용
- **실시간 분석**: P50/P90/P95/P99 지연 시간, 비용 추적
- **확장 가능**: n8n 워크플로우로 무한 커스터마이징

## 주요 기능

### Core
- **Slack 통합**: `@claude-flow` 멘션으로 즉시 대화
- **코드 리뷰**: `@claude-flow !123 리뷰해줘`로 GitLab MR 자동 리뷰
- **Socket Mode**: ngrok 없이 로컬 개발 환경에서 완벽 작동
- **워크플로우 엔진**: n8n 기반 유연한 이벤트 처리

### Intelligence
- **스마트 라우팅**: 멀티 에이전트 자동 분류 (캐싱 지원)
- **사용자 컨텍스트**: AI 기반 대화 요약, 개인별 규칙 저장
- **구조화된 출력**: JSON Schema로 응답 형식 강제

### Analytics
- **실시간 대시보드**: 성능 지표, 사용량, 비용 한눈에
- **백분위수 통계**: P50/P90/P95/P99 응답 시간 추적
- **피드백 분석**: 사용자 만족도 자동 측정

## 빠른 시작 (5분)

### 1. 설치

```bash
git clone https://github.com/your-org/claude-flow.git
cd claude-flow/docker-compose
cp .env.example .env
```

### 2. 환경 변수 설정

`.env` 파일 편집:
```bash
SLACK_APP_TOKEN=xapp-xxx      # Slack App Token
SLACK_BOT_TOKEN=xoxb-xxx      # Slack Bot Token
SLACK_SIGNING_SECRET=xxx      # Slack Signing Secret

# Optional: GitLab 연동
GITLAB_URL=https://gitlab.example.com
GITLAB_TOKEN=glpat-xxx
```

### 3. 실행

```bash
docker-compose up -d
```

### 4. 사용

Slack에서:
```
@claude-flow 안녕하세요!
@claude-flow authorization-server !42 리뷰해줘
```

> Claude CLI 인증은 `claude login`으로 한 번만 하면 됩니다.

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                         Slack                                    │
│                    (@멘션, 메시지, 리액션)                         │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Socket Mode (WebSocket)
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Claude Flow (Kotlin)                          │
│  ┌─────────────────┐  ┌──────────────┐  ┌───────────────────┐   │
│  │ SlackSocketMode │→ │ AgentRouter  │→ │  ClaudeExecutor   │   │
│  │     Bridge      │  │  (Smart)     │  │  (Claude CLI)     │   │
│  └─────────────────┘  └──────────────┘  └───────────────────┘   │
│                            │                                     │
│  ┌─────────────────────────┴──────────────────────────────────┐ │
│  │  Storage (SQLite/WAL) │ UserContext │ Analytics │ Feedback │ │
│  └────────────────────────────────────────────────────────────┘ │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Webhook
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                      n8n Workflows                               │
│  • slack-mention-handler   • gitlab-mr-review                   │
│  • slack-mr-review         • daily-report                       │
│  • user-context-handler    • slack-reaction-handler             │
└─────────────────────────────────────────────────────────────────┘
```

## API

### Execute API
| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/v1/execute` | Claude 실행 (단일 턴) |
| POST | `/api/v1/execute-with-routing` | 스마트 라우팅 + 실행 |

### Analytics API
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/analytics/dashboard` | 종합 대시보드 |
| GET | `/api/v1/analytics/overview` | P50/P90/P95/P99 통계 |
| GET | `/api/v1/analytics/timeseries` | 시계열 데이터 |

### User Context API
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/users/{userId}/context` | 사용자 컨텍스트 조회 |
| POST | `/api/v1/users/{userId}/rules` | 사용자 규칙 추가 |

## 프로젝트 구조

```
claude-flow/
├── claude-flow-core/          # 도메인 모델, 라우팅, 스토리지
├── claude-flow-executor/      # Claude CLI 래퍼
├── claude-flow-api/           # REST API, Slack 연동
├── claude-flow-app/           # Spring Boot 애플리케이션
├── docker-compose/            # Docker 설정, n8n 워크플로우
└── dashboard/                 # React 대시보드
```

## 기술 스택

- **Backend**: Kotlin 2.1, Spring Boot 3.4, Kotlin Coroutines
- **AI**: Claude CLI (자체 인증)
- **Slack**: Bolt for Java (Socket Mode)
- **Workflow**: n8n
- **Storage**: SQLite (WAL mode)
- **Dashboard**: React, Vite, TailwindCSS, Recharts

## 대시보드

```bash
cd dashboard
npm install && npm run dev
# http://localhost:5173
```

## 라이선스

MIT
