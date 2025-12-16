---
description: Automatically sync architecture documentation with code changes
---

# Documentation Sync

이 명령은 코드 변경사항을 자동으로 감지하고 문서를 동기화합니다.

## 1. 변경 감지

최근 변경된 주요 파일들을 분석합니다:

### Repository 변경
```bash
find ./claude-flow-core/src/main/kotlin -path "*/storage/repository/*.kt" -type f 2>/dev/null | xargs -I {} basename {} .kt | sort
```

### Plugin 변경
```bash
find ./claude-flow-core/src/main/kotlin -path "*/plugin/*.kt" -type f 2>/dev/null | xargs -I {} basename {} .kt | sort
```

### API Controller 변경
```bash
find ./claude-flow-api/src/main/kotlin -name "*Controller.kt" -type f 2>/dev/null | xargs -I {} basename {} .kt | sort
```

### Routing 변경
```bash
find ./claude-flow-core/src/main/kotlin -path "*/routing/*.kt" -type f 2>/dev/null | xargs -I {} basename {} .kt | sort
```

## 2. 문서 동기화 체크리스트

위 스캔 결과를 다음 문서들과 비교하세요:

### CLAUDE.md 업데이트 필요 영역
- **모듈 구조**: 새 Repository, Plugin, Router 클래스
- **기술 스택**: build.gradle.kts 의존성 변경
- **자주 수정하는 파일**: 새 Controller 추가

### docs/ARCHITECTURE.md 업데이트 필요 영역
- **스토리지 계층 다이어그램**: 새 Repository 추가 시
- **플러그인 시스템 다이어그램**: 새 Plugin 추가 시
- **에이전트 라우팅 다이어그램**: Routing 로직 변경 시
- **메시지 처리 흐름**: 주요 흐름 변경 시

### README.md 업데이트 필요 영역
- **API 엔드포인트 테이블**: 새 Controller/엔드포인트 추가 시
- **설치 가이드**: 환경 변수 변경 시

## 3. 자동 동기화 작업

다음 작업을 수행하세요:

1. **CLAUDE.md 모듈 구조 업데이트**
   - 새 클래스가 있으면 해당 섹션에 추가
   - 삭제된 클래스가 있으면 제거

2. **ARCHITECTURE.md Mermaid 다이어그램 업데이트**
   - ER 다이어그램에 새 엔티티 추가
   - 클래스 다이어그램에 새 클래스 추가
   - 시퀀스 다이어그램 흐름 업데이트

3. **변경 이력 기록**
   - 문서 상단의 lastUpdated 날짜 갱신

## 4. 동기화 상태 확인

```bash
cat /tmp/claude-flow-doc-sync-state.json 2>/dev/null || echo "No pending sync items"
```

## 5. 완료 후 정리

동기화 완료 후:
```bash
rm -f /tmp/claude-flow-doc-sync-state.json
```
