#!/bin/bash
# Claude Flow - Context Enrichment Hook
# UserPromptSubmit hook for injecting user context into prompts

set -e

# Read hook input from stdin
INPUT=$(cat)

# jq가 없으면 gracefully skip
if command -v jq &> /dev/null; then
    PROMPT=$(echo "$INPUT" | jq -r '.prompt // empty')
else
    PROMPT=""
fi

# Get user context from environment or database
USER_ID="${CLAUDE_FLOW_USER_ID:-unknown}"
PROJECT_ID="${CLAUDE_FLOW_PROJECT_ID:-default}"
# PROJECT_ROOT를 환경변수 또는 git root로 설정
PROJECT_ROOT="${CLAUDE_FLOW_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
DB_PATH="${CLAUDE_FLOW_DB:-$PROJECT_ROOT/data/claude-flow.db}"

# Function to get user rules from database
get_user_rules() {
    if [ -f "$DB_PATH" ]; then
        sqlite3 "$DB_PATH" "SELECT rule FROM user_rules WHERE user_id = '$USER_ID' LIMIT 10" 2>/dev/null || echo ""
    fi
}

# Function to get user summary from database
get_user_summary() {
    if [ -f "$DB_PATH" ]; then
        sqlite3 "$DB_PATH" "SELECT summary FROM user_contexts WHERE user_id = '$USER_ID'" 2>/dev/null || echo ""
    fi
}

# Build context block
RULES=$(get_user_rules)
SUMMARY=$(get_user_summary)

if [ -n "$RULES" ] || [ -n "$SUMMARY" ]; then
    echo "---" >&2
    echo "[User Context for $USER_ID]" >&2

    if [ -n "$RULES" ]; then
        echo "Rules:" >&2
        echo "$RULES" | while read -r rule; do
            echo "  - $rule" >&2
        done
    fi

    if [ -n "$SUMMARY" ]; then
        echo "Previous Summary: $SUMMARY" >&2
    fi

    echo "---" >&2
fi

# Log prompt submission
echo "[$(date -Iseconds)] PROMPT_SUBMIT: user=$USER_ID project=$PROJECT_ID len=${#PROMPT}" >> /tmp/claude-flow-audit.log

exit 0
