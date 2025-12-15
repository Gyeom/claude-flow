---
description: Refactor code following project patterns
---

# Refactor Code

프로젝트 패턴에 맞게 코드를 리팩토링합니다.

## 사용법

`/refactor <file-path> [reason]`

예시:
- `/refactor Storage.kt too-large`
- `/refactor AgentRouter.kt add-korean-support`

## 프로젝트 패턴

### 1. Repository Pattern
데이터 접근 로직은 Repository로 분리:
```kotlin
class {Entity}Repository(
    connectionProvider: ConnectionProvider
) : BaseRepository<{Entity}, String>(connectionProvider) {
    override val tableName = "{table_name}"
    // ...
}
```

### 2. Plugin System
외부 서비스 연동은 Plugin으로:
```kotlin
class {Service}Plugin : BasePlugin() {
    override val id = "{service}"
    override val name = "{Service} Plugin"
    // ...
}
```

### 3. Builder Pattern
복잡한 객체 생성에는 Builder:
```kotlin
class {Object}Builder {
    fun with{Property}(value: Type): {Object}Builder
    fun build(): {Object}
}
```

## 리팩토링 체크리스트

- [ ] 단일 책임 원칙 (SRP) 준수
- [ ] 200줄 이상 파일은 분리 검토
- [ ] 중복 코드 제거
- [ ] 테스트 유지/추가
- [ ] 문서 업데이트 (CLAUDE.md)

## 리팩토링 후

1. 테스트 실행: `./gradlew :claude-flow-core:test`
2. 빌드 확인: `./gradlew build`
3. 문서 업데이트: `/update-docs`
