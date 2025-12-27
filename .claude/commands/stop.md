---
description: "Stop all services (infra + app)"
---

# Stop All Services

모든 서비스를 중지합니다. 데이터는 보존됩니다.

## Instructions

```bash
echo "🛑 Stopping Claude Flow..."
echo ""

# ==================== Application ====================
echo "=== Application ==="

# Dashboard 중지
echo -n "Dashboard: "
pkill -f "vite" 2>/dev/null && echo "stopped ✓" || echo "not running"

# Backend 중지
echo -n "Backend: "
pkill -f "claude-flow-app" 2>/dev/null && echo "stopped ✓" || echo "not running"
lsof -ti:8080 | xargs kill -9 2>/dev/null

echo ""

# ==================== Infrastructure ====================
echo "=== Infrastructure ==="

# Qdrant + n8n (Docker)
echo -n "Qdrant + n8n: "
cd docker-compose && docker compose stop qdrant n8n 2>/dev/null && cd ..
echo "stopped ✓"

# Ollama
echo -n "Ollama: "
brew services stop ollama 2>/dev/null
echo "stopped ✓"

echo ""
echo "========================================="
echo "✅ All services stopped!"
echo ""
echo "⚠️  데이터는 보존됨"
echo "💡 완전 삭제: cd docker-compose && docker compose down -v"
```

## Related Commands

- `/start` - 모든 서비스 시작
- `/health` - 전체 상태 확인
