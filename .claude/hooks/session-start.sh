#!/bin/bash
# Claude Flow - Session Initialization Hook
# SessionStart hook for loading user preferences and setting up environment

set -e

# Read hook input from stdin
INPUT=$(cat)
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // empty')

# Configuration
DB_PATH="${CLAUDE_FLOW_DB:-/Users/a13801/42dot/claude-flow/data/claude-flow.db}"
USER_ID="${CLAUDE_FLOW_USER_ID:-unknown}"

# Log session start
echo "[$(date -Iseconds)] SESSION_START: session=$SESSION_ID user=$USER_ID" >> /tmp/claude-flow-audit.log

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
