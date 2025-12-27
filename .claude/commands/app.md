---
description: "Manage application (backend + dashboard)"
allowed-arguments: ["start", "stop", "restart", "logs", "status"]
argument-hint: "<action: start|stop|restart|logs|status>"
---

# Application Command

Backend + Dashboard를 관리합니다. (인프라는 `/infra` 참조)

## Arguments
- `$ARGUMENTS` - 액션: start, stop, restart, logs, status (기본: status)

## Instructions

### status (기본)
```bash
echo "=== Backend (8080) ==="
if curl -s --max-time 2 http://localhost:8080/api/v1/health >/dev/null 2>&1; then
  curl -s http://localhost:8080/api/v1/health
  echo ""
else
  echo "✗ Not running"
fi

echo ""
echo "=== Dashboard (3000) ==="
if curl -s --max-time 2 http://localhost:3000/ >/dev/null 2>&1; then
  echo "✓ Running"
else
  echo "✗ Not running"
fi
```

### start
```bash
# 인프라 확인
if ! curl -s http://localhost:6333/collections >/dev/null 2>&1; then
  echo "⚠️  Qdrant not running. Run '/infra start' first."
  exit 1
fi

# Backend 시작 (Gradle이 docker-compose/.env 자동 로드)
echo "Starting backend..."
./gradlew :claude-flow-app:bootRun --no-configuration-cache > /tmp/claude-flow.log 2>&1 &

# Backend 대기
echo -n "Waiting for backend"
for i in {1..30}; do
  if curl -s http://localhost:8080/api/v1/health >/dev/null 2>&1; then
    echo " ✓"
    break
  fi
  echo -n "."
  sleep 2
done

# Dashboard 시작
echo "Starting dashboard..."
cd dashboard && npm run dev > /tmp/dashboard.log 2>&1 &
sleep 3

echo ""
echo "✓ Backend:   http://localhost:8080"
echo "✓ Dashboard: http://localhost:3000"
```

### stop
```bash
echo "Stopping services..."

# Dashboard 중지
pkill -f "vite" 2>/dev/null && echo "✓ Dashboard stopped" || echo "Dashboard not running"

# Backend 중지
pkill -f "claude-flow-app" 2>/dev/null && echo "✓ Backend stopped" || echo "Backend not running"
lsof -ti:8080 | xargs kill -9 2>/dev/null
```

### restart
```bash
# Stop
pkill -f "vite" 2>/dev/null
pkill -f "claude-flow-app" 2>/dev/null
lsof -ti:8080 | xargs kill -9 2>/dev/null
sleep 2
echo "✓ Services stopped"

# Start backend
echo "Starting backend..."
./gradlew :claude-flow-app:bootRun --no-configuration-cache > /tmp/claude-flow.log 2>&1 &

echo -n "Waiting for backend"
for i in {1..30}; do
  if curl -s http://localhost:8080/api/v1/health >/dev/null 2>&1; then
    echo " ✓"
    break
  fi
  echo -n "."
  sleep 2
done

# Start dashboard
echo "Starting dashboard..."
cd dashboard && npm run dev > /tmp/dashboard.log 2>&1 &
sleep 3

echo ""
echo "✓ Backend:   http://localhost:8080"
echo "✓ Dashboard: http://localhost:3000"
```

### logs
```bash
# Backend 로그 확인
echo "=== Backend Logs (tail -50) ==="
tail -50 /tmp/claude-flow.log 2>/dev/null || echo "No logs found"

echo ""
echo "💡 실시간 로그: tail -f /tmp/claude-flow.log"
```

## Service URLs

| 서비스 | URL | 로그 |
|--------|-----|------|
| Backend | http://localhost:8080 | /tmp/claude-flow.log |
| Dashboard | http://localhost:3000 | /tmp/dashboard.log |
