# 프로젝트 컨텍스트 자동 주입 (Project Context Injection)

## 문제 현상

### Before (개선 전)
사용자가 "authorization-server에 대해 설명해줘"라고 요청했을 때:

```
❌ 일반적인 OAuth 2.0 Authorization Server 개념 설명
- "Authorization Server는 OAuth 2.0 및 OpenID Connect 프로토콜에서..."
- 실제 프로젝트와 무관한 일반적인 설명
```

**문제점**: Claude가 `authorization-server`를 **일반 용어**로 인식하여 OAuth 개념을 설명함.
실제로는 `~/projects/authorization-server` 프로젝트를 의미하는 것임.

### After (개선 후)
동일한 요청에 대해:

```
✅ 실제 프로젝트 기반 설명
- "authorization-server 프로젝트를 찾았고 구조를 파악했습니다"
- OpenFGA 기반의 중앙 권한 관리 서버
- Hexagonal Architecture, Kotlin 2.1.20, Spring Boot 3.4.4
- 실제 모듈 구조 (auth-domain, auth-application, auth-adapter 등)
```

---

## 원인 분석

### 1. 컨텍스트 부재
LLM은 기본적으로 **프롬프트에 주어진 정보만** 사용합니다.
"authorization-server"라는 단어만으로는:
- 일반 용어인지
- 특정 프로젝트명인지
- 파일명인지

구분할 수 없습니다.

### 2. 도메인 지식 vs 로컬 지식
| 유형 | 예시 | LLM 대응 |
|------|------|----------|
| **도메인 지식** | OAuth, REST API, Kubernetes | 학습 데이터로 알고 있음 |
| **로컬 지식** | 로컬 프로젝트, 레포지토리 | 알 수 없음 - 컨텍스트 필요 |

`authorization-server`는 일반적인 OAuth 용어이기도 하면서 동시에 로컬 프로젝트명이기도 합니다.
LLM은 로컬 컨텍스트 없이는 후자를 알 수 없습니다.

---

## 해결 방법

### 아키텍처 개요

```
┌─────────────────────────────────────────────────────────────────┐
│                    사용자 프롬프트                               │
│              "authorization-server 설명해줘"                     │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│              UserPromptSubmit Hook                               │
│         (project-context-injector.sh)                           │
│                                                                  │
│  1. 프롬프트에서 프로젝트명 패턴 탐지                            │
│  2. project-aliases.json에서 별칭 매핑                          │
│  3. 실제 디렉토리 검색                                          │
│  4. README.md, CLAUDE.md 컨텍스트 추출                          │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    강화된 프롬프트                               │
│  "authorization-server 설명해줘"                                │
│  +                                                              │
│  [Detected Project: authorization-server]                       │
│  Path: ~/projects/authorization-server                         │
│  --- Project Instructions ---                                   │
│  OpenFGA 기반의 중앙 권한 관리 서버...                          │
│  Hexagonal Architecture, Kotlin 2.1.20...                       │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Claude 응답                                   │
│  실제 프로젝트 기반의 정확한 설명 제공                          │
└─────────────────────────────────────────────────────────────────┘
```

### 핵심 컴포넌트

#### 1. UserPromptSubmit Hook
Claude Code의 Hook 시스템을 활용하여 프롬프트가 처리되기 **전에** 컨텍스트를 주입합니다.

```bash
# .claude/settings.local.json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": ".claude/hooks/project-context-injector.sh"
          }
        ]
      }
    ]
  }
}
```

**Hook 이벤트 종류:**
| Hook | 시점 | 용도 |
|------|------|------|
| `UserPromptSubmit` | 프롬프트 제출 전 | 컨텍스트 주입, 유효성 검사 |
| `PreToolUse` | 도구 실행 전 | 권한 검사, 로깅 |
| `PostToolUse` | 도구 실행 후 | 결과 로깅, 후처리 |
| `SessionStart` | 세션 시작 시 | 초기화, 환경 설정 |

#### 2. Project Aliases 설정
한국어/영어 별칭을 실제 프로젝트명으로 매핑합니다.

```json
// .claude/config/project-aliases.json
{
  "workspaceRoot": "${WORKSPACE_PATH:-$HOME/workspace}",
  "aliases": {
    "auth-server": {
      "patterns": ["인가", "인가서버", "인가 서버", "auth서버", "authorization"],
      "description": "권한 관리 서버"
    },
    "api-server": {
      "patterns": ["api", "API", "api서버"],
      "description": "API 서버"
    }
  }
}
```

#### 3. 컨텍스트 추출
프로젝트 디렉토리에서 관련 정보를 자동 추출합니다:

| 파일 | 용도 |
|------|------|
| `CLAUDE.md` | Claude Code 전용 프로젝트 가이드 |
| `README.md` | 프로젝트 개요 |
| `build.gradle.kts` | 기술 스택 탐지 (Kotlin/Gradle) |
| `package.json` | 기술 스택 탐지 (Node.js) |
| `pom.xml` | 기술 스택 탐지 (Java/Maven) |

---

## 구현 상세

### project-context-injector.sh 동작 흐름

```bash
# 1. 프롬프트 수신
INPUT=$(cat)  # JSON 형태로 수신: {"prompt": "authorization-server 설명해줘"}

# 2. 프로젝트 후보 추출
extract_project_candidates() {
    # 2-1. 직접 한국어 매칭
    if [[ "$prompt" == *"인가"* ]]; then
        candidates="$candidates authorization-server"
    fi

    # 2-2. 하이픈 이름 패턴 (auth-server, api-server)
    echo "$prompt" | grep -oE '[a-zA-Z][a-zA-Z0-9]*-[a-zA-Z0-9-]+'

    # 2-3. CamelCase 패턴 (AuthorizationServer)
    echo "$prompt" | grep -oE '[A-Z][a-z]+[A-Z][a-zA-Z]+'
}

# 3. 프로젝트 디렉토리 검색
find_project_dir() {
    # 캐시 확인 (5분 TTL)
    # 정확한 이름 매칭
    find "$WORKSPACE_ROOT" -maxdepth 3 -type d -iname "$name"
    # 부분 매칭 폴백
    find "$WORKSPACE_ROOT" -maxdepth 3 -type d -iname "*$name*"
}

# 4. 컨텍스트 추출
get_project_summary() {
    # README.md 앞부분 (1000자)
    # CLAUDE.md 전체 (2000자)
    # 디렉토리 구조
    # 기술 스택 (build.gradle.kts, package.json 등 탐지)
}

# 5. JSON 출력 (Claude Code Hook 형식)
{
    "hookSpecificOutput": {
        "hookEventName": "UserPromptSubmit",
        "additionalContext": "[Detected Project: authorization-server]..."
    }
}
```

### Settings 대시보드

REST API를 통해 별칭을 관리할 수 있습니다:

| Endpoint | Method | 설명 |
|----------|--------|------|
| `/api/v1/settings/project-aliases` | GET | 전체 설정 조회 |
| `/api/v1/settings/project-aliases` | PUT | 전체 설정 저장 |
| `/api/v1/settings/project-aliases/{id}` | PUT | 단일 별칭 추가/수정 |
| `/api/v1/settings/project-aliases/{id}` | DELETE | 단일 별칭 삭제 |
| `/api/v1/settings/project-aliases/test` | POST | 탐지 테스트 |

---

## Best Practices

### 1. Layered Context Architecture
컨텍스트를 계층별로 구성하여 관리합니다:

```
┌─────────────────────────────────────┐
│       Meta-Context (메타)           │
│  - 에이전트 역할, 워크스페이스 정보  │
├─────────────────────────────────────┤
│      Domain Context (도메인)        │
│  - 프로젝트별 CLAUDE.md             │
│  - 기술 스택, 아키텍처 패턴          │
├─────────────────────────────────────┤
│   Environmental Context (환경)      │
│  - 현재 브랜치, 변경 파일            │
│  - 최근 커밋 히스토리                │
└─────────────────────────────────────┘
```

### 2. CLAUDE.md 작성 가이드
각 프로젝트에 `CLAUDE.md` 파일을 작성하면 더 정확한 응답을 받을 수 있습니다:

```markdown
# Project Name - Claude Code Guide

## 프로젝트 개요
- 목적: 무엇을 하는 프로젝트인지
- 아키텍처: Hexagonal, Clean Architecture 등

## 기술 스택
- Language: Kotlin 2.1
- Framework: Spring Boot 3.4

## 주요 모듈
- module-a/: 설명
- module-b/: 설명

## 개발 가이드라인
- 코딩 컨벤션
- 테스트 방법
```

### 3. 별칭 설계 원칙
| 원칙 | 설명 | 예시 |
|------|------|------|
| **다양성** | 여러 형태의 별칭 등록 | 인가, 인가서버, auth서버, authorization |
| **중복 방지** | 서로 다른 프로젝트에 같은 별칭 X | "서버"만 단독 사용 금지 |
| **명확성** | 의미가 분명한 별칭 | "auth" → authorization-server (O) |

---

## 오픈소스 배포 고려사항

### 파일 구조
```
.claude/config/
├── project-aliases.json          # 실제 설정 (gitignore)
└── project-aliases.example.json  # 예제 파일 (버전관리)
```

### 사용자 설정 방법
```bash
# 1. 예제 파일 복사
cp .claude/config/project-aliases.example.json \
   .claude/config/project-aliases.json

# 2. 워크스페이스 경로 설정
{
  "workspaceRoot": "$HOME/my-projects",
  ...
}

# 3. 자신의 프로젝트 별칭 추가
{
  "aliases": {
    "my-backend": {
      "patterns": ["백엔드", "backend"],
      "description": "Main backend service"
    }
  }
}
```

---

## 트러블슈팅

### Hook이 동작하지 않을 때
```bash
# 1. 실행 권한 확인
chmod +x .claude/hooks/project-context-injector.sh

# 2. 수동 테스트
echo '{"prompt": "인가 서버 설명해줘"}' | .claude/hooks/project-context-injector.sh
```

### 프로젝트가 탐지되지 않을 때
1. `workspaceRoot` 경로가 올바른지 확인
2. 별칭이 `project-aliases.json`에 등록되어 있는지 확인
3. 대시보드의 "Test Detection" 기능으로 테스트

### 캐시 문제
```bash
# 캐시 삭제
rm -rf /tmp/claude-flow-project-cache
```

---

## 관련 파일

| 파일 | 설명 |
|------|------|
| `.claude/hooks/project-context-injector.sh` | 컨텍스트 주입 Hook |
| `.claude/config/project-aliases.json` | 별칭 설정 (로컬) |
| `.claude/config/project-aliases.example.json` | 별칭 설정 예제 |
| `claude-flow-api/.../SettingsController.kt` | 설정 API |
| `dashboard/src/pages/Settings.tsx` | 설정 UI |
| `claude-flow-core/.../Agent.kt` | 에이전트 시스템 프롬프트 |
