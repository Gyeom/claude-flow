---
description: Jira issue management via Claude Flow
argument-hint: <command> [issue-key|jql]
---

# Jira Integration Commands

Manage Jira issues directly from Claude Code CLI.

## Available Commands

```bash
COMMAND="${1:-}"
ARG="${2:-}"

API_BASE="http://localhost:8080/api/v1/plugins/jira"

case "$COMMAND" in
  # 이슈 상세 조회
  "issue"|"i")
    if [ -z "$ARG" ]; then
      echo "❌ Usage: /jira issue <issue-key>"
      echo "   Example: /jira issue PROJ-123"
      exit 1
    fi
    echo "🔍 Fetching issue: $ARG"
    RESULT=$(curl -s "$API_BASE/issues/$ARG")

    # Parse and display
    if echo "$RESULT" | grep -q '"success":true'; then
      echo ""
      echo "$RESULT" | python3 -c "
import json, sys
data = json.load(sys.stdin)['data']
print(f\"📋 {data['key']}: {data['summary']}\")
print(f\"   Status: {data['status']} | Priority: {data['priority']} | Type: {data['issuetype']}\")
print(f\"   Assignee: {data['assignee'] or 'Unassigned'}\")
print(f\"   Reporter: {data['reporter']}\")
if data.get('description'):
    print(f\"\\n📝 Description:\\n{data['description'][:500]}...\")
print(f\"\\n🔗 {data['url']}\")
"
    else
      echo "❌ Failed to fetch issue"
      echo "$RESULT"
    fi
    ;;

  # 내 이슈 목록
  "my"|"my-issues"|"mine")
    echo "📋 Fetching your assigned issues..."
    RESULT=$(curl -s "$API_BASE/my-issues")

    if echo "$RESULT" | grep -q '"success":true'; then
      echo "$RESULT" | python3 -c "
import json, sys
data = json.load(sys.stdin)
issues = data.get('data', [])
print(f\"\\n📌 Your Issues ({len(issues)} found):\\n\")
for i, issue in enumerate(issues[:15], 1):
    status_emoji = {'Done': '✅', 'In Progress': '🔄', 'To Do': '📝', 'In Review': '👀'}.get(issue['status'], '⚪')
    print(f\"{i:2}. [{issue['key']}] {issue['summary'][:60]}\")
    print(f\"    {status_emoji} {issue['status']} | {issue.get('priority', '-')} | {issue.get('type', '-')}\")
"
    else
      echo "❌ Failed to fetch issues"
    fi
    ;;

  # 스프린트 이슈
  "sprint"|"s")
    BOARD_ID="${ARG:-}"
    echo "🏃 Fetching current sprint issues..."

    if [ -n "$BOARD_ID" ]; then
      RESULT=$(curl -s "$API_BASE/sprint?boardId=$BOARD_ID")
    else
      RESULT=$(curl -s "$API_BASE/sprint")
    fi

    if echo "$RESULT" | grep -q '"success":true'; then
      echo "$RESULT" | python3 -c "
import json, sys
data = json.load(sys.stdin)
issues = data.get('data', [])
print(f\"\\n🏃 Sprint Issues ({len(issues)} found):\\n\")

# Group by status
by_status = {}
for issue in issues:
    status = issue.get('status', 'Unknown')
    if status not in by_status:
        by_status[status] = []
    by_status[status].append(issue)

status_order = ['To Do', 'In Progress', 'In Review', 'Done']
for status in status_order + [s for s in by_status if s not in status_order]:
    if status in by_status:
        emoji = {'Done': '✅', 'In Progress': '🔄', 'To Do': '📝', 'In Review': '👀'}.get(status, '⚪')
        print(f\"{emoji} {status} ({len(by_status[status])}):\")
        for issue in by_status[status][:5]:
            assignee = issue.get('assignee', 'Unassigned') or 'Unassigned'
            print(f\"   [{issue['key']}] {issue['summary'][:50]} (@{assignee})\")
        if len(by_status[status]) > 5:
            print(f\"   ... and {len(by_status[status])-5} more\")
        print()
"
    else
      echo "❌ Failed to fetch sprint issues"
    fi
    ;;

  # JQL 검색
  "search"|"q")
    if [ -z "$ARG" ]; then
      echo "❌ Usage: /jira search <jql-query>"
      echo "   Example: /jira search 'project=PROJ AND status=\"In Progress\"'"
      exit 1
    fi

    # Combine remaining args for JQL
    shift
    JQL="$*"
    echo "🔍 Searching: $JQL"

    ENCODED_JQL=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$JQL'))")
    RESULT=$(curl -s "$API_BASE/search?jql=$ENCODED_JQL")

    if echo "$RESULT" | grep -q '"success":true'; then
      echo "$RESULT" | python3 -c "
import json, sys
data = json.load(sys.stdin)
issues = data.get('data', [])
msg = data.get('message', '')
print(f\"\\n{msg}\\n\")
for issue in issues[:20]:
    status_emoji = {'Done': '✅', 'In Progress': '🔄', 'To Do': '📝', 'In Review': '👀'}.get(issue['status'], '⚪')
    print(f\"[{issue['key']}] {issue['summary'][:55]}\")
    print(f\"  {status_emoji} {issue['status']} | {issue.get('assignee', 'Unassigned')}\")
"
    else
      echo "❌ Search failed"
      echo "$RESULT"
    fi
    ;;

  # 상태 변경
  "move"|"transition"|"t")
    ISSUE_KEY="$ARG"
    STATUS="${3:-}"

    if [ -z "$ISSUE_KEY" ] || [ -z "$STATUS" ]; then
      echo "❌ Usage: /jira move <issue-key> <status>"
      echo "   Example: /jira move PROJ-123 Done"
      echo "   Statuses: To Do, In Progress, In Review, Done"
      exit 1
    fi

    echo "🔄 Transitioning $ISSUE_KEY to $STATUS..."
    RESULT=$(curl -s -X POST "$API_BASE/issues/$ISSUE_KEY/transition" \
      -H "Content-Type: application/json" \
      -d "{\"status\": \"$STATUS\"}")

    if echo "$RESULT" | grep -q '"success":true'; then
      echo "✅ $ISSUE_KEY moved to $STATUS"
    else
      echo "❌ Transition failed"
      echo "$RESULT" | python3 -c "
import json, sys
data = json.load(sys.stdin)
print(data.get('error', 'Unknown error'))
"
    fi
    ;;

  # 이슈 분석 (Claude 연동)
  "analyze"|"a")
    if [ -z "$ARG" ]; then
      echo "❌ Usage: /jira analyze <issue-key>"
      echo "   Analyze issue and suggest implementation approach"
      exit 1
    fi

    echo "🤖 Analyzing issue $ARG with Claude..."

    # Fetch issue first
    ISSUE=$(curl -s "$API_BASE/issues/$ARG")

    if ! echo "$ISSUE" | grep -q '"success":true'; then
      echo "❌ Failed to fetch issue $ARG"
      exit 1
    fi

    # Extract issue data and send to Claude
    PROMPT=$(echo "$ISSUE" | python3 -c "
import json, sys
data = json.load(sys.stdin)['data']
print(f'''Jira 이슈를 분석하고 구현 방향을 제안해줘:

이슈: {data['key']} - {data['summary']}
타입: {data['issuetype']}
우선순위: {data['priority']}
설명: {data.get('description', 'No description')[:1000]}

다음을 포함해서 분석해줘:
1. 요구사항 분석
2. 구현 접근 방식 제안
3. 예상 작업 단계
4. 주의할 점이나 리스크
''')
")

    curl -s -X POST "http://localhost:8080/api/v1/execute" \
      -H "Content-Type: application/json" \
      -d "{
        \"prompt\": $(echo "$PROMPT" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),
        \"agentId\": \"general\",
        \"userId\": \"${CLAUDE_FLOW_USER_ID:-cli-user}\"
      }"
    ;;

  # 도움말
  "help"|"h"|"")
    echo "
🎫 Jira Commands

  /jira issue <key>      이슈 상세 조회
  /jira my               내 이슈 목록
  /jira sprint           현재 스프린트 이슈
  /jira search <jql>     JQL로 이슈 검색
  /jira move <key> <st>  이슈 상태 변경
  /jira analyze <key>    Claude로 이슈 분석

Examples:
  /jira issue PROJ-123
  /jira my
  /jira sprint
  /jira search 'project=PROJ AND assignee=currentUser()'
  /jira move PROJ-123 Done
  /jira analyze PROJ-456
"
    ;;

  *)
    # Check if it looks like an issue key
    if echo "$COMMAND" | grep -qE '^[A-Z]+-[0-9]+$'; then
      echo "🔍 Fetching issue: $COMMAND"
      curl -s "$API_BASE/issues/$COMMAND" | python3 -c "
import json, sys
data = json.load(sys.stdin)
if data.get('success'):
    d = data['data']
    print(f\"\\n📋 {d['key']}: {d['summary']}\")
    print(f\"   Status: {d['status']} | Priority: {d['priority']}\")
    print(f\"   Assignee: {d['assignee'] or 'Unassigned'}\")
    print(f\"   🔗 {d['url']}\")
else:
    print(f\"❌ {data.get('error', 'Issue not found')}\")
"
    else
      echo "❌ Unknown command: $COMMAND"
      echo "   Run '/jira help' for available commands"
    fi
    ;;
esac
```

## Quick Reference

| Command | Description |
|---------|-------------|
| `/jira PROJ-123` | Quick issue lookup |
| `/jira my` | My assigned issues |
| `/jira sprint` | Current sprint board |
| `/jira analyze PROJ-123` | AI-powered analysis |
