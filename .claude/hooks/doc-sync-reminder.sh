#!/bin/bash
# Claude Flow - Doc Sync Reminder Hook
# 작업 완료 시(Stop 이벤트) 대기 중인 문서 동기화 항목 알림
#
# Best Practice: Stop Hook으로 작업 완료 후 실행
# 참고: https://code.claude.com/docs/en/hooks-guide

SYNC_STATE_FILE="/tmp/claude-flow-doc-sync-state.json"

# 대기 중인 동기화 항목이 있는지 확인
if [[ -f "$SYNC_STATE_FILE" ]]; then
    PENDING_COUNT=$(jq 'length' "$SYNC_STATE_FILE" 2>/dev/null || echo "0")

    if [[ "$PENDING_COUNT" -gt 0 ]]; then
        echo "" >&2
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        echo "📋 [Doc Sync] $PENDING_COUNT개의 문서 업데이트 대기 중" >&2
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        echo "" >&2

        # 대기 항목 표시
        jq -r '.[] | "  • \(.fileType): \(.className) → \(.docSection)"' "$SYNC_STATE_FILE" 2>/dev/null >&2

        echo "" >&2
        echo "  💡 /sync-docs 명령으로 문서를 동기화하세요" >&2
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        echo "" >&2
    fi
fi

exit 0
