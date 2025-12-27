# Claude Flow 시작 및 초기화 가이드

이 문서는 Claude Flow의 시작, 초기화, 설정 메커니즘을 설명합니다.

## 설계 원칙

> **원커맨드 시작**: `/start` 하나로 모든 서비스가 구동되고, 설정이 자동 로드되며, 재시작 시에도 최신 상태가 유지되어야 한다.

| 원칙 | 설명 |
|------|------|
| 단일 진입점 | `/start` 명령 하나로 인프라 + 앱 전체 시작 |
| 설정 자동 로드 | `.env`, `projects.json`, `application.yml` 자동 반영 |
| 재시작 시 최신화 | 워크플로우, 프로젝트 설정 등 재시작 시 자동 동기화 |
| 실패 시 명확한 피드백 | 누락된 설정, 포트 충돌 등 즉시 알림 |

## 빠른 시작

```bash
# 모든 서비스 시작 (인프라 + 앱)
/start

# 상태 확인
/health

# 모든 서비스 중지
/stop

# 재시작
/restart
```

## 아키텍처 개요

```
┌─────────────────────────────────────────────────────────────────┐
│                        /start 실행                               │
└─────────────────────────────┬───────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌───────────────┐    ┌───────────────┐    ┌───────────────┐
│    Qdrant     │    │     n8n       │    │    Ollama     │
│  (Docker)     │    │  (Docker)     │    │   (brew)      │
│  :6333        │    │  :5678        │    │  :11434       │
└───────────────┘    └───────┬───────┘    └───────────────┘
                             │
                    n8n-entrypoint.sh
                             │
              ┌──────────────┴──────────────┐
              ▼                              ▼
     [첫 시작]                        [재시작]
     setup-n8n.mjs                  sync-workflows.mjs
     - Owner 계정 생성               - 세션 로그인
     - Credential 설정              - JSON 파일 동기화
     - 워크플로우 Import             - 워크플로우 업데이트
              │                              │
              └──────────────┬───────────────┘
                             ▼
                    ┌───────────────┐
                    │   Backend     │
                    │  (Gradle)     │
                    │  :8080        │
                    └───────┬───────┘
                            │
                   .env 자동 로드
                            │
                            ▼
                    ┌───────────────┐
                    │  Dashboard    │
                    │  (Vite)       │
                    │  :3000        │
                    └───────────────┘
```

## 서비스 구성

| 서비스 | 포트 | 실행 방식 | 용도 |
|--------|------|----------|------|
| Qdrant | 6333 | Docker | Vector DB (RAG) |
| Ollama | 11434 | brew services | Embedding 모델 |
| n8n | 5678 | Docker | 워크플로우 엔진 |
| Backend | 8080 | Gradle bootRun | Spring Boot API |
| Dashboard | 3000 | Vite dev | React UI |

## 자동 로드되는 설정 파일

```
┌─────────────────────────────────────────────────────────────────────┐
│                        자동 로드 순서                                │
└─────────────────────────────────────────────────────────────────────┘

[1] Gradle bootRun 실행
     │
     ├──▶ docker-compose/.env          → 환경변수로 주입
     │
     ▼
[2] Spring Boot 시작
     │
     ├──▶ application.yml              → Spring 설정 로드
     │    (환경변수 ${...} 치환)
     │
     ▼
[3] Storage 초기화
     │
     └──▶ config/projects.json         → DB에 프로젝트 동기화
```

### 자동 로드 파일 요약

| 파일 | 로드 시점 | 로드 주체 | 용도 |
|------|----------|----------|------|
| `docker-compose/.env` | Gradle bootRun 시작 | `build.gradle.kts` doFirst | 환경변수 주입 |
| `application.yml` | Spring Boot 시작 | Spring Boot | 앱 설정 (포트, 연동 정보) |
| `config/projects.json` | Storage 초기화 | `Storage.kt` | 프로젝트/별칭 DB 동기화 |
| `n8n-workflows/*.json` | n8n 재시작 | `sync-workflows.mjs` | 워크플로우 동기화 |

---

### config/projects.json

**로드 시점**: Spring Boot 시작 → Storage 초기화 시 자동 로드

프로젝트 목록과 GitLab 연동 설정:

```json
{
  "defaultBranch": "develop",
  "gitlabHost": "https://gitlab.example.com",
  "projects": [
    {
      "id": "my-project",
      "name": "My Project",
      "description": "프로젝트 설명",
      "path": "my-project",
      "gitlabPath": "group/my-project",
      "aliases": ["mp", "마이프로젝트"]
    }
  ]
}
```

| 필드 | 설명 | 필수 |
|------|------|------|
| `id` | 고유 식별자 | ✓ |
| `name` | 표시 이름 | ✓ |
| `path` | 로컬 디렉토리명 | ✓ |
| `gitlabPath` | GitLab 프로젝트 경로 (MR 리뷰용) | |
| `aliases` | 검색/라우팅용 별칭 | |
| `isDefault` | 기본 프로젝트 여부 | |

---

### application.yml

**위치**: `claude-flow-app/src/main/resources/application.yml`

**로드 시점**: Spring Boot 시작 시 자동 로드 (환경변수 치환)

```yaml
server:
  port: 8080

claude-flow:
  slack:
    app-token: ${SLACK_APP_TOKEN:}
    bot-token: ${SLACK_BOT_TOKEN:}
    signing-secret: ${SLACK_SIGNING_SECRET:}

  claude:
    model: ${CLAUDE_MODEL:claude-sonnet-4-20250514}
    timeout-seconds: ${CLAUDE_TIMEOUT:900}

  workspace:
    path: ${WORKSPACE_PATH:.}

  gitlab:
    url: ${GITLAB_URL:}
    token: ${GITLAB_TOKEN:}

  jira:
    url: ${JIRA_URL:}
    email: ${JIRA_EMAIL:}
    api-token: ${JIRA_API_TOKEN:}

  qdrant:
    url: ${QDRANT_URL:http://localhost:6333}

  ollama:
    url: ${OLLAMA_URL:http://localhost:11434}
    model: ${OLLAMA_EMBEDDING_MODEL:qwen3-embedding:0.6b}
```

**환경변수 치환**: `${VAR:default}` 형식으로 환경변수가 없으면 기본값 사용

---

### docker-compose/.env

**위치**: `docker-compose/.env` (git 제외), 템플릿: `.env.example`

**로드 시점**: Gradle bootRun doFirst 블록에서 환경변수로 주입

**로드 메커니즘** (`claude-flow-app/build.gradle.kts`):

```kotlin
tasks.named<BootRun>("bootRun") {
    doFirst {
        val envFile = file("${rootProject.projectDir}/docker-compose/.env")
        if (envFile.exists()) {
            envFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .forEach { line ->
                    val (key, value) = line.split("=", limit = 2)
                    environment(key.trim(), value.trim())
                }
        }
    }
}
```

**필수 환경 변수**:

```bash
# Slack 연동 (필수)
SLACK_APP_TOKEN=xapp-xxx
SLACK_BOT_TOKEN=xoxb-xxx
SLACK_SIGNING_SECRET=xxx
```

### 선택 환경 변수

```bash
# Claude 설정
CLAUDE_MODEL=claude-sonnet-4-20250514
CLAUDE_TIMEOUT=300

# GitLab 연동
GITLAB_URL=https://gitlab.example.com
GITLAB_TOKEN=glpat-xxx

# Jira 연동
JIRA_URL=https://company.atlassian.net
JIRA_EMAIL=email@company.com
JIRA_API_TOKEN=xxx

# n8n 관리자 계정
N8N_DEFAULT_EMAIL=admin@local.dev
N8N_DEFAULT_PASSWORD=Localdev123
```

## n8n 초기화 흐름

### 첫 시작 (setup-n8n.mjs)

```
1. Owner 계정 생성
   POST /rest/owner/setup
   - email: admin@local.dev
   - password: Localdev123

2. 로그인 (세션 쿠키 획득)
   POST /rest/login

3. Credential 생성 (GITLAB_TOKEN이 있는 경우)
   POST /rest/credentials
   - name: "GitLab Token"
   - type: "httpHeaderAuth"

4. 워크플로우 Import
   docker-compose/n8n-workflows/*.json
   - {{GITLAB_CREDENTIAL_ID}} 플레이스홀더 치환
   - POST /rest/workflows

5. 마커 파일 생성
   /home/node/.n8n/.setup_complete
```

### 재시작 (sync-workflows.mjs)

```
1. 세션 로그인 (이메일/비밀번호)
   POST /rest/login
   - emailOrLdapLoginId: admin@local.dev
   - password: Localdev123
   → 세션 쿠키 획득

2. Credential ID 조회
   GET /rest/credentials

3. 워크플로우 동기화
   - JSON 파일 스캔
   - 기존 워크플로우 조회
   - 생성 또는 업데이트 (PATCH)
```

## 스크립트 파일 구조

```
docker-compose/scripts/
├── n8n-entrypoint.sh      # n8n 컨테이너 시작점
├── ollama-entrypoint.sh   # Ollama 컨테이너 시작점
├── setup-n8n.mjs          # n8n 초기 설정 (1회)
├── sync-workflows.mjs     # 워크플로우 동기화 (재시작)
└── backup-workflows.mjs   # 워크플로우 백업 (/backup)
```

### n8n-entrypoint.sh

```bash
#!/bin/sh
MARKER_FILE="/home/node/.n8n/.setup_complete"

# 백그라운드에서 초기화
(
    sleep 15  # n8n 시작 대기

    if [ ! -f "$MARKER_FILE" ]; then
        node setup-n8n.mjs      # 첫 시작
        touch "$MARKER_FILE"
    else
        node sync-workflows.mjs  # 재시작
    fi
) &

exec n8n "$@"  # 포그라운드에서 n8n 실행
```

### ollama-entrypoint.sh

```bash
#!/bin/bash
ollama serve &
OLLAMA_PID=$!

# 서버 준비 대기
for i in {1..30}; do
    curl -s http://localhost:11434/api/tags && break
    sleep 2
done

# 임베딩 모델 설치 (없는 경우)
MODEL=${OLLAMA_EMBEDDING_MODEL:-qwen3-embedding:0.6b}
if ! ollama list | grep -q "$MODEL"; then
    ollama pull "$MODEL"
fi

wait $OLLAMA_PID
```

## 워크플로우 관리

### 워크플로우 파일 위치

```
docker-compose/n8n-workflows/
├── slack-mention-handler.json      # Slack 멘션 처리
├── slack-action-handler.json       # Slack 버튼 액션
├── slack-feedback-handler.json     # 피드백 수집
├── slack-mr-review-v2.json         # MR 리뷰 (최신)
├── scheduled-mr-review.json        # 스케줄 MR 리뷰
├── gitlab-feedback-poller.json     # GitLab 피드백 수집
├── alert-channel-monitor.json      # 장애 알람 모니터링
├── alert-to-mr-pipeline.json       # 알람→MR 파이프라인
└── user-context-handler.json       # 사용자 컨텍스트
```

### 워크플로우 동기화

```bash
# JSON → n8n 동기화 (자동)
# n8n 재시작 시 sync-workflows.mjs가 자동 실행

# n8n → JSON 백업
/backup
```

### Credential 플레이스홀더

워크플로우 JSON에서 `{{GITLAB_CREDENTIAL_ID}}`를 사용하면,
setup/sync 스크립트가 실제 Credential ID로 치환합니다.

```json
{
  "credentials": {
    "httpHeaderAuth": {
      "id": "{{GITLAB_CREDENTIAL_ID}}",
      "name": "GitLab Token"
    }
  }
}
```

## 슬래시 커맨드

| 커맨드 | 설명 |
|--------|------|
| `/start` | 모든 서비스 시작 |
| `/stop` | 모든 서비스 중지 |
| `/restart` | 모든 서비스 재시작 |
| `/health` | 전체 상태 확인 |
| `/infra start\|stop\|status` | 인프라만 관리 |
| `/app start\|stop\|restart\|logs` | 앱만 관리 |
| `/backup` | n8n 워크플로우 백업 |

## 트러블슈팅

### n8n 로그인 실패

```bash
# n8n 로그 확인
docker logs claude-flow-n8n

# 초기화 다시 실행 (마커 파일 삭제)
docker exec claude-flow-n8n rm /home/node/.n8n/.setup_complete
docker restart claude-flow-n8n
```

### .env 로드 확인

```bash
# bootRun 시 로드 상태 출력됨
./gradlew :claude-flow-app:bootRun

# 출력 예시:
# ✓ Loaded 8 vars from docker-compose/.env
#   ✓ Slack
#   ✗ Figma
#   ✓ GitLab
#   ✓ Jira
```

### 포트 충돌

```bash
# 포트 사용 프로세스 확인
lsof -i :8080
lsof -i :3000
lsof -i :5678

# 강제 종료
lsof -ti:8080 | xargs kill -9
```

### 워크플로우 동기화 문제

```bash
# n8n 재시작으로 동기화 트리거
docker restart claude-flow-n8n

# 또는 수동 동기화
docker exec claude-flow-n8n node /home/node/scripts/sync-workflows.mjs
```
