#!/usr/bin/env bash
# Claude Flow - Doc Sync Reminder Hook
# 작업 완료 시(Stop 이벤트) 대기 중인 문서 동기화 항목 알림

SYNC_STATE_FILE="/tmp/claude-flow-doc-sync-state.json"

# 대기 중인 동기화 항목이 있는지 확인
if [[ -f "$SYNC_STATE_FILE" ]]; then
    # jq 없이 라인 수로 카운트 (JSON 배열이므로 대략적인 수치)
    PENDING_COUNT=$(grep -c '"className"' "$SYNC_STATE_FILE" 2>/dev/null || echo "0")

    if [[ "$PENDING_COUNT" -gt 0 ]]; then
        echo "" >&2
        echo "📋 [Doc Sync] ${PENDING_COUNT}개의 문서 업데이트 대기 중" >&2
        echo "   💡 /sync-docs 명령으로 문서를 동기화하세요" >&2
        echo "" >&2
    fi
fi

exit 0
