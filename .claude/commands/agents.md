---
description: List and manage Claude Flow agents
argument-hint: [action: list|get|test] [agent-id]
---

# Claude Flow Agent Management

View and test available agents in the routing system.

```bash
ACTION="${1:-list}"
AGENT_ID="${2:-}"

case "$ACTION" in
  list)
    curl -s "http://localhost:8080/api/v1/agents" | jq '.[] | {id, name, priority, keywords, enabled}'
    ;;
  get)
    if [ -n "$AGENT_ID" ]; then
      curl -s "http://localhost:8080/api/v1/agents/$AGENT_ID"
    else
      echo "Usage: /agents get <agent-id>"
    fi
    ;;
  test)
    # Test routing for a sample prompt
    PROMPT="${3:-Hello, which agent handles this?}"
    curl -s -X POST "http://localhost:8080/api/v1/route" \
      -H "Content-Type: application/json" \
      -d "{\"prompt\": \"$PROMPT\"}"
    ;;
esac
```

## Agent Properties:
- **id**: Unique identifier
- **name**: Display name
- **keywords**: Trigger keywords (supports regex with /pattern/)
- **priority**: 0-1000, higher = checked first
- **systemPrompt**: Agent's base instructions
- **allowedTools**: Permitted Claude tools
