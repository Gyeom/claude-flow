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

1. RAG 인프라 시작 (Qdrant via Docker, Ollama via brew - Metal GPU 지원)
2. 환경 변수 로드 (.env + docker-compose/.env)
3. 백엔드 서버 시작 (./gradlew :claude-flow-app:bootRun)
4. 백엔드 준비 대기 (health check)
5. 대시보드 시작 (npm run dev)

```bash
# 1. Qdrant 시작 (Docker)
cd docker-compose && docker-compose up -d qdrant && cd ..

# 2. Ollama 시작 (brew - Metal GPU 지원, Docker보다 240배 빠름)
brew services start ollama

# 3. Qdrant/Ollama 준비 대기
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

# 4. 환경 변수 읽기 (루트 .env 우선, docker-compose/.env 폴백)
[ -f .env ] && export $(grep -v '^#' .env | xargs)
[ -f docker-compose/.env ] && source docker-compose/.env

# 5. 백엔드 시작
SLACK_APP_TOKEN="$SLACK_APP_TOKEN" SLACK_BOT_TOKEN="$SLACK_BOT_TOKEN" \
FIGMA_ACCESS_TOKEN="$FIGMA_ACCESS_TOKEN" \
QDRANT_URL="http://localhost:6333" OLLAMA_URL="http://localhost:11434" \
RAG_ENABLED="true" \
  ./gradlew :claude-flow-app:bootRun > /tmp/claude-flow.log 2>&1 &

# 6. 백엔드 대기
for i in {1..30}; do
  curl -s http://localhost:8080/api/v1/health && break
  sleep 2
done

# 7. 대시보드 시작
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
# brew services stop ollama
# cd docker-compose && docker-compose stop qdrant && cd ..
```

**restart**
stop 후 start를 실행합니다:

```bash
# 1. 서비스 중지
pkill -f "vite" 2>/dev/null
pkill -f "claude-flow-app" 2>/dev/null
lsof -ti:8080 | xargs kill -9 2>/dev/null
sleep 2

# 2. Qdrant 시작 (Docker)
cd docker-compose && docker-compose up -d qdrant && cd ..

# 3. Ollama 재시작 (brew - Metal GPU)
brew services restart ollama

# 4. 인프라 준비 대기
echo "Waiting for Qdrant..."
for i in {1..30}; do curl -s http://localhost:6333/collections && break; sleep 2; done

echo "Waiting for Ollama..."
for i in {1..30}; do curl -s http://localhost:11434/api/tags && break; sleep 2; done

# 5. 환경 변수 로드
[ -f .env ] && export $(grep -v '^#' .env | xargs)
[ -f docker-compose/.env ] && source docker-compose/.env

# 6. 백엔드 시작
SLACK_APP_TOKEN="$SLACK_APP_TOKEN" SLACK_BOT_TOKEN="$SLACK_BOT_TOKEN" \
FIGMA_ACCESS_TOKEN="$FIGMA_ACCESS_TOKEN" \
QDRANT_URL="http://localhost:6333" OLLAMA_URL="http://localhost:11434" \
RAG_ENABLED="true" \
  ./gradlew :claude-flow-app:bootRun > /tmp/claude-flow.log 2>&1 &

# 7. 백엔드 대기
for i in {1..30}; do curl -s http://localhost:8080/api/v1/health && break; sleep 2; done

# 8. 대시보드 시작
cd dashboard && npm run dev &
```

### 2. 서비스 URL

| 서비스 | URL | 설명 | 실행 방법 |
|--------|-----|------|----------|
| Backend | http://localhost:8080 | REST API | Gradle |
| Dashboard | http://localhost:5173 | React UI (Vite) | npm |
| Qdrant | http://localhost:6333 | Vector DB (RAG) | Docker |
| Ollama | http://localhost:11434 | Embedding (Metal GPU) | brew services |
| n8n | http://localhost:5678 | Workflow (선택) | Docker |

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
- **RAG 기능 사용 시 Qdrant + Ollama 필요**
  - Qdrant: Docker (docker-compose)
  - Ollama: brew services (Metal GPU 지원, Docker보다 240배 빠름)
- n8n은 별도로 docker-compose로 실행 필요
- Qdrant/Ollama는 stop 시 중지되지 않음 (데이터 보존)
- Figma 연동 시 `.env` 파일에 `FIGMA_ACCESS_TOKEN` 설정 필요
