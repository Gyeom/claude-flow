#!/usr/bin/env python3
"""
Claude Flow - Operation Logging Hook
PostToolUse hook for logging all tool executions for analytics
"""

import json
import sys
import os
import sqlite3
from datetime import datetime
from pathlib import Path

# Database path
DB_PATH = os.environ.get(
    'CLAUDE_FLOW_DB',
    str(Path(__file__).parent.parent.parent / 'data' / 'claude-flow.db')
)

def init_db():
    """Initialize audit log table if not exists"""
    conn = sqlite3.connect(DB_PATH)
    conn.execute('''
        CREATE TABLE IF NOT EXISTS tool_audit_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp TEXT NOT NULL,
            session_id TEXT,
            tool_name TEXT NOT NULL,
            tool_input TEXT,
            tool_output TEXT,
            duration_ms INTEGER,
            success INTEGER,
            error TEXT,
            user_id TEXT,
            project_id TEXT
        )
    ''')
    conn.execute('''
        CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON tool_audit_log(timestamp)
    ''')
    conn.execute('''
        CREATE INDEX IF NOT EXISTS idx_audit_tool ON tool_audit_log(tool_name)
    ''')
    conn.commit()
    return conn

def log_operation(data: dict):
    """Log tool operation to database"""
    try:
        conn = init_db()

        tool_name = data.get('tool_name', 'unknown')
        tool_input = json.dumps(data.get('tool_input', {}), ensure_ascii=False)
        tool_output = data.get('tool_output', '')

        # Truncate large outputs
        if len(tool_output) > 10000:
            tool_output = tool_output[:10000] + '... [truncated]'

        conn.execute('''
            INSERT INTO tool_audit_log
            (timestamp, session_id, tool_name, tool_input, tool_output, success, user_id, project_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ''', (
            datetime.utcnow().isoformat(),
            data.get('session_id'),
            tool_name,
            tool_input,
            tool_output,
            1 if not data.get('error') else 0,
            os.environ.get('CLAUDE_FLOW_USER_ID'),
            os.environ.get('CLAUDE_FLOW_PROJECT_ID')
        ))
        conn.commit()
        conn.close()

        # Print summary for Claude to see
        print(f"[AUDIT] {tool_name} logged", file=sys.stderr)

    except Exception as e:
        print(f"[AUDIT ERROR] Failed to log: {e}", file=sys.stderr)

def main():
    try:
        # Read hook input from stdin
        input_data = sys.stdin.read()
        if not input_data:
            return

        data = json.loads(input_data)
        log_operation(data)

    except json.JSONDecodeError as e:
        print(f"[AUDIT] Invalid JSON input: {e}", file=sys.stderr)
    except Exception as e:
        print(f"[AUDIT] Error: {e}", file=sys.stderr)

if __name__ == '__main__':
    main()
