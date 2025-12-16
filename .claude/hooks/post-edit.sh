#!/bin/bash
# Claude Flow - Post Edit Hook
# 코드 편집 후 문서 업데이트 필요 여부 알림

set -e

# Read hook input from stdin (jq 없이 grep/sed 사용)
INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | grep -o '"file_path"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')

# 문서 업데이트가 필요한 파일 패턴
IMPORTANT_PATTERNS=(
    "*/storage/repository/*.kt"
    "*/plugin/*.kt"
    "*/routing/*.kt"
    "*/ratelimit/*.kt"
    "*/rest/*Controller.kt"
)

# 패턴 매칭 확인
for pattern in "${IMPORTANT_PATTERNS[@]}"; do
    if [[ "$FILE_PATH" == $pattern ]]; then
        echo "" >&2
        echo "📝 [Documentation Reminder]" >&2
        echo "   파일 변경됨: $FILE_PATH" >&2
        echo "   CLAUDE.md 또는 README.md 업데이트가 필요할 수 있습니다." >&2
        echo "   /update-docs 명령으로 확인하세요." >&2
        echo "" >&2
        break
    fi
done

# 새 파일 생성 감지
if [[ "$FILE_PATH" == *"Repository.kt" ]] && [[ ! -f "$FILE_PATH" ]]; then
    echo "" >&2
    echo "🆕 [New Repository Created]" >&2
    echo "   CLAUDE.md의 모듈 구조 섹션을 업데이트하세요." >&2
    echo "" >&2
fi

if [[ "$FILE_PATH" == *"Plugin.kt" ]] && [[ ! -f "$FILE_PATH" ]]; then
    echo "" >&2
    echo "🆕 [New Plugin Created]" >&2
    echo "   config/plugins.toml에 설정을 추가하세요." >&2
    echo "" >&2
fi

exit 0
