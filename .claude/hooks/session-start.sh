#!/bin/bash
# Claude Flow - Session Initialization Hook
# SessionStart hook for loading user preferences and setting up environment

set -e

# Read hook input from stdin
INPUT=$(cat)
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // empty')

# ============================================
# Auto-start Claude Flow backend server
# ============================================
check_and_start_server() {
    local PROJECT_ROOT="${CLAUDE_FLOW_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
    local ENV_FILE="$PROJECT_ROOT/docker-compose/.env"

    # Check if server is already running
    if curl -s http://localhost:8080/api/v1/health > /dev/null 2>&1; then
        echo "[Server] Already running" >&2
        return 0
    fi

    # Load environment variables from .env
    if [ -f "$ENV_FILE" ]; then
        export SLACK_APP_TOKEN=$(grep "^SLACK_APP_TOKEN=" "$ENV_FILE" | cut -d'=' -f2-)
        export SLACK_BOT_TOKEN=$(grep "^SLACK_BOT_TOKEN=" "$ENV_FILE" | cut -d'=' -f2-)
        export WORKSPACE_PATH="$PROJECT_ROOT/data"

        echo "[Server] Starting Claude Flow backend..." >&2
        cd "$PROJECT_ROOT"

        SLACK_APP_TOKEN="$SLACK_APP_TOKEN" \
        SLACK_BOT_TOKEN="$SLACK_BOT_TOKEN" \
        WORKSPACE_PATH="$WORKSPACE_PATH" \
        ./gradlew :claude-flow-app:bootRun > /tmp/claude-flow.log 2>&1 &

        echo "[Server] Started (PID: $!, logs: /tmp/claude-flow.log)" >&2
    else
        echo "[Server] Warning: .env file not found at $ENV_FILE" >&2
    fi
}

# Start server in background (don't block session start)
check_and_start_server &

# Configuration
PROJECT_ROOT="${CLAUDE_FLOW_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
DB_PATH="${CLAUDE_FLOW_DB:-$PROJECT_ROOT/data/claude-flow.db}"
USER_ID="${CLAUDE_FLOW_USER_ID:-unknown}"

# Log session start
echo "[$(date -Iseconds)] SESSION_START: session=$SESSION_ID user=$USER_ID" >> /tmp/claude-flow-audit.log

# Remind to read project context
echo "" >&2
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
echo "📚 Claude Flow 프로젝트 컨텍스트" >&2
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
echo "" >&2
echo "프로젝트 문서: CLAUDE.md" >&2
echo "" >&2
echo "주요 명령어:" >&2
echo "  /health      - 시스템 상태 확인" >&2
echo "  /update-docs - 문서 업데이트" >&2
echo "  /add-test    - 테스트 추가" >&2
echo "  /new-feature - 새 기능 구현" >&2
echo "  /refactor    - 코드 리팩토링" >&2
echo "" >&2
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2

# Load user preferences if database exists
if [ -f "$DB_PATH" ]; then
    # Get user's preferred language
    LANG=$(sqlite3 "$DB_PATH" "SELECT preferred_language FROM user_contexts WHERE user_id = '$USER_ID'" 2>/dev/null || echo "ko")

    # Get total interactions count
    INTERACTIONS=$(sqlite3 "$DB_PATH" "SELECT total_interactions FROM user_contexts WHERE user_id = '$USER_ID'" 2>/dev/null || echo "0")

    # Output context info for Claude
    if [ "$INTERACTIONS" != "0" ] && [ -n "$INTERACTIONS" ]; then
        echo "[Session Context]" >&2
        echo "User: $USER_ID (interactions: $INTERACTIONS)" >&2
        echo "Language: $LANG" >&2
    fi

    # Update last_seen timestamp
    sqlite3 "$DB_PATH" "UPDATE user_contexts SET last_seen = datetime('now') WHERE user_id = '$USER_ID'" 2>/dev/null || true
fi

# Create session record for tracking
if [ -f "$DB_PATH" ] && [ -n "$SESSION_ID" ]; then
    sqlite3 "$DB_PATH" "
        CREATE TABLE IF NOT EXISTS sessions (
            session_id TEXT PRIMARY KEY,
            user_id TEXT,
            started_at TEXT,
            ended_at TEXT,
            total_turns INTEGER DEFAULT 0,
            total_tokens INTEGER DEFAULT 0
        );
        INSERT OR REPLACE INTO sessions (session_id, user_id, started_at)
        VALUES ('$SESSION_ID', '$USER_ID', datetime('now'));
    " 2>/dev/null || true
fi

exit 0
