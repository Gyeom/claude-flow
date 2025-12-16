# Claude Flow

Slack에서 Claude를 호출하고, GitLab MR 리뷰를 자동화하는 AI 에이전트 플랫폼입니다.

## 주요 기능

- **Slack 연동**: `@claude` 멘션으로 Claude와 대화
- **GitLab MR 리뷰**: `@claude project-name !123 리뷰해줘`로 자동 코드 리뷰
- **사용자 컨텍스트**: 대화 기록 요약, 개인별 선호도 저장
- **실시간 분석**: 응답 시간, 사용량, 비용 대시보드
- **n8n 워크플로우**: 유연한 이벤트 처리 및 확장

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

# 선택 - GitLab (MR 리뷰 기능)
GITLAB_URL=https://gitlab.example.com
GITLAB_TOKEN=glpat-xxx              # api scope 권한 필요
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
┌─────────────────────────────────────────┐
│           Claude Flow (Kotlin)          │
│  SlackBridge → AgentRouter → Executor   │
│         │                               │
│   Storage │ UserContext │ Analytics     │
└─────────────────────────────────────────┘
    │ Webhook
    ▼
┌─────────────────────────────────────────┐
│              n8n Workflows              │
│  • slack-mention-handler                │
│  • gitlab-mr-review                     │
│  • user-context-handler                 │
└─────────────────────────────────────────┘
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

### Execute
| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/v1/execute` | Claude 실행 |
| GET | `/api/v1/health` | 헬스체크 |

### Analytics
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/analytics/dashboard` | 대시보드 데이터 |
| GET | `/api/v1/analytics/overview` | P50/P90/P95/P99 통계 |

### User Context
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/users/{userId}/context` | 사용자 컨텍스트 |
| POST | `/api/v1/users/{userId}/rules` | 규칙 추가 |

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

- **Backend**: Kotlin 2.1, Spring Boot 3.4
- **AI**: Claude CLI
- **Slack**: Bolt for Java (Socket Mode)
- **Workflow**: n8n
- **Storage**: SQLite
- **Dashboard**: React, Vite, TailwindCSS

## 라이선스

MIT License
