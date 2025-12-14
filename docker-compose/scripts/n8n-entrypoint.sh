#!/bin/sh
set -e

SCRIPTS_DIR="/home/node/scripts"
MARKER_FILE="/home/node/.n8n/.setup_complete"

# 백그라운드에서 설정/동기화 실행
(
    echo "Waiting for n8n to start..."
    sleep 15

    if [ ! -f "$MARKER_FILE" ]; then
        echo "=== First startup: Running initial setup ==="
        node "$SCRIPTS_DIR/setup-n8n.mjs" 2>&1

        if [ $? -eq 0 ]; then
            touch "$MARKER_FILE"
            echo "Setup complete"
        else
            echo "Setup failed, will retry on next restart"
            exit 1
        fi
    else
        echo "=== Syncing workflows from JSON files ==="
        node "$SCRIPTS_DIR/sync-workflows.mjs" 2>&1
    fi
) &

exec n8n "$@"
