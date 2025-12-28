#!/bin/bash
# Claude Hook: 민감한 정보 검사
# PostToolUse hook으로 사용하여 Edit/Write 시 경고

# 환경변수에서 파일 경로 가져오기
FILE_PATH="${CLAUDE_FILE_PATH:-}"

if [ -z "$FILE_PATH" ]; then
    exit 0
fi

# 제외할 파일 패턴
case "$FILE_PATH" in
    */config/projects.json|*node_modules*|*.lock|*.min.js|*.min.css)
        exit 0
        ;;
esac

# 바이너리 파일 건너뛰기
if [ -f "$FILE_PATH" ] && file "$FILE_PATH" | grep -q "binary"; then
    exit 0
fi

# 검사할 패턴 목록
PATTERNS="42dot|gitlab\.42dot\.|sirius/"

# 파일 검사
if [ -f "$FILE_PATH" ]; then
    MATCHES=$(grep -inE "$PATTERNS" "$FILE_PATH" 2>/dev/null || true)

    if [ -n "$MATCHES" ]; then
        echo "⚠️  경고: 회사 특정 값이 발견되었습니다!"
        echo "파일: $FILE_PATH"
        echo "$MATCHES" | head -5
        echo ""
        echo "오픈소스 프로젝트에서는 일반화된 예시를 사용하세요."
    fi
fi

exit 0
