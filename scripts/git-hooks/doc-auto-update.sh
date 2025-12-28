#!/bin/bash
# Claude Flow - Auto Documentation Update Hook
# 커밋 전 Claude CLI를 사용하여 문서 자동 업데이트

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 설정
SKIP_DOC_UPDATE="${SKIP_DOC_UPDATE:-false}"
DOC_UPDATE_TIMEOUT="${DOC_UPDATE_TIMEOUT:-120}"

# 스킵 조건 체크
if [ "$SKIP_DOC_UPDATE" = "true" ]; then
    echo -e "${YELLOW}⏭️  문서 업데이트 스킵 (SKIP_DOC_UPDATE=true)${NC}"
    exit 0
fi

# Claude CLI 확인
if ! command -v claude &> /dev/null; then
    echo -e "${YELLOW}⚠️  Claude CLI가 설치되어 있지 않습니다. 문서 업데이트를 건너뜁니다.${NC}"
    exit 0
fi

# 변경된 파일 확인
STAGED_FILES=$(git diff --cached --name-only)
if [ -z "$STAGED_FILES" ]; then
    exit 0
fi

# 코드 파일만 필터링 (문서 파일 제외)
CODE_FILES=$(echo "$STAGED_FILES" | grep -E '\.(kt|java|ts|tsx|js|jsx|py|go|rs|json)$' | grep -v 'package.*json' || true)
if [ -z "$CODE_FILES" ]; then
    # 코드 변경 없으면 스킵
    exit 0
fi

# 삭제된 함수/클래스/API 추출을 위한 diff
DELETED_CONTENT=$(git diff --cached --diff-filter=M -U0 | grep -E '^\-' | grep -vE '^---' || true)

# 삭제된 파일
DELETED_FILES=$(git diff --cached --name-only --diff-filter=D)

# 변경 내용이 문서 업데이트가 필요한 수준인지 확인
NEEDS_UPDATE=false

# API 엔드포인트 삭제 감지
if echo "$DELETED_CONTENT" | grep -qE '@(Get|Post|Put|Delete|Patch)Mapping|@RequestMapping|"/api/'; then
    NEEDS_UPDATE=true
    echo -e "${BLUE}🔍 API 엔드포인트 변경 감지${NC}"
fi

# 클래스/인터페이스 삭제 감지
if echo "$DELETED_CONTENT" | grep -qE '^\-\s*(class|interface|object|fun)\s+[A-Z]'; then
    NEEDS_UPDATE=true
    echo -e "${BLUE}🔍 클래스/함수 삭제 감지${NC}"
fi

# 파일 삭제 감지
if [ -n "$DELETED_FILES" ]; then
    NEEDS_UPDATE=true
    echo -e "${BLUE}🔍 파일 삭제 감지: $DELETED_FILES${NC}"
fi

if [ "$NEEDS_UPDATE" = "false" ]; then
    echo -e "${GREEN}✓ 문서 업데이트 불필요 (API/클래스 변경 없음)${NC}"
    exit 0
fi

echo -e "${YELLOW}📝 Claude로 문서 분석 중... (최대 ${DOC_UPDATE_TIMEOUT}초)${NC}"

# Claude에게 분석 요청
PROMPT="다음 Git 변경사항을 분석하고, 문서 업데이트가 필요한지 확인해줘.

## 변경된 코드 파일
$CODE_FILES

## 삭제된 코드 (diff에서 - 로 시작하는 라인)
$(echo "$DELETED_CONTENT" | head -100)

## 삭제된 파일
$DELETED_FILES

## 작업 지시
1. 위 변경사항에서 삭제/이름변경된 API 엔드포인트, 클래스, 주요 함수를 식별해
2. 다음 문서들에서 해당 항목의 참조가 있는지 Grep으로 검색해:
   - README.md, README.en.md
   - CLAUDE.md
   - docs/*.md
   - .claude/commands/*.md
3. 발견된 참조가 있으면 문서를 Edit 도구로 업데이트해 (삭제된 항목 제거 또는 새 이름으로 변경)
4. 업데이트한 파일 목록만 간단히 한 줄로 알려줘

문서 변경이 필요 없으면 '문서 업데이트 없음'이라고만 응답해."

# Claude 실행 (타임아웃 설정)
RESULT=$(timeout $DOC_UPDATE_TIMEOUT claude -p "$PROMPT" --allowedTools "Grep,Read,Edit" 2>&1)
EXIT_CODE=$?

if [ $EXIT_CODE -eq 124 ]; then
    echo -e "${YELLOW}⚠️  Claude 실행 타임아웃. 문서 업데이트를 건너뜁니다.${NC}"
    exit 0
fi

if [ $EXIT_CODE -ne 0 ]; then
    echo -e "${YELLOW}⚠️  Claude 실행 오류. 문서 업데이트를 건너뜁니다.${NC}"
    exit 0
fi

# 결과 요약 출력
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo "$RESULT" | tail -5
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

# 문서 파일이 변경됐으면 staging에 추가
UPDATED_DOCS=$(git status --porcelain | grep -E '^\s*M.*(README|CLAUDE|\.md)' | awk '{print $2}' || true)
if [ -n "$UPDATED_DOCS" ]; then
    echo -e "${GREEN}📄 업데이트된 문서를 커밋에 추가:${NC}"
    for doc in $UPDATED_DOCS; do
        echo "   - $doc"
        git add "$doc"
    done
fi

echo -e "${GREEN}✓ 문서 자동 업데이트 완료${NC}"
exit 0
