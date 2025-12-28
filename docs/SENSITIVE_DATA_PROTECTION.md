# 민감 정보 누출 방지 시스템

오픈소스 프로젝트에서 회사/도메인 특정 값이 실수로 커밋되는 것을 방지하는 pre-commit hook 시스템입니다.

## 작동 방식

```
git commit
    │
    ▼
┌─────────────────────────────────────┐
│  .git/hooks/pre-commit 실행         │
│                                     │
│  1. 스테이징된 파일 목록 수집        │
│  2. 제외 패턴 필터링                 │
│  3. 민감 패턴 검색                   │
│  4. 발견 시 커밋 차단 + 가이드 출력  │
└─────────────────────────────────────┘
    │
    ▼ (패턴 발견 시)
┌─────────────────────────────────────┐
│  ⚠️  민감한 정보 발견!              │
│  ========================================│
│  패턴: sirius/                       │
│  파일: src/controller/Example.kt     │
│    207: // sirius/ccds/project       │
│                                      │
│  커밋이 차단되었습니다.              │
│  → 일반화된 예시로 변경 필요         │
└─────────────────────────────────────┘
```

## 검사 대상 패턴

현재 `.git/hooks/pre-commit`에 정의된 패턴:

| 패턴 | 설명 | 대체 예시 |
|------|------|----------|
| `42dot` | 회사 도메인 | `example.com` |
| `gitlab\.42dot\.` | GitLab URL | `gitlab.example.com` |
| `sirius/` | 내부 그룹명 | `team/`, `group/` |

## 제외되는 파일

다음 파일/경로는 검사에서 제외됩니다:

```bash
EXCLUDE_PATTERNS=(
    "config/projects.json"  # 사용자 설정 (각자 다름)
    ".git/"
    "node_modules/"
    "*.lock"
    "*.min.js"
    "*.min.css"
    ".claude/hooks/"        # Hook 파일 자체
)
```

## 사용 예시

### 1. 정상 커밋 (패턴 없음)
```bash
$ git commit -m "feat: 새 기능 추가"
[main abc1234] feat: 새 기능 추가
 2 files changed, 50 insertions(+)
```

### 2. 차단된 커밋 (패턴 발견)
```bash
$ git commit -m "fix: 버그 수정"
========================================
⚠️  민감한 정보 발견!
========================================

패턴: sirius/
파일: src/Example.kt
  15: // sirius/ccds/authorization-server

========================================
커밋이 차단되었습니다.

위 파일들에서 회사/도메인 특정 값이 발견되었습니다.
오픈소스 프로젝트에서는 이런 값들을 일반화된 예시로 변경해야 합니다.

예시:
  - gitlab.42dot.ai → gitlab.example.com
  - CCDC-123 → PROJ-123
  - sirius/ccds → team/project
========================================
```

### 3. 수정 후 재커밋
```bash
# 파일 수정: sirius/ccds → team/project
$ git add -A
$ git commit -m "fix: 버그 수정"
[main def5678] fix: 버그 수정
```

### 4. 검사 우회 (비권장)
```bash
# 정말 필요한 경우에만 사용
$ git commit --no-verify -m "메시지"
```

## 새 패턴 추가

`.git/hooks/pre-commit` 파일의 `SENSITIVE_PATTERNS` 배열에 추가:

```bash
SENSITIVE_PATTERNS=(
    # 기존 패턴
    "42dot"
    "gitlab\\.42dot\\."
    "sirius/"

    # 새 패턴 추가
    "internal-api\\.company\\."
    "secret-project/"
)
```

## 새 제외 패턴 추가

특정 파일을 검사에서 제외하려면 `EXCLUDE_PATTERNS` 배열에 추가:

```bash
EXCLUDE_PATTERNS=(
    # 기존 패턴
    "config/projects.json"

    # 새 제외 패턴
    "config/local-settings.json"
    "scripts/internal/"
)
```

## Hook 설치 확인

```bash
# Hook 파일 확인
$ ls -la .git/hooks/pre-commit
-rwxr-xr-x  1 user  staff  2847 Dec 28 10:00 .git/hooks/pre-commit

# 실행 권한 부여 (필요시)
$ chmod +x .git/hooks/pre-commit
```

## Claude Code와의 연동

Claude Code가 코드를 수정할 때도 이 hook이 적용됩니다:

1. Claude가 코드 수정
2. `/commit` 명령 실행
3. pre-commit hook이 민감 정보 검사
4. 발견 시 Claude에게 차단 메시지 전달
5. Claude가 자동으로 패턴을 일반화된 예시로 수정
6. 재커밋 성공

```
Claude: git commit -m "fix: 버그 수정"
Hook:   ⚠️ 민감한 정보 발견! sirius/ccds
Claude: 패턴을 group/subgroup으로 변경하겠습니다
Claude: git add -A && git commit -m "fix: 버그 수정"
Hook:   ✓ 커밋 성공
```

## 장점

| 장점 | 설명 |
|------|------|
| **자동화** | 수동 검토 없이 자동으로 감지 |
| **즉각적 피드백** | 커밋 시점에 바로 알림 |
| **교육적** | 어떤 패턴이 문제인지, 어떻게 수정해야 하는지 안내 |
| **유연성** | 패턴 추가/제외 쉽게 가능 |
| **CI 연동** | Claude Code, GitHub Actions 등과 자연스럽게 연동 |

## 한계 및 주의사항

- **정규식 기반**: 복잡한 패턴은 오탐/미탐 가능
- **우회 가능**: `--no-verify`로 건너뛸 수 있음
- **바이너리 제외**: 이미지, 컴파일된 파일은 검사 안 함
- **새 패턴**: 새로운 민감 정보는 수동으로 패턴 추가 필요

## 관련 파일

| 파일 | 역할 |
|------|------|
| `.git/hooks/pre-commit` | Hook 스크립트 |
| `docs/SENSITIVE_DATA_PROTECTION.md` | 이 문서 |
| `.gitignore` | 아예 추적하지 않을 파일 |
| `config/projects.json.example` | 설정 파일 예시 템플릿 |
