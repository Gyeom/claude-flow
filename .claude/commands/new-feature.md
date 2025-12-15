---
description: Plan and implement a new feature
---

# New Feature Implementation

새로운 기능을 계획하고 구현합니다.

## 사용법

`/new-feature <feature-name>`

예시:
- `/new-feature slack-thread-context`
- `/new-feature multi-model-routing`
- `/new-feature cost-tracking`

## 구현 프로세스

### 1. 분석 단계
- 기존 코드베이스 분석
- 영향받는 모듈 파악
- 기술적 접근 방법 결정

### 2. 설계 단계
- 데이터 모델 설계
- API 인터페이스 설계
- 테스트 케이스 정의

### 3. 구현 단계
아래 순서로 구현:
1. 데이터 레이어 (Repository)
2. 비즈니스 로직 (Service/Manager)
3. API 엔드포인트 (Controller)
4. 테스트 코드
5. 문서 업데이트

### 4. 검증 단계
```bash
# 테스트 실행
./gradlew test

# 빌드 확인
./gradlew build

# 로컬 실행 테스트
./gradlew :claude-flow-app:bootRun
```

## 파일 생성 위치

| 유형 | 위치 |
|------|------|
| 데이터 모델 | `claude-flow-core/src/main/kotlin/.../storage/` |
| Repository | `claude-flow-core/src/main/kotlin/.../storage/repository/` |
| 비즈니스 로직 | `claude-flow-core/src/main/kotlin/.../` |
| API Controller | `claude-flow-api/src/main/kotlin/.../rest/` |
| 테스트 | `claude-flow-core/src/test/kotlin/...` |

## 체크리스트

- [ ] 기존 패턴 준수
- [ ] 테스트 코드 작성
- [ ] 에러 처리
- [ ] 로깅 추가
- [ ] 문서 업데이트 (CLAUDE.md, README.md)
- [ ] Slash command 추가 (필요시)
