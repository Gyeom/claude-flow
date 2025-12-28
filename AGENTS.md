# Claude Flow Agents

이 문서는 Claude Code가 Claude Flow 프로젝트 개발 시 활용할 수 있는 전문화된 서브에이전트를 정의합니다.

> **구현 위치**: `.claude/agents/` 디렉토리에 각 에이전트별 상세 정의가 있습니다.

---

## 에이전트 개요

| 에이전트 | 역할 | 모델 | 도구 | 사용 시점 |
|---------|------|------|------|----------|
| [code-reviewer](#code-reviewer) | 코드 리뷰 및 MR 분석 | **Opus** | Read, Write, Edit, Grep, Glob, Bash | MR 리뷰, 코드 검토 |
| [bug-fixer](#bug-fixer) | 버그 분석 및 수정 | **Opus** | Read, Edit, Write, Bash, Grep, Glob | 에러, 버그, 수정 요청 |
| [refactor](#refactor) | 코드 리팩토링 | Sonnet | Read, Edit, Write, Bash, Grep, Glob | 개선, 정리, 클린업 |
| [security-reviewer](#security-reviewer) | 보안 취약점 검토 | Sonnet | Read, Grep, Glob | 보안 검토, OWASP |
| [test-writer](#test-writer) | 테스트 코드 작성 | Sonnet | Read, Write, Edit, Bash, Grep, Glob | 테스트 추가 |

---

## 에이전트 상세

### code-reviewer

**코드 리뷰 및 MR 분석 전문가** (Opus 모델)

```yaml
트리거: 리뷰, review, MR, PR, 코드리뷰, diff, 검토
모델: claude-opus-4-20250514
도구: Read, Write, Edit, Grep, Glob, Bash
```

**작업 프로세스**:
1. MR/PR 변경사항 분석 (컨텍스트 데이터 활용)
2. 파일별 변경 유형 분류 (추가/수정/삭제/이름변경)
3. 자동 감지된 이슈 검증
4. 보안, 성능, 유지보수성 관점 리뷰
5. 리뷰 결과 포맷팅 및 점수 부여

**리뷰 형식**:
```markdown
## MR !{번호} 코드 리뷰 결과

📋 **개요**
- 제목, 작성자, 브랜치, 변경 파일 수

📁 **변경 파일 분석**
- Rename / Add / Delete / Modify 분류

🚨 **감지된 이슈**
- 보안, Breaking Change, 네이밍 불일치 등

✅ **긍정적인 측면** / ⚠️ **개선 필요 사항**

📊 **리뷰 점수: X/10**
```

---

### bug-fixer

**버그 분석 및 수정 전문가** (Opus 모델)

```yaml
트리거: 버그, bug, fix, 에러, error, 오류, 수정, Exception, 문제 해결
모델: claude-opus-4-20250514
도구: Read, Edit, Write, Bash, Grep, Glob
```

**작업 프로세스**:
1. 에러 메시지와 스택 트레이스 분석
2. `Grep`으로 에러 발생 위치 검색
3. 근본 원인 파악
4. `Edit`으로 최소한의 수정 적용
5. `./gradlew test`로 검증

---

### refactor

**코드 리팩토링 전문가**

```yaml
트리거: 리팩토링, refactor, 개선, 정리, 클린업, cleanup
도구: Read, Edit, Write, Bash, Grep, Glob
```

**리팩토링 원칙**:
- SOLID 원칙 준수
- Kotlin 관용구 활용 (when, extension functions, sealed class)
- 동작 변경 없이 구조만 개선
- 작은 단위로 커밋

---

### security-reviewer

**보안 취약점 검토 전문가**

```yaml
트리거: 보안, security, 취약점, vulnerability, OWASP
도구: Read, Grep, Glob (읽기 전용)
```

**OWASP Top 10 체크**:
1. Injection (SQL, Command)
2. Broken Authentication
3. Sensitive Data Exposure
4. Broken Access Control
5. Security Misconfiguration
6. XSS
7. Insecure Deserialization
8. Known Vulnerabilities
9. Insufficient Logging

**Claude Flow 특화 검토**:
- Slack 토큰 처리 (SlackSocketModeBridge)
- 플러그인 API 토큰 관리
- Claude CLI 명령어 인젝션 방지

---

### test-writer

**테스트 코드 작성 전문가**

```yaml
트리거: 테스트, test, 단위 테스트, unit test, 테스트 추가
도구: Read, Write, Edit, Bash, Grep, Glob
```

**테스트 스타일**:
- **Kotlin**: Kotest (DescribeSpec)
- **React**: Jest + React Testing Library

**Kotest 패턴**:
```kotlin
describe("MyService") {
    context("특정 조건에서") {
        it("예상 동작을 한다") {
            result shouldBe expected
        }
    }
}
```

**테스트 실행**:
```bash
./gradlew test                           # 전체
./gradlew :claude-flow-core:test         # 모듈별
cd dashboard && npm test                  # React
```

---

## 복합 작업 처리

### 순차 실행이 필요한 조합

```
리팩토링 + 테스트:
  refactor → test-writer (변경 후 테스트 추가)

코드 작성 + 보안 검토:
  (작업) → security-reviewer (작성 후 보안 검토)
```

---

## 에이전트 선택 가이드

```
사용자 요청 분석
    │
    ├─ 버그/에러 관련? ──────────→ bug-fixer
    │
    ├─ 리팩토링/개선? ──────────→ refactor
    │
    ├─ 보안 검토? ─────────────→ security-reviewer
    │
    ├─ 테스트 작성? ────────────→ test-writer
    │
    └─ 복합 요청? ──────────────→ 해당 에이전트 조합
```

---

## 주의사항

1. **단일 작업은 직접 처리**: 간단한 질문이나 단일 작업은 서브에이전트 없이 직접 처리
2. **컨텍스트 전달**: 순차 실행 시 이전 결과를 다음 에이전트에 전달
3. **과도한 분할 금지**: 2-3개 이상 에이전트가 필요하면 작업 범위 재검토
4. **도구 제한 준수**: 각 에이전트는 정의된 도구만 사용 (보안)

---

## 파일 구조

```
.claude/agents/
├── bug-fixer.md         # 버그 수정 전문가
├── refactor.md          # 리팩토링 전문가
├── security-reviewer.md # 보안 검토 전문가
└── test-writer.md       # 테스트 작성 전문가
```

---

## 참고: 다른 프로젝트용 에이전트

Jira, GitLab 연동 에이전트는 각 프로젝트에 별도로 정의되어 있습니다:

```
my-api-server/.claude/agents/           # API 서버용
my-web-app/.claude/agents/                 # 웹 앱용
my-auth-server/.claude/agents/             # 인증 서버용
```
