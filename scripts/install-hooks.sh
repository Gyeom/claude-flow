#!/bin/bash
# Git Hooks 설치 스크립트
# 사용법: ./scripts/install-hooks.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOKS_SRC="$SCRIPT_DIR/git-hooks"
HOOKS_DST="$(git rev-parse --show-toplevel)/.git/hooks"

echo "📦 Git Hooks 설치 중..."

# pre-commit hook 설치
if [ -f "$HOOKS_SRC/pre-commit" ]; then
    cp "$HOOKS_SRC/pre-commit" "$HOOKS_DST/pre-commit"
    chmod +x "$HOOKS_DST/pre-commit"
    echo "  ✓ pre-commit 설치됨"
fi

# doc-auto-update.sh 설치
if [ -f "$HOOKS_SRC/doc-auto-update.sh" ]; then
    cp "$HOOKS_SRC/doc-auto-update.sh" "$HOOKS_DST/doc-auto-update.sh"
    chmod +x "$HOOKS_DST/doc-auto-update.sh"
    echo "  ✓ doc-auto-update.sh 설치됨"
fi

echo ""
echo "✅ Git Hooks 설치 완료!"
echo ""
echo "설치된 기능:"
echo "  - 민감정보 누출 방지 (pre-commit)"
echo "  - 문서 자동 업데이트 (Claude CLI 기반)"
echo ""
echo "참고: Claude CLI가 설치되어 있어야 문서 자동 업데이트가 동작합니다."
