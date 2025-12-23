---
description: "Manage dev environment (backend + dashboard)"
allowed-arguments: ["start", "stop", "restart", "status"]
argument-hint: "<action: start|stop|restart|status>"
---

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
echo "=== Dashboard (port 5173) ==="
curl -s -o /dev/null -w "HTTP %{http_code}" http://localhost:5173/ 2>/dev/null || echo "Not running"

echo ""
echo "=== Qdrant (port 6333) ==="
curl -s http://localhost:6333/collections 2>/dev/null | head -c 100 || echo "Not running"

echo ""
echo "=== Ollama (port 11434) ==="
curl -s http://localhost:11434/api/tags 2>/dev/null | head -c 100 || echo "Not running"

echo ""
echo "=== n8n (port 5678) ==="
curl -s -o /dev/null -w "HTTP %{http_code}" http://localhost:5678/ 2>/dev/null || echo "Not running"
```

**start**
인프라, 백엔드, 대시보드를 순차적으로 시작합니다:

1. RAG 인프라 시작 (Qdrant + Ollama via docker-compose)
2. 환경 변수 로드 (docker-compose/.env)
3. 백엔드 서버 시작 (./gradlew :claude-flow-app:bootRun)
4. 백엔드 준비 대기 (health check)
5. 대시보드 시작 (npm run dev)

```bash
# 1. RAG 인프라 시작 (Qdrant + Ollama)
cd docker-compose && docker-compose up -d qdrant ollama && cd ..

# 2. Qdrant/Ollama 준비 대기
echo "Waiting for Qdrant..."
for i in {1..30}; do
  curl -s http://localhost:6333/collections && break
  sleep 2
done

echo "Waiting for Ollama..."
for i in {1..30}; do
  curl -s http://localhost:11434/api/tags && break
  sleep 2
done

# 3. 환경 변수 읽기
source docker-compose/.env

# 4. 백엔드 시작
SLACK_APP_TOKEN="$SLACK_APP_TOKEN" SLACK_BOT_TOKEN="$SLACK_BOT_TOKEN" \
QDRANT_URL="http://localhost:6333" OLLAMA_URL="http://localhost:11434" \
RAG_ENABLED="true" \
  ./gradlew :claude-flow-app:bootRun > /tmp/claude-flow.log 2>&1 &

# 5. 백엔드 대기
for i in {1..30}; do
  curl -s http://localhost:8080/api/v1/health && break
  sleep 2
done

# 6. 대시보드 시작
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

# RAG 인프라 중지 (선택적 - 데이터 유지)
# cd docker-compose && docker-compose stop qdrant ollama && cd ..
```

**restart**
stop 후 start를 실행합니다.

### 2. 서비스 URL

| 서비스 | URL | 설명 |
|--------|-----|------|
| Backend | http://localhost:8080 | REST API |
| Dashboard | http://localhost:5173 | React UI (Vite) |
| Qdrant | http://localhost:6333 | Vector DB (RAG) |
| Ollama | http://localhost:11434 | Embedding 서비스 |
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
- **RAG 기능 사용 시 Qdrant + Ollama 필요** (docker-compose로 자동 시작)
- n8n은 별도로 docker-compose로 실행 필요
- Qdrant/Ollama 컨테이너는 stop 시 중지되지 않음 (데이터 보존)
