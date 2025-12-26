---
description: "Manage infrastructure (Qdrant, Ollama)"
allowed-arguments: ["start", "stop", "status"]
argument-hint: "<action: start|stop|status>"
---

# Infrastructure Command

RAG 인프라(Qdrant, Ollama)를 관리합니다. 거의 재시작할 일 없음.

## Arguments
- `$ARGUMENTS` - 액션: start, stop, status (기본: status)

## Instructions

### status (기본)
```bash
echo "=== Qdrant (6333) ==="
if curl -s --max-time 2 http://localhost:6333/collections >/dev/null 2>&1; then
  collections=$(curl -s --max-time 2 http://localhost:6333/collections | jq -r '.result.collections | length')
  echo "✓ Running ($collections collections)"
else
  echo "✗ Not running"
fi

echo ""
echo "=== Ollama (11434) ==="
if curl -s --max-time 2 http://localhost:11434/api/tags >/dev/null 2>&1; then
  models=$(curl -s --max-time 2 http://localhost:11434/api/tags | jq -r '.models | length')
  echo "✓ Running ($models models)"
else
  echo "✗ Not running"
fi

echo ""
echo "=== n8n (5678) ==="
if curl -s --max-time 2 http://localhost:5678/ >/dev/null 2>&1; then
  echo "✓ Running"
else
  echo "- Not running (optional)"
fi
```

### start
```bash
echo "Starting infrastructure..."

# Qdrant (Docker)
echo -n "Qdrant: "
if curl -s http://localhost:6333/collections >/dev/null 2>&1; then
  echo "already running ✓"
else
  cd docker-compose && docker-compose up -d qdrant && cd ..
  for i in {1..30}; do
    if curl -s http://localhost:6333/collections >/dev/null 2>&1; then
      echo "started ✓"
      break
    fi
    sleep 2
  done
fi

# Ollama (brew - Metal GPU 지원)
echo -n "Ollama: "
if curl -s http://localhost:11434/api/tags >/dev/null 2>&1; then
  echo "already running ✓"
else
  brew services start ollama
  for i in {1..30}; do
    if curl -s http://localhost:11434/api/tags >/dev/null 2>&1; then
      echo "started ✓"
      break
    fi
    sleep 2
  done
fi

echo ""
echo "✓ Qdrant:  http://localhost:6333"
echo "✓ Ollama:  http://localhost:11434"
```

### stop
```bash
echo "Stopping infrastructure..."

# Qdrant - graceful stop (데이터 손상 방지)
echo -n "Qdrant: "
cd docker-compose && docker-compose stop qdrant && cd ..
echo "stopped ✓"

# Ollama
echo -n "Ollama: "
brew services stop ollama
echo "stopped ✓"

echo ""
echo "⚠️  데이터는 보존됨. 완전 삭제: docker-compose down -v"
```

## Service Details

| 서비스 | URL | 실행 방식 | 용도 |
|--------|-----|----------|------|
| Qdrant | http://localhost:6333 | Docker | Vector DB |
| Ollama | http://localhost:11434 | brew services | Embedding (Metal GPU) |
| n8n | http://localhost:5678 | Docker | Workflow (별도 실행) |

## n8n 별도 실행

n8n은 워크플로우 엔진으로 필요 시에만 실행:

```bash
cd docker-compose && docker-compose up -d n8n
```
