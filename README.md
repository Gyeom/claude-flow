# Claude Flow

Slack에서 Claude를 호출하고, GitLab MR 리뷰를 자동화하는 AI 에이전트 플랫폼입니다.

## 주요 기능

- **Slack 연동**: `@claude` 멘션으로 Claude와 대화
- **GitLab MR 리뷰**: `@claude project-name !123 리뷰해줘`로 자동 코드 리뷰
- **Jira 연동**: AI 기반 이슈 분석, 자연어 JQL 변환, 스프린트 리포트 자동 생성
- **실시간 스트리밍 채팅**: SSE 기반 실시간 응답 스트리밍
- **지능형 라우팅**: 키워드 → 시맨틱 → LLM 폴백 3단계 라우팅
- **플러그인 시스템**: GitLab, Jira, GitHub, n8n 플러그인 확장
- **프로젝트 관리**: 프로젝트별 에이전트, 채널 매핑, Rate Limiting
- **사용자 컨텍스트**: 대화 기록 요약, 개인별 선호도/규칙 저장
- **실시간 분석**: P50/P90/P95/P99 통계, 시계열 차트, 피드백 분석
- **n8n 워크플로우**: 자연어로 워크플로우 자동 생성, 유연한 이벤트 처리
- **RAG (선택)**: Qdrant + Ollama 기반 대화 검색 및 컨텍스트 증강

### Dashboard 기능

| 페이지 | 기능 |
|--------|------|
| Dashboard | 실시간 통계, 요약 차트 |
| Chat | 웹 기반 채팅 인터페이스 |
| History | 실행 이력 조회 |
| Live Logs | 실시간 로그 스트리밍 |
| Projects | 프로젝트 관리 |
| Jira | Jira 이슈 관리, AI 분석, 자연어 JQL |
| Agents | 글로벌 에이전트 설정 |
| Analytics | 상세 통계 (백분위수, 시계열) |
| Feedback | 피드백 분석 |
| Models | 모델별 사용량 |
| Errors | 에러 통계 |
| Plugins | 플러그인 관리 (GitLab, Jira, n8n) |
| Workflows | n8n 워크플로우 관리/생성 |
| Settings | 시스템 설정 |

## 빠른 시작

### 사전 요구사항

- Docker & Docker Compose
- Slack 워크스페이스 (앱 설치 권한)
- Claude CLI 인증 (`claude login` 완료)

### 1. 저장소 클론

```bash
git clone https://github.com/Gyeom/claude-flow.git
cd claude-flow
```

### 2. Slack 앱 생성

1. [Slack API](https://api.slack.com/apps) 접속 → **Create New App** → **From scratch**
2. 앱 이름: `Claude Flow`, 워크스페이스 선택

3. **Socket Mode** 활성화:
   - Settings > Socket Mode > Enable Socket Mode
   - App-Level Token 생성 (scope: `connections:write`)
   - **xapp-xxx** 토큰 복사

4. **Bot Token Scopes** 추가 (OAuth & Permissions):
   ```
   app_mentions:read    - @멘션 읽기
   chat:write           - 메시지 전송
   reactions:read       - 리액션 읽기
   reactions:write      - 리액션 추가
   im:history           - DM 기록 읽기
   im:read              - DM 접근
   im:write             - DM 전송
   ```

5. **Event Subscriptions** 활성화:
   - Subscribe to bot events: `app_mention`, `message.im`

6. **워크스페이스에 앱 설치** → **xoxb-xxx** 토큰 복사

7. Basic Information에서 **Signing Secret** 복사

### 3. 환경 설정

```bash
cd docker-compose
cp .env.example .env
```

`.env` 파일 편집:
```bash
# 필수 - Slack
SLACK_APP_TOKEN=xapp-1-xxx          # Socket Mode 토큰
SLACK_BOT_TOKEN=xoxb-xxx            # Bot 토큰
SLACK_SIGNING_SECRET=xxx            # Signing Secret

# Claude 설정
CLAUDE_MODEL=claude-sonnet-4-20250514  # 사용할 모델
CLAUDE_TIMEOUT=300                      # 타임아웃 (초)

# 선택 - GitLab (MR 리뷰 기능)
GITLAB_URL=https://gitlab.example.com
GITLAB_TOKEN=glpat-xxx              # api scope 권한 필요
GITLAB_GROUP=my-org/my-group        # 그룹 경로 (멀티 프로젝트 쿼리용)

# 선택 - Jira
JIRA_URL=https://your-org.atlassian.net
JIRA_EMAIL=your-email@example.com
JIRA_API_TOKEN=xxx                  # API 토큰

# 선택 - RAG (벡터 검색)
RAG_ENABLED=true
QDRANT_URL=http://qdrant:6333
OLLAMA_URL=http://ollama:11434
```

### 4. 실행

```bash
# 프로젝트 루트에서
./start.sh
```

또는 수동 실행:
```bash
cd docker-compose
docker compose up -d
```

### 5. 확인

```bash
# 서비스 상태
./start.sh --status

# 로그 확인
./start.sh --logs
```

**접속 URL:**
- API: http://localhost:8080
- Dashboard: http://localhost:5173
- n8n: http://localhost:5678 (admin@local.dev / Localdev123)

### 6. 사용

Slack에서:
```
@claude 안녕하세요!
@claude authorization-server !42 리뷰해줘
```

## Claude CLI 인증

Claude Flow는 [Claude CLI](https://docs.anthropic.com/en/docs/claude-code)를 사용합니다. API 키가 아닌 CLI 인증 방식입니다.

### 로컬 개발 시

```bash
# Claude CLI 설치
npm install -g @anthropic-ai/claude-code

# 인증 (브라우저에서 로그인)
claude login
```

### Docker 환경

Docker 컨테이너에서 Claude CLI를 사용하려면 인증 정보를 마운트해야 합니다:

```yaml
# docker-compose.yml에 추가
volumes:
  - ~/.claude:/home/appuser/.claude:ro
```

## 로컬 개발 (Docker 없이)

```bash
# 1. 빌드
./gradlew build

# 2. 환경변수 설정
export SLACK_APP_TOKEN=xapp-xxx
export SLACK_BOT_TOKEN=xoxb-xxx
export SLACK_SIGNING_SECRET=xxx

# 3. 실행
./gradlew :claude-flow-app:bootRun

# 4. 대시보드 (별도 터미널)
cd dashboard
npm install
npm run dev
```

## 아키텍처

```
Slack (@멘션)
    │ Socket Mode
    ▼
┌─────────────────────────────────────────────────┐
│             Claude Flow (Kotlin)                │
│  SlackBridge → AgentRouter → Executor           │
│         │                                       │
│   Storage │ UserContext │ Analytics │ Plugins   │
│                                                 │
│   ┌─────────────────────────────────────────┐   │
│   │ Plugins: GitLab │ Jira │ GitHub │ n8n   │   │
│   └─────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
    │ Webhook
    ▼
┌─────────────────────────────────────────────────┐
│                n8n Workflows                    │
│  • slack-mention-handler    (멘션 처리)         │
│  • slack-mr-review          (MR 리뷰)           │
│  • slack-action-handler     (버튼 액션)         │
│  • slack-feedback-handler   (피드백 수집)       │
│  • alert-channel-monitor    (장애 알람 분석)    │
│  • alert-to-mr-pipeline     (알람→MR 자동화)    │
└─────────────────────────────────────────────────┘
```

## 프로젝트 구조

```
claude-flow/
├── claude-flow-core/       # 도메인 모델, 라우팅, 스토리지
├── claude-flow-executor/   # Claude CLI 래퍼
├── claude-flow-api/        # REST API
├── claude-flow-app/        # Spring Boot 앱 (Slack 연동)
├── dashboard/              # React 대시보드
├── docker-compose/         # Docker 설정
│   ├── .env.example        # 환경변수 템플릿
│   ├── docker-compose.yml  # 서비스 정의
│   └── n8n-workflows/      # n8n 워크플로우 JSON
└── start.sh                # 원클릭 실행 스크립트
```

## API

### Execute & Chat
| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/v1/execute` | Claude 실행 |
| POST | `/api/v1/execute-with-routing` | 라우팅 + 실행 통합 |
| POST | `/api/v1/chat/stream` | SSE 스트리밍 채팅 |
| POST | `/api/v1/chat/execute` | 비스트리밍 채팅 |
| GET | `/api/v1/health` | 헬스체크 |

### Projects
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/projects` | 프로젝트 목록 |
| POST | `/api/v1/projects` | 프로젝트 생성 |
| GET | `/api/v1/projects/{id}` | 프로젝트 조회 |
| PATCH | `/api/v1/projects/{id}` | 프로젝트 수정 |
| DELETE | `/api/v1/projects/{id}` | 프로젝트 삭제 |
| GET | `/api/v1/projects/{id}/agents` | 프로젝트 에이전트 목록 |
| POST | `/api/v1/projects/{id}/channels` | 채널 매핑 |
| GET | `/api/v1/projects/{id}/stats` | 프로젝트 통계 |

### Agents (v1 & v2)
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/agents` | 에이전트 목록 |
| POST | `/api/v1/agents` | 에이전트 생성 |
| GET | `/api/v2/agents` | 에이전트 목록 (v2) |
| GET | `/api/v2/agents/{id}` | 에이전트 조회 |
| PATCH | `/api/v2/agents/{id}` | 에이전트 수정 |
| DELETE | `/api/v2/agents/{id}` | 에이전트 삭제 |

### Analytics
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/analytics/dashboard` | 대시보드 데이터 |
| GET | `/api/v1/analytics/overview` | P50/P90/P95/P99 통계 |
| GET | `/api/v1/analytics/percentiles` | 백분위수 조회 |
| GET | `/api/v1/analytics/timeseries` | 시계열 데이터 |
| GET | `/api/v1/analytics/models` | 모델별 통계 |
| GET | `/api/v1/analytics/errors` | 에러 통계 |
| GET | `/api/v1/analytics/users` | 사용자별 통계 |
| GET | `/api/v1/analytics/feedback/verified` | 검증된 피드백 통계 |

### Users
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/users` | 사용자 목록 |
| GET | `/api/v1/users/{userId}` | 사용자 상세 |
| GET | `/api/v1/users/{userId}/context` | 사용자 컨텍스트 |
| PUT | `/api/v1/users/{userId}/context` | 컨텍스트 저장 |
| GET | `/api/v1/users/{userId}/rules` | 규칙 조회 |
| POST | `/api/v1/users/{userId}/rules` | 규칙 추가 |
| GET | `/api/v1/users/{userId}/context/formatted` | 포맷팅된 컨텍스트 |

### System
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/system/health` | 시스템 상태 |
| GET | `/api/v1/system/slack/status` | Slack 연결 상태 |
| POST | `/api/v1/system/slack/reconnect` | Slack 재연결 |

### Jira Analysis (AI 기반)
| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/v1/jira/analyze/{issueKey}` | 이슈 분석 및 구현 방향 제안 |
| POST | `/api/v1/jira/analyze/{issueKey}/code-context` | 관련 코드 분석 |
| POST | `/api/v1/jira/sprint-report` | 스프린트 리포트 생성 |
| POST | `/api/v1/jira/nl-to-jql` | 자연어 → JQL 변환 |
| POST | `/api/v1/jira/auto-label/{issueKey}` | 자동 라벨링 |
| POST | `/api/v1/jira/analyze-text` | 텍스트 → 이슈 필드 제안 |

### Plugins
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/plugins` | 플러그인 목록 |
| GET | `/api/v1/plugins/{id}` | 플러그인 상세 |
| POST | `/api/v1/plugins/{id}/execute` | 플러그인 명령 실행 |
| PATCH | `/api/v1/plugins/{id}/enabled` | 활성화/비활성화 |
| GET | `/api/v1/plugins/gitlab/mrs` | GitLab MR 목록 |
| GET | `/api/v1/plugins/jira/issues/{key}` | Jira 이슈 조회 |
| POST | `/api/v1/plugins/jira/issues` | Jira 이슈 생성 |
| GET | `/api/v1/plugins/jira/search` | Jira JQL 검색 |

### n8n Workflows
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/n8n/workflows` | 워크플로우 목록 |
| GET | `/api/v1/n8n/workflows/{id}` | 워크플로우 상세 |
| POST | `/api/v1/n8n/workflows/generate` | 자연어로 워크플로우 생성 |
| POST | `/api/v1/n8n/workflows/template/{id}` | 템플릿 기반 생성 |
| GET | `/api/v1/n8n/templates` | 사용 가능한 템플릿 목록 |
| POST | `/api/v1/n8n/workflows/{id}/run` | 워크플로우 실행 |
| PATCH | `/api/v1/n8n/workflows/{id}/active` | 활성화/비활성화 |

## 문제 해결

### Slack 연결 실패

```bash
# 토큰 확인
echo $SLACK_BOT_TOKEN

# Socket Mode 활성화 여부 확인
# Slack API > Settings > Socket Mode > Enabled
```

### Claude CLI 인증 오류

```bash
# 인증 상태 확인
claude --version

# 재인증
claude logout
claude login
```

### n8n 워크플로우 미작동

1. http://localhost:5678 접속
2. 워크플로우 목록에서 활성화 상태 확인 (토글 ON)
3. Webhook URL이 올바른지 확인

### 포트 충돌

```bash
# 사용 중인 포트 확인
lsof -i :8080
lsof -i :5678

# 프로세스 종료
kill -9 <PID>
```

## 명령어 모음

```bash
./start.sh              # 인터랙티브 설정 + 시작
./start.sh --quick      # 기존 설정으로 빠른 시작
./start.sh --stop       # 서비스 중지
./start.sh --status     # 상태 확인
./start.sh --logs       # 로그 확인
./start.sh --backup     # n8n 워크플로우 백업
./start.sh --reset      # 데이터 초기화 (주의!)
```

## 기술 스택

- **Backend**: Kotlin 2.1, Spring Boot 3.4, Spring WebFlux
- **AI**: Claude CLI
- **Slack**: Bolt for Java (Socket Mode)
- **Workflow**: n8n (자동 생성 지원)
- **Storage**: SQLite (WAL mode)
- **Cache**: Caffeine
- **Vector DB**: Qdrant (선택)
- **Embedding**: Ollama (선택)
- **Dashboard**: React, Vite, TailwindCSS

## 라이선스

MIT License
