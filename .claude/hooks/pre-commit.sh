#!/bin/bash
# Claude Flow - Pre Commit Hook
# 커밋 전 체크리스트 확인

set -e

echo "" >&2
echo "🔍 [Pre-Commit Checklist]" >&2
echo "" >&2

# 1. 테스트 실행 여부 확인
echo "   ✓ 테스트 실행 권장: ./gradlew test" >&2

# 2. 빌드 확인
echo "   ✓ 빌드 확인 권장: ./gradlew build" >&2

# 3. 변경된 파일 확인
CHANGED_FILES=$(git diff --cached --name-only 2>/dev/null || echo "")

if echo "$CHANGED_FILES" | grep -q "Repository.kt\|Plugin.kt\|Router.kt\|Controller.kt"; then
    echo "" >&2
    echo "   ⚠️  주요 컴포넌트 변경 감지!" >&2
    echo "   → CLAUDE.md 업데이트 필요 여부 확인하세요." >&2
fi

if echo "$CHANGED_FILES" | grep -q "build.gradle"; then
    echo "" >&2
    echo "   ⚠️  빌드 설정 변경 감지!" >&2
    echo "   → README.md의 기술 스택 섹션 확인하세요." >&2
fi

echo "" >&2

exit 0
