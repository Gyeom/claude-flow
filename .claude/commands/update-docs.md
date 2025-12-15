---
description: Update project documentation after code changes
---

# Documentation Update

이 명령은 코드 변경 후 문서를 자동으로 업데이트합니다.

## 수행 작업

1. **CLAUDE.md 업데이트 확인**
   - 새로운 모듈/컴포넌트가 추가되었는지 확인
   - 아키텍처 변경이 있는지 확인
   - 필요시 CLAUDE.md 업데이트

2. **README.md 업데이트 확인**
   - 새 API 엔드포인트가 추가되었는지 확인
   - 설치 방법이 변경되었는지 확인

3. **인라인 문서 확인**
   - 새 public 함수에 KDoc이 있는지 확인
   - 복잡한 로직에 주석이 있는지 확인

## 체크리스트

최근 변경된 파일들을 확인하고:

```bash
git diff --name-only HEAD~5
```

다음 문서들이 최신 상태인지 확인:
- [ ] CLAUDE.md의 모듈 구조
- [ ] CLAUDE.md의 기술 스택
- [ ] README.md의 API 테이블
- [ ] README.md의 빠른 시작 가이드

변경이 필요하면 해당 파일을 업데이트하세요.
