---
description: View or manage user context (rules, summary, history)
argument-hint: <user-id> [action: view|add-rule|clear]
---

# Claude Flow User Context Management

Manage user context including rules, conversation summary, and preferences.

```bash
USER_ID="${1:-${CLAUDE_FLOW_USER_ID:-unknown}}"
ACTION="${2:-view}"

case "$ACTION" in
  view)
    curl -s "http://localhost:8080/api/v1/users/$USER_ID/context"
    ;;
  add-rule)
    RULE="${3:-}"
    if [ -n "$RULE" ]; then
      curl -s -X POST "http://localhost:8080/api/v1/users/$USER_ID/rules" \
        -H "Content-Type: application/json" \
        -d "{\"rule\": \"$RULE\"}"
    else
      echo "Usage: /user-context <user-id> add-rule <rule-text>"
    fi
    ;;
  clear)
    curl -s -X DELETE "http://localhost:8080/api/v1/users/$USER_ID/context/lock?lock_id=cli"
    ;;
esac
```

## Context Fields:
- **rules**: User-specific instructions applied to every conversation
- **summary**: AI-generated conversation summary
- **recentConversations**: Last N interactions
- **needsSummary**: Whether context needs summarization
