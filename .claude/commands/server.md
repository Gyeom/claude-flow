---
description: "Manage Claude Flow backend server (start/stop/restart/status/logs)"
allowed-arguments: ["start", "stop", "restart", "status", "logs"]
argument-hint: "<action: start|stop|restart|status|logs>"
---

# Server Management Command

Claude Flow 백엔드 서버를 관리합니다.

## Arguments
- `$ARGUMENTS` - 액션: start, stop, restart, status, logs (기본: status)

## Instructions

### 1. 환경 변수 로드
두 개의 .env 파일에서 환경 변수를 읽어서 사용하세요:

**프로젝트 루트 .env** (API 키)
- FIGMA_ACCESS_TOKEN
- GITLAB_TOKEN
- JIRA_API_TOKEN

**docker-compose/.env** (Slack/워크스페이스)
- SLACK_APP_TOKEN
- SLACK_BOT_TOKEN
- WORKSPACE_PATH (기본값: <project-root>/data)

### 2. 액션별 동작

**status (기본)**
```bash
curl -s http://localhost:8080/api/v1/health
lsof -i :8080 | head -3
```

**start**
```bash
# 두 .env 파일에서 환경 변수 로드 후 인라인 전달
# 1. 루트 .env와 docker-compose/.env 모두 읽기
# 2. export로 변환하여 Gradle에 전달

# 예시 (실제 실행 시):
cd /path/to/claude-flow
eval $(grep -v '^#' .env | xargs -I {} echo export {})
eval $(grep -v '^#' docker-compose/.env | xargs -I {} echo export {})
./gradlew :claude-flow-app:bootRun > /tmp/claude-flow-server.log 2>&1 &
```

**stop**
```bash
pkill -f "claude-flow-app:bootRun"
```

**restart**
stop 후 start 실행

**logs**
```bash
tail -50 /tmp/claude-flow.log
```

### 3. 주의사항
- 환경 변수는 반드시 Gradle 명령어와 같은 줄에 인라인으로 전달해야 함
- `source .env` 방식은 Gradle 자식 프로세스로 전달되지 않음
- 서버 시작 후 약 20초 대기 필요
