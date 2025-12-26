---
description: "Check all services status"
---

# Health Check

전체 서비스 상태를 한눈에 확인합니다.

## Instructions

```bash
echo "╔═══════════════════════════════════════════╗"
echo "║        Claude Flow Health Check           ║"
echo "╚═══════════════════════════════════════════╝"
echo ""

# Infrastructure
echo "📦 Infrastructure"
echo "─────────────────"

echo -n "  Qdrant (6333):  "
if curl -s --max-time 2 http://localhost:6333/collections >/dev/null 2>&1; then
  echo "✓ Running"
else
  echo "✗ Not running → /infra start"
fi

echo -n "  Ollama (11434): "
if curl -s --max-time 2 http://localhost:11434/api/tags >/dev/null 2>&1; then
  echo "✓ Running"
else
  echo "✗ Not running → /infra start"
fi

echo ""

# Application
echo "🚀 Application"
echo "─────────────────"

echo -n "  Backend (8080): "
if curl -s --max-time 2 http://localhost:8080/api/v1/health >/dev/null 2>&1; then
  echo "✓ Running"
else
  echo "✗ Not running → /app start"
fi

echo -n "  Dashboard:      "
if curl -s --max-time 2 http://localhost:3000/ >/dev/null 2>&1; then
  echo "✓ Running (port 3000)"
elif curl -s --max-time 2 http://localhost:5173/ >/dev/null 2>&1; then
  echo "✓ Running (port 5173)"
else
  echo "✗ Not running → /app start"
fi

echo ""

# Optional Services
echo "🔧 Optional"
echo "─────────────────"

echo -n "  n8n (5678):     "
if curl -s --max-time 2 http://localhost:5678/ >/dev/null 2>&1; then
  echo "✓ Running"
else
  echo "- Not running"
fi

echo -n "  Claude CLI:     "
if command -v claude &> /dev/null; then
  echo "✓ Installed"
else
  echo "✗ Not installed"
fi

echo ""

# Environment
echo "🔑 Environment"
echo "─────────────────"
PROJECT_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
ENV_FILE="$PROJECT_ROOT/docker-compose/.env"
if [ -f "$ENV_FILE" ]; then
  vars=$(grep -v "^#" "$ENV_FILE" | grep -c "=" 2>/dev/null || echo 0)
  echo "  .env: $vars vars loaded"
else
  echo "  .env: ✗ Not found"
fi

echo ""
echo "💡 Commands: /infra, /app, /health"
```

## Quick Commands

| 문제 | 해결 |
|------|------|
| Qdrant/Ollama not running | `/infra start` |
| Backend/Dashboard not running | `/app start` |
| Backend restart needed | `/app restart` |
| View backend logs | `/app logs` |
