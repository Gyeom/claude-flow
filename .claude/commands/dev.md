# Development Environment Command

Claude Flow 개발 환경(백엔드 + 대시보드)을 한번에 관리합니다.

## Arguments
- `$ARGUMENTS` - 액션: start, stop, status, restart (기본: status)

## Instructions

### 1. 액션별 동작

**status (기본)**
전체 서비스 상태를 확인합니다:

```bash
echo "=== Backend (port 8080) ==="
curl -s http://localhost:8080/api/v1/health 2>/dev/null || echo "Not running"

echo ""
echo "=== Dashboard (port 3000) ==="
curl -s -o /dev/null -w "HTTP %{http_code}" http://localhost:3000/ 2>/dev/null || echo "Not running"

echo ""
echo "=== n8n (port 5678) ==="
curl -s -o /dev/null -w "HTTP %{http_code}" http://localhost:5678/ 2>/dev/null || echo "Not running"
```

**start**
백엔드와 대시보드를 순차적으로 시작합니다:

1. 환경 변수 로드 (docker-compose/.env)
2. 백엔드 서버 시작 (./gradlew :claude-flow-app:bootRun)
3. 백엔드 준비 대기 (health check)
4. 대시보드 시작 (npm run dev)

```bash
# 1. 환경 변수 읽기
source docker-compose/.env

# 2. 백엔드 시작
SLACK_APP_TOKEN="$SLACK_APP_TOKEN" SLACK_BOT_TOKEN="$SLACK_BOT_TOKEN" \
  ./gradlew :claude-flow-app:bootRun > /tmp/claude-flow.log 2>&1 &

# 3. 백엔드 대기
for i in {1..30}; do
  curl -s http://localhost:8080/api/v1/health && break
  sleep 2
done

# 4. 대시보드 시작
cd dashboard && npm run dev &
```

**stop**
모든 서비스를 중지합니다:

```bash
# 대시보드 중지
pkill -f "vite" 2>/dev/null

# 백엔드 중지
pkill -f "claude-flow-app" 2>/dev/null
lsof -ti:8080 | xargs kill -9 2>/dev/null
```

**restart**
stop 후 start를 실행합니다.

### 2. 서비스 URL

| 서비스 | URL | 설명 |
|--------|-----|------|
| Backend | http://localhost:8080 | REST API |
| Dashboard | http://localhost:3000 | React UI |
| n8n | http://localhost:5678 | Workflow (선택) |

### 3. 로그 확인

```bash
# 백엔드 로그
tail -f /tmp/claude-flow.log

# 대시보드 로그
# (터미널에 직접 출력됨)
```

### 4. 주의사항
- 백엔드가 먼저 시작되어야 대시보드가 API를 사용할 수 있음
- Slack 연동을 위해 환경 변수 필요 (SLACK_APP_TOKEN, SLACK_BOT_TOKEN)
- n8n은 별도로 docker-compose로 실행 필요
