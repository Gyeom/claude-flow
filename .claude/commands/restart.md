---
description: "Restart all services (stop + start)"
---

# Restart All Services

모든 서비스를 재시작합니다.

## Instructions

```bash
echo "🔄 Restarting Claude Flow..."
echo ""

# ==================== Stop ====================
echo "=== Stopping services ==="

# Dashboard 중지
echo -n "Dashboard: "
pkill -f "vite" 2>/dev/null && echo "stopped ✓" || echo "not running"

# Backend 중지
echo -n "Backend: "
pkill -f "claude-flow-app" 2>/dev/null && echo "stopped ✓" || echo "not running"
lsof -ti:8080 | xargs kill -9 2>/dev/null

# Docker 서비스 재시작
echo -n "Qdrant + n8n: "
cd docker-compose && docker compose restart qdrant n8n 2>/dev/null && cd ..
echo "restarting..."

# Ollama 재시작
echo -n "Ollama: "
brew services restart ollama 2>/dev/null
echo "restarting..."

sleep 3

# ==================== Start ====================
echo ""
echo "=== Starting services ==="

# Qdrant 대기
echo -n "Waiting for Qdrant..."
for i in {1..30}; do
  if curl -s --max-time 2 http://localhost:6333/collections >/dev/null 2>&1; then
    echo " ✓"
    break
  fi
  sleep 2
done

# n8n 대기
echo -n "Waiting for n8n..."
for i in {1..20}; do
  if curl -s --max-time 2 http://localhost:5678/ >/dev/null 2>&1; then
    echo " ✓"
    break
  fi
  sleep 2
done

# Ollama 대기
echo -n "Waiting for Ollama..."
for i in {1..30}; do
  if curl -s --max-time 2 http://localhost:11434/api/tags >/dev/null 2>&1; then
    echo " ✓"
    break
  fi
  sleep 2
done

# Backend 시작
echo -n "Starting Backend: "
./gradlew :claude-flow-app:bootRun --no-configuration-cache > /tmp/claude-flow.log 2>&1 &
echo "launching..."

echo -n "Waiting for Backend..."
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
echo "✅ All services restarted!"
echo ""
echo "📦 Infrastructure:"
echo "   • Qdrant:    http://localhost:6333"
echo "   • Ollama:    http://localhost:11434"
echo "   • n8n:       http://localhost:5678"
echo ""
echo "🚀 Application:"
echo "   • Backend:   http://localhost:8080"
echo "   • Dashboard: http://localhost:3000"
```

## Related Commands

- `/start` - 모든 서비스 시작
- `/stop` - 모든 서비스 중지
- `/health` - 전체 상태 확인
