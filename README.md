# Claude Flow

Slack에서 Claude를 호출하고, GitLab MR 리뷰를 자동화하는 AI 에이전트 플랫폼

## 주요 기능

- **Slack 멘션 처리**: `@claude-flow` 멘션으로 Claude와 대화
- **GitLab MR 리뷰**: `@claude-flow !123 리뷰해줘`로 AI 코드 리뷰 자동화
- **Socket Mode**: ngrok 없이 로컬에서 Slack 연동
- **n8n 워크플로우**: 유연한 이벤트 처리 파이프라인
- **Dashboard**: 실행 기록 및 분석 대시보드

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                         Slack                                    │
│                    (@멘션, 메시지, 리액션)                         │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Socket Mode (WebSocket)
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    claude-flow (Kotlin)                          │
│  ┌─────────────────┐  ┌──────────────┐  ┌───────────────────┐   │
│  │ SlackSocketMode │→ │ WebhookSender│→ │  ClaudeExecutor   │   │
│  │     Bridge      │  │   (to n8n)   │  │  (Claude API)     │   │
│  └─────────────────┘  └──────────────┘  └───────────────────┘   │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Webhook
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                      n8n Workflows                               │
│  • slack-mention-handler   - 일반 멘션 처리                       │
│  • slack-mr-review         - GitLab MR 리뷰 자동화               │
│  • gitlab-mr-review        - GitLab Webhook 처리                 │
│  • daily-report            - 일일 리포트 생성                     │
└─────────────────────────────────────────────────────────────────┘
```

## 빠른 시작

### 1. Slack App 생성

1. [Slack API](https://api.slack.com/apps) 접속
2. **Create New App** → **From scratch**
3. **Socket Mode** 활성화
   - Settings → Socket Mode → Enable
   - App Token 생성 (`xapp-xxx`)
4. **Event Subscriptions** 활성화
   - Subscribe to bot events: `app_mention`, `message.channels`, `reaction_added`
5. **OAuth & Permissions**
   - Bot Token Scopes: `chat:write`, `reactions:read`, `reactions:write`
   - Install to Workspace → Bot Token 복사 (`xoxb-xxx`)

### 2. 환경 변수 설정

```bash
cd docker-compose
cp .env.example .env
```

`.env` 파일 수정:
```bash
# Required
SLACK_APP_TOKEN=xapp-xxx
SLACK_BOT_TOKEN=xoxb-xxx
SLACK_SIGNING_SECRET=xxx

# Optional: GitLab MR Review
GITLAB_URL=https://gitlab.example.com
GITLAB_GROUP=my-org/my-group
GITLAB_TOKEN=glpat-xxx
```

> **Note**: `ANTHROPIC_API_KEY`는 필요 없습니다. Claude CLI가 자체 인증(`claude login`)을 사용합니다.

### 3. 실행

```bash
# Docker Compose로 실행
cd docker-compose
docker-compose up -d

# 또는 로컬에서 직접 실행
./gradlew :claude-flow-app:bootRun
```

### 4. n8n 워크플로우 설정

n8n 컨테이너가 시작되면 워크플로우가 자동으로 임포트됩니다.

수동 설정이 필요한 경우:
1. http://localhost:5678 접속 (초기 계정: admin@local.dev / Localdev123)
2. Credentials → GitLab Token 생성 (HTTP Header Auth, PRIVATE-TOKEN)
3. Workflows에서 필요한 워크플로우 활성화

### 5. 테스트

Slack에서 봇 멘션:
```
@claude-flow 안녕하세요!

@claude-flow authorization-server !42 리뷰해줘
```

## 프로젝트 구조

```
claude-flow/
├── claude-flow-core/          # 공통 도메인 모델
│   ├── model/                 # Agent, Project, Event
│   ├── plugin/                # GitLab, GitHub, Jira 플러그인
│   ├── routing/               # 에이전트 라우팅
│   └── session/               # 세션 관리
├── claude-flow-executor/      # Claude API 래퍼
│   └── ClaudeExecutor.kt
├── claude-flow-api/           # API 레이어
│   ├── slack/                 # Socket Mode 브릿지
│   ├── rest/                  # REST API
│   └── command/               # 슬래시 명령어
├── claude-flow-app/           # 실행 가능한 애플리케이션
├── docker-compose/            # Docker 설정
│   ├── docker-compose.yml
│   ├── n8n-workflows/         # n8n 워크플로우 JSON
│   └── scripts/               # 설정 스크립트
├── dashboard/                 # React 대시보드
└── docs/                      # 문서
```

## 기술 스택

- **Kotlin 2.1** + **Spring Boot 3.4**
- **Slack Bolt for Java** (Socket Mode)
- **Claude CLI** (AI 추론, 자체 인증)
- **Kotlin Coroutines** (비동기 처리)
- **n8n** (워크플로우 오케스트레이션)
- **React** + **Vite** + **TailwindCSS** (대시보드)

## API 엔드포인트

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/v1/execute` | Claude 실행 (단일 턴) |
| POST | `/api/v1/execute-with-routing` | 에이전트 라우팅 포함 실행 |
| POST | `/api/v1/slack/message` | Slack 메시지 전송 |
| POST | `/api/v1/slack/reaction` | Slack 리액션 추가/제거 |
| GET | `/api/v1/agents` | 에이전트 목록 |
| GET | `/api/v1/analytics/stats` | 실행 통계 |
| GET | `/api/v1/system/health` | Health check |

## n8n 워크플로우

| 워크플로우 | 트리거 | 설명 |
|-----------|--------|------|
| slack-mention-handler | Webhook | 일반 Slack 멘션 처리 |
| slack-mr-review | Webhook | GitLab MR 리뷰 요청 처리 |
| gitlab-mr-review | GitLab Webhook | MR 생성 시 자동 리뷰 |
| daily-report | Schedule (09:00) | 일일 리포트 생성 |
| slack-reaction-handler | Webhook | 리액션 기반 액션 |
| slack-slash-command | Webhook | 슬래시 명령어 처리 |

## 설정

`application.yml`:
```yaml
claude-flow:
  slack:
    app-token: ${SLACK_APP_TOKEN}
    bot-token: ${SLACK_BOT_TOKEN}
  webhook:
    base-url: http://n8n:5678
  claude:
    model: claude-sonnet-4-20250514
    timeout-seconds: 600
  gitlab:
    url: ${GITLAB_URL:}
    token: ${GITLAB_TOKEN:}
```

## Dashboard

React 기반 대시보드로 실행 기록, 에이전트 상태, 분석을 확인할 수 있습니다.

```bash
cd dashboard
npm install
npm run dev
# http://localhost:5173
```

## License

MIT
