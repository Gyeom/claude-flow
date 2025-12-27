---
description: "Start all services (infra + app)"
---

# Start All Services

인프라(Qdrant, Ollama, n8n)와 애플리케이션(Backend, Dashboard)을 한 번에 시작합니다.

## Instructions

```bash
echo "🚀 Starting Claude Flow..."
echo ""

# ==================== Infrastructure ====================
echo "=== Infrastructure ==="

# Qdrant + n8n (Docker)
echo -n "Starting Qdrant + n8n: "
cd docker-compose && docker compose up -d qdrant n8n 2>/dev/null && cd ..
echo "✓"

# Qdrant 대기
echo -n "  Waiting for Qdrant..."
for i in {1..30}; do
  if curl -s --max-time 2 http://localhost:6333/collections >/dev/null 2>&1; then
    echo " ✓"
    break
  fi
  sleep 2
done

# n8n 대기
echo -n "  Waiting for n8n..."
for i in {1..20}; do
  if curl -s --max-time 2 http://localhost:5678/ >/dev/null 2>&1; then
    echo " ✓"
    break
  fi
  sleep 2
done

# Ollama (brew - Metal GPU 지원)
echo -n "Starting Ollama: "
if curl -s --max-time 2 http://localhost:11434/api/tags >/dev/null 2>&1; then
  echo "already running ✓"
else
  brew services start ollama 2>/dev/null
  for i in {1..30}; do
    if curl -s --max-time 2 http://localhost:11434/api/tags >/dev/null 2>&1; then
      echo "started ✓"
      break
    fi
    sleep 2
  done
fi

echo ""

# ==================== Application ====================
echo "=== Application ==="

# Backend 시작
echo -n "Starting Backend: "
./gradlew :claude-flow-app:bootRun --no-configuration-cache > /tmp/claude-flow.log 2>&1 &
echo "launching..."

# Backend 대기
echo -n "  Waiting for Backend..."
for i in {1..30}; do
  if curl -s http://localhost:8080/api/v1/health >/dev/null 2>&1; then
    echo " ✓"
    break
  fi
  sleep 2
done

# Dashboard 시작
echo -n "Starting Dashboard: "
cd dashboard && npm run dev > /tmp/dashboard.log 2>&1 &
sleep 3
echo "✓"

echo ""
echo "========================================="
echo "✅ All services started!"
echo ""
echo "📦 Infrastructure:"
echo "   • Qdrant:    http://localhost:6333"
echo "   • Ollama:    http://localhost:11434"
echo "   • n8n:       http://localhost:5678"
echo ""
echo "🚀 Application:"
echo "   • Backend:   http://localhost:8080"
echo "   • Dashboard: http://localhost:3000"
echo ""
echo "💡 Logs: /app logs"
echo "💡 Stop: /stop"
```

## Related Commands

- `/stop` - 모든 서비스 중지
- `/health` - 전체 상태 확인
- `/infra` - 인프라만 관리
- `/app` - 앱만 관리
