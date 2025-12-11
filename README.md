# claude-flow

Slack에서 Claude Code를 실행하는 AI 에이전트 플랫폼

> Claudio를 벤치마킹하여 Kotlin/Spring Boot로 재구현

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                         Slack                                    │
│                    (@멘션, 메시지, 리액션)                         │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Socket Mode (WebSocket)
                            │ ← ngrok 불필요!
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    claude-flow (Kotlin)                          │
│  ┌─────────────────┐  ┌──────────────┐  ┌───────────────────┐   │
│  │ SlackSocketMode │→ │ WebhookSender│→ │  ClaudeExecutor   │   │
│  │     Bridge      │  │   (to n8n)   │  │  (Claude CLI)     │   │
│  └─────────────────┘  └──────────────┘  └───────────────────┘   │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    REST API                              │    │
│  │  POST /api/v1/execute      - Claude 실행                 │    │
│  │  POST /api/v1/slack/message - Slack 메시지 전송          │    │
│  │  POST /api/v1/slack/reaction - Slack 리액션 추가/제거    │    │
│  └─────────────────────────────────────────────────────────┘    │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Webhook
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                      n8n Workflows                               │
│  • slack-mention-handler    - 멘션 처리                          │
│  • slack-message-handler    - 메시지 처리                        │
│  • slack-reaction-handler   - 리액션 처리                        │
└─────────────────────────────────────────────────────────────────┘
```

## 주요 기능

- **Socket Mode**: ngrok 없이 로컬에서 Slack 연동
- **Claude Code 실행**: CLI 래핑으로 코드베이스 접근
- **n8n 워크플로우**: 유연한 이벤트 처리 파이프라인
- **멀티 에이전트**: 키워드 기반 에이전트 라우팅

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

# .env 파일 수정
SLACK_APP_TOKEN=xapp-xxx
SLACK_BOT_TOKEN=xoxb-xxx
ANTHROPIC_API_KEY=sk-ant-xxx
PROJECT_PATH=/path/to/your/projects
```

### 3. 실행

```bash
# Docker Compose로 실행
cd docker-compose
docker-compose up -d

# 또는 로컬에서 직접 실행
./gradlew :claude-flow-app:bootRun
```

### 4. n8n 워크플로우 임포트

1. http://localhost:5678 접속
2. Workflows → Import → `n8n-workflows/slack-mention-handler.json`
3. Workflow 활성화

### 5. 테스트

Slack에서 봇 멘션:
```
@claude-flow 이 프로젝트의 구조를 설명해줘
```

## 프로젝트 구조

```
claude-flow/
├── claude-flow-core/          # 공통 도메인 모델
│   └── model/                 # Agent, Project, Event
├── claude-flow-executor/      # Claude CLI 래퍼
│   └── ClaudeExecutor.kt
├── claude-flow-api/           # API 레이어
│   ├── slack/                 # Socket Mode 브릿지
│   ├── webhook/               # n8n 웹훅 전송
│   └── rest/                  # REST API
├── claude-flow-app/           # 실행 가능한 애플리케이션
├── docker-compose/            # Docker 설정
│   ├── docker-compose.yml
│   ├── Dockerfile
│   └── n8n-workflows/         # n8n 워크플로우 JSON
└── build.gradle.kts
```

## 기술 스택

- **Kotlin 2.1** + **Spring Boot 3.4**
- **Slack Bolt for Java** (Socket Mode)
- **Kotlin Coroutines** (비동기 처리)
- **Ktor Client** (HTTP 요청)
- **n8n** (워크플로우 오케스트레이션)

## API 엔드포인트

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/v1/execute` | Claude Code 실행 |
| POST | `/api/v1/slack/message` | Slack 메시지 전송 |
| POST | `/api/v1/slack/reaction` | Slack 리액션 추가/제거 |
| GET | `/api/v1/health` | Health check |

## 설정

`application.yml`:
```yaml
claude-flow:
  slack:
    app-token: ${SLACK_APP_TOKEN}
    bot-token: ${SLACK_BOT_TOKEN}
  webhook:
    base-url: http://localhost:5678
  claude:
    model: claude-sonnet-4-20250514
    timeout-seconds: 300
```

## License

MIT
