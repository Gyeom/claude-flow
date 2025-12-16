#!/usr/bin/env bash
# Claude Flow - Document Sync Hook
# 코드 변경 시 자동으로 문서 업데이트 필요 영역 감지 및 알림

set -e

# PROJECT_ROOT를 환경변수 또는 git root로 설정
PROJECT_ROOT="${CLAUDE_FLOW_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
CLAUDE_MD="$PROJECT_ROOT/CLAUDE.md"
ARCHITECTURE_MD="$PROJECT_ROOT/docs/ARCHITECTURE.md"
README_MD="$PROJECT_ROOT/README.md"
LOG_FILE="/tmp/claude-flow-doc-sync.log"

# Hook 입력 읽기 (jq 없이 grep/sed 사용)
INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | grep -o '"file_path"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')

# 로그 함수
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# 파일 타입 감지
detect_file_type() {
    local path="$1"

    case "$path" in
        */storage/repository/*.kt) echo "repository" ;;
        */plugin/*.kt) echo "plugin" ;;
        */routing/*.kt) echo "routing" ;;
        */rest/*Controller.kt) echo "controller" ;;
        */executor/*.kt) echo "executor" ;;
        */slack/*.kt) echo "slack" ;;
        *Config*.kt|*/config/*.kt) echo "config" ;;
        *build.gradle*) echo "gradle" ;;
        *docker-compose*.yml) echo "docker" ;;
        *) echo "other" ;;
    esac
}

# 클래스/인터페이스 이름 추출
extract_class_name() {
    local path="$1"
    if [[ -f "$path" ]]; then
        grep -E '^(class|interface|object) ' "$path" 2>/dev/null | head -1 | sed 's/^[^[:space:]]* \([A-Za-z0-9_]*\).*/\1/'
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

# 메인 로직
main() {
    [[ -z "$FILE_PATH" ]] && exit 0

    local file_type=$(detect_file_type "$FILE_PATH")
    [[ "$file_type" == "other" ]] && exit 0

    local class_name=$(extract_class_name "$FILE_PATH")

    log "Detected: $file_type - $class_name in $FILE_PATH"

    # 문서화 상태 확인
    local needs_update=false
    local update_targets=""

    case "$file_type" in
        repository|plugin|routing)
            if ! check_documented "$class_name" "$CLAUDE_MD"; then
                needs_update=true
                update_targets="$update_targets CLAUDE.md"
            fi
            if ! check_documented "$class_name" "$ARCHITECTURE_MD"; then
                needs_update=true
                update_targets="$update_targets ARCHITECTURE.md"
            fi
            ;;
        controller)
            if ! check_documented "$class_name" "$CLAUDE_MD"; then
                needs_update=true
                update_targets="$update_targets CLAUDE.md"
            fi
            if ! check_documented "$class_name" "$README_MD"; then
                needs_update=true
                update_targets="$update_targets README.md"
            fi
            ;;
        executor|slack)
            if ! check_documented "$class_name" "$ARCHITECTURE_MD"; then
                needs_update=true
                update_targets="$update_targets ARCHITECTURE.md"
            fi
            ;;
        gradle|docker)
            needs_update=true
            update_targets="$update_targets CLAUDE.md"
            ;;
    esac

    if $needs_update; then
        echo "" >&2
        echo "📝 [Doc Sync] 문서 업데이트 권장: $class_name →$update_targets" >&2
        log "Update needed:$update_targets"
    fi
}

main
exit 0
