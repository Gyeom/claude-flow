---
description: Execute prompt with smart agent routing via Claude Flow API
argument-hint: <prompt>
---

# Claude Flow Execute

Execute a prompt through the Claude Flow smart routing system.

```bash
curl -s -X POST http://localhost:8080/api/v1/execute-with-routing \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "$ARGUMENTS",
    "userId": "'"${CLAUDE_FLOW_USER_ID:-cli-user}"'",
    "projectId": "'"${CLAUDE_FLOW_PROJECT_ID:-default}"'"
  }'
```

Analyze the response and provide:
1. Which agent was selected and why (routing method, confidence)
2. The execution result
3. Token usage and cost estimate
