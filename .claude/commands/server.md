---
description: "Manage Claude Flow backend server (start/stop/restart/status/logs)"
---

# Server Management Command

Claude Flow 백엔드 서버를 관리합니다.

## Arguments
- `$ARGUMENTS` - 액션: start, stop, restart, status, logs (기본: status)

## Instructions

### 1. 환경 변수 로드
docker-compose/.env 파일에서 환경 변수를 읽어서 사용하세요:
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
# 환경 변수를 인라인으로 전달해야 함
SLACK_APP_TOKEN="..." SLACK_BOT_TOKEN="..." WORKSPACE_PATH="..." ./gradlew :claude-flow-app:bootRun > /tmp/claude-flow.log 2>&1 &
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
