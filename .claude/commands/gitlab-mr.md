---
description: Review GitLab Merge Request with Claude Flow
argument-hint: <project> <mr-number>
---

# GitLab MR Review via Claude Flow

Trigger a code review for a GitLab Merge Request.

```bash
PROJECT="${1:-}"
MR_NUMBER="${2:-}"

if [ -z "$PROJECT" ] || [ -z "$MR_NUMBER" ]; then
  echo "Usage: /gitlab-mr <project-name> <mr-number>"
  echo "Example: /gitlab-mr authorization-server 42"
  exit 1
fi

# Trigger review via Claude Flow API
curl -s -X POST "http://localhost:8080/api/v1/chat/execute" \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [{"role": "user", "content": "'"$PROJECT"' !'"$MR_NUMBER"' 리뷰해줘"}],
    "agentId": "code-reviewer",
    "userId": "'"${CLAUDE_FLOW_USER_ID:-cli-user}"'",
    "source": "cli"
  }'
```

## Review Output:
1. **Summary**: Overall assessment of the MR
2. **Issues Found**: Critical, Major, Minor categorization
3. **Security Concerns**: Potential vulnerabilities
4. **Suggestions**: Code improvement recommendations
5. **Approval Status**: Approved / Changes Requested
