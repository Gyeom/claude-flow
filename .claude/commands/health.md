---
description: Check Claude Flow system health status
---

# Claude Flow Health Check

Check the health of all Claude Flow components.

```bash
echo "=== Claude Flow Health Check ==="
echo ""

# API Server
echo -n "API Server (8080): "
if curl -s --max-time 5 "http://localhost:8080/api/v1/health" > /dev/null 2>&1; then
  echo "✓ Healthy"
else
  echo "✗ Unreachable"
fi

# n8n Workflow Engine
echo -n "n8n Engine (5678): "
if curl -s --max-time 5 "http://localhost:5678/healthz" > /dev/null 2>&1; then
  echo "✓ Healthy"
else
  echo "✗ Unreachable"
fi

# Dashboard (if running)
echo -n "Dashboard (5173):  "
if curl -s --max-time 2 "http://localhost:5173" > /dev/null 2>&1; then
  echo "✓ Running"
else
  echo "- Not running (optional)"
fi

# Claude CLI
echo -n "Claude CLI:        "
if command -v claude &> /dev/null; then
  echo "✓ Installed ($(claude --version 2>/dev/null | head -1 || echo 'unknown version'))"
else
  echo "✗ Not installed"
fi

# Docker containers
echo ""
echo "=== Docker Containers ==="
docker ps --filter "name=claude-flow" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null || echo "Docker not available"

# Database
echo ""
echo "=== Database Stats ==="
PROJECT_ROOT="${CLAUDE_FLOW_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
DB_PATH="${CLAUDE_FLOW_DB:-$PROJECT_ROOT/data/claude-flow.db}"
if [ -f "$DB_PATH" ]; then
  echo "Executions: $(sqlite3 "$DB_PATH" 'SELECT COUNT(*) FROM executions' 2>/dev/null || echo 'N/A')"
  echo "Users: $(sqlite3 "$DB_PATH" 'SELECT COUNT(*) FROM user_contexts' 2>/dev/null || echo 'N/A')"
  echo "Agents: $(sqlite3 "$DB_PATH" 'SELECT COUNT(*) FROM agents' 2>/dev/null || echo 'N/A')"
else
  echo "Database not found at $DB_PATH"
fi
```

Report the overall system status and any issues detected.
