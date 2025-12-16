#!/bin/bash
# Claude Flow - Document Sync Hook
# 코드 변경 시 자동으로 문서 업데이트 필요 영역 감지 및 알림
#
# Best Practice: PostToolUse Hook으로 Edit/Write 후 실행
# 참고: https://code.claude.com/docs/en/hooks-guide

set -e

PROJECT_ROOT="/Users/a13801/42dot/claude-flow"
CLAUDE_MD="$PROJECT_ROOT/CLAUDE.md"
ARCHITECTURE_MD="$PROJECT_ROOT/docs/ARCHITECTURE.md"
README_MD="$PROJECT_ROOT/README.md"
LOG_FILE="/tmp/claude-flow-doc-sync.log"
SYNC_STATE_FILE="/tmp/claude-flow-doc-sync-state.json"

# Hook 입력 읽기
INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.file_path // empty')
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')

# 로그 함수
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# 문서 섹션 매핑 정의
declare -A DOC_SECTIONS=(
    ["repository"]="CLAUDE.md > 모듈 구조 > storage/repository/"
    ["plugin"]="CLAUDE.md > 모듈 구조 > plugin/"
    ["routing"]="CLAUDE.md > 모듈 구조 > routing/"
    ["controller"]="CLAUDE.md > 자주 수정하는 파일 | README.md > API"
    ["executor"]="ARCHITECTURE.md > 메시지 처리 흐름"
    ["slack"]="ARCHITECTURE.md > 외부 시스템 연동"
    ["config"]="CLAUDE.md > 환경 변수"
    ["gradle"]="CLAUDE.md > 기술 스택"
)

# 파일 타입 감지
detect_file_type() {
    local path="$1"

    if [[ "$path" == */storage/repository/*.kt ]]; then
        echo "repository"
    elif [[ "$path" == */plugin/*.kt ]]; then
        echo "plugin"
    elif [[ "$path" == */routing/*.kt ]]; then
        echo "routing"
    elif [[ "$path" == */rest/*Controller.kt ]]; then
        echo "controller"
    elif [[ "$path" == */executor/*.kt ]]; then
        echo "executor"
    elif [[ "$path" == */slack/*.kt ]]; then
        echo "slack"
    elif [[ "$path" == *Config*.kt ]] || [[ "$path" == */config/*.kt ]]; then
        echo "config"
    elif [[ "$path" == *build.gradle* ]]; then
        echo "gradle"
    elif [[ "$path" == *docker-compose*.yml ]]; then
        echo "docker"
    else
        echo "other"
    fi
}

# 클래스/인터페이스 이름 추출
extract_class_name() {
    local path="$1"
    if [[ -f "$path" ]]; then
        grep -oP '(?<=^class |^interface |^object )\w+' "$path" 2>/dev/null | head -1
    else
        basename "$path" .kt
    fi
}

# 문서에 이미 있는지 확인
check_documented() {
    local name="$1"
    local doc_file="$2"

    if [[ -f "$doc_file" ]]; then
        grep -q "$name" "$doc_file" 2>/dev/null
        return $?
    fi
    return 1
}

# 동기화 상태 저장
save_sync_state() {
    local file_type="$1"
    local class_name="$2"
    local doc_section="$3"

    # JSON 상태 파일에 추가
    local timestamp=$(date '+%Y-%m-%dT%H:%M:%S')
    local entry="{\"timestamp\":\"$timestamp\",\"fileType\":\"$file_type\",\"className\":\"$class_name\",\"docSection\":\"$doc_section\"}"

    if [[ -f "$SYNC_STATE_FILE" ]]; then
        # 기존 배열에 추가
        jq --argjson entry "$entry" '. += [$entry]' "$SYNC_STATE_FILE" > "${SYNC_STATE_FILE}.tmp" && mv "${SYNC_STATE_FILE}.tmp" "$SYNC_STATE_FILE"
    else
        echo "[$entry]" > "$SYNC_STATE_FILE"
    fi
}

# 메인 로직
main() {
    [[ -z "$FILE_PATH" ]] && exit 0

    local file_type=$(detect_file_type "$FILE_PATH")
    [[ "$file_type" == "other" ]] && exit 0

    local class_name=$(extract_class_name "$FILE_PATH")
    local doc_section="${DOC_SECTIONS[$file_type]}"

    log "Detected: $file_type - $class_name in $FILE_PATH"

    # 문서화 상태 확인
    local needs_update=false
    local update_targets=()

    case "$file_type" in
        repository|plugin|routing)
            if ! check_documented "$class_name" "$CLAUDE_MD"; then
                needs_update=true
                update_targets+=("CLAUDE.md")
            fi
            if ! check_documented "$class_name" "$ARCHITECTURE_MD"; then
                needs_update=true
                update_targets+=("ARCHITECTURE.md")
            fi
            ;;
        controller)
            if ! check_documented "$class_name" "$CLAUDE_MD"; then
                needs_update=true
                update_targets+=("CLAUDE.md")
            fi
            if ! check_documented "$class_name" "$README_MD"; then
                needs_update=true
                update_targets+=("README.md")
            fi
            ;;
        executor|slack)
            if ! check_documented "$class_name" "$ARCHITECTURE_MD"; then
                needs_update=true
                update_targets+=("ARCHITECTURE.md")
            fi
            ;;
        gradle|docker)
            needs_update=true
            update_targets+=("CLAUDE.md (기술 스택)")
            ;;
    esac

    if $needs_update; then
        save_sync_state "$file_type" "$class_name" "$doc_section"

        echo "" >&2
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        echo "📝 [Doc Sync] 문서 업데이트 필요" >&2
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        echo "" >&2
        echo "  파일: $FILE_PATH" >&2
        echo "  타입: $file_type" >&2
        echo "  클래스: $class_name" >&2
        echo "" >&2
        echo "  📄 업데이트 대상:" >&2
        for target in "${update_targets[@]}"; do
            echo "     - $target" >&2
        done
        echo "" >&2
        echo "  💡 /sync-docs 명령으로 자동 동기화하세요" >&2
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        echo "" >&2

        log "Update needed: ${update_targets[*]}"
    fi
}

main
exit 0
