---
description: "Create git commit and push changes"
---

# Commit Command

변경 사항을 확인하고 Git 커밋을 생성한 후 push합니다.

## Arguments
- `$ARGUMENTS` - 옵션: `--no-push` (push 안함), 또는 커밋 메시지

## Instructions

### 1. 변경 사항 확인

먼저 현재 Git 상태를 확인합니다:

```bash
# 상태 확인
git status

# 변경 내용 확인
git diff --stat

# 최근 커밋 스타일 확인
git log --oneline -5
```

### 2. 커밋 메시지 작성

변경 사항을 분석하여 커밋 메시지를 작성합니다:

**메시지 형식:**
```
<type>: <subject>

[optional body]

🤖 Generated with [Claude Code](https://claude.ai/code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

**타입:**
- `feat`: 새 기능
- `fix`: 버그 수정
- `docs`: 문서 변경
- `style`: 코드 포맷팅
- `refactor`: 리팩토링
- `test`: 테스트 추가/수정
- `chore`: 빌드, 설정 변경

### 3. 커밋 실행

사용자가 메시지를 제공한 경우 해당 메시지 사용, 아니면 변경 사항 기반으로 자동 생성:

```bash
# 모든 변경 사항 스테이징
git add -A

# 커밋 생성
git commit -m "커밋 메시지"
```

### 4. Push (기본 동작)

커밋 후 자동으로 원격 저장소에 push합니다:

```bash
# 현재 브랜치를 원격에 push
git push origin HEAD
```

`--no-push` 옵션을 주면 push를 생략합니다.

### 5. 주의사항

- `.env`, `credentials.json` 등 민감한 파일은 커밋하지 않음
- pre-commit hook이 실행되면 결과에 따라 처리
- 변경 사항이 없으면 커밋하지 않음
- main/master 브랜치에 직접 push 시 경고
