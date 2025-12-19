# Claude Flow - AI Agent Context

이 문서는 Claude가 claude-flow 프로젝트를 이해하고 효과적으로 작업하기 위한 컨텍스트 문서입니다.

## 프로젝트 개요

**Claude Flow**는 Slack에서 Claude를 호출하고 GitLab MR 리뷰를 자동화하는 오픈소스 AI 에이전트 플랫폼입니다.

### 핵심 가치
- 5분 설치 (Docker one-liner)
- 지능형 라우팅 (키워드 → 시맨틱 → LLM 폴백)
- 사용자 컨텍스트 기억 (대화 요약, 규칙 자동 적용)
- 실시간 분석 (P50/P90/P95/P99)

## 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                         Slack                               │
│                    (@멘션, 메시지, 리액션)                   │
└───────────────────────────┬─────────────────────────────────┘
                            │ Socket Mode (WebSocket)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    Claude Flow (Kotlin)                      │
│  ┌─────────────────┐  ┌──────────────┐  ┌───────────────┐   │
│  │ SlackSocketMode │→ │ AgentRouter  │→ │ ClaudeExecutor│   │
│  │     Bridge      │  │  (Smart)     │  │ (Claude CLI)  │   │
│  └─────────────────┘  └──────────────┘  └───────────────┘   │
│                            │                                 │
│  ┌─────────────────────────┴───────────────────────────────┐│
│  │ Storage │ UserContext │ Analytics │ Plugin │ RateLimit  ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

## 모듈 구조

```
claude-flow/
├── claude-flow-core/       # 핵심 도메인 로직
│   ├── storage/            # Repository Pattern 기반 데이터 레이어
│   │   ├── Repository.kt           # 기본 CRUD 인터페이스
│   │   ├── BaseRepository.kt       # 공통 구현
│   │   ├── query/QueryBuilder.kt   # 타입 안전 SQL 빌더
│   │   └── repository/             # 도메인별 Repository
│   │       ├── ExecutionRepository.kt
│   │       ├── FeedbackRepository.kt
│   │       ├── UserContextRepository.kt
│   │       ├── AgentRepository.kt
│   │       └── AnalyticsRepository.kt
│   ├── routing/            # 에이전트 라우팅
│   │   ├── AgentRouter.kt          # 메인 라우터
│   │   └── KoreanOptimizedRouter.kt # 한국어 최적화 (초성, 유사어)
│   ├── plugin/             # 플러그인 시스템
│   │   ├── PluginRegistry.kt       # 플러그인 레지스트리
│   │   ├── PluginLoader.kt         # 동적 로더
│   │   ├── PluginConfigManager.kt  # 설정 관리
│   │   ├── GitLabPlugin.kt         # GitLab 연동
│   │   ├── GitHubPlugin.kt         # GitHub 연동
│   │   ├── JiraPlugin.kt           # Jira 연동
│   │   └── N8nPlugin.kt            # n8n 워크플로우 관리
│   ├── n8n/                # n8n 워크플로우 생성
│   │   └── N8nWorkflowGenerator.kt # AI 기반 워크플로우 생성기
│   ├── ratelimit/          # Rate Limiting
│   │   ├── RateLimitPolicy.kt      # 정책 정의
│   │   └── AdvancedRateLimiter.kt  # 다차원 제한
│   ├── session/            # 세션 관리
│   │   └── SessionManager.kt       # 세션 매니저
│   ├── cache/              # 캐싱
│   │   └── ClassificationCache.kt  # 분류 캐시
│   └── analytics/          # 분석
│       └── Analytics.kt            # 통계 수집
│
├── claude-flow-executor/   # Claude CLI 래퍼
├── claude-flow-api/        # REST API (Spring WebFlux)
├── claude-flow-app/        # Spring Boot 앱 (Slack 연동)
├── dashboard/              # React 대시보드
└── docker-compose/         # Docker 설정, n8n 워크플로우
```

## 기술 스택

| 영역 | 기술 |
|------|------|
| Language | Kotlin 2.1, Java 21 |
| Framework | Spring Boot 3.4, Spring WebFlux |
| Build | Gradle (Kotlin DSL) |
| Database | SQLite (WAL mode) |
| Cache | Caffeine |
| AI | Claude CLI |
| Slack | Bolt for Java (Socket Mode) |
| Workflow | n8n |
| Dashboard | React, Vite, TailwindCSS |

## 개발 가이드라인

### 코드 스타일
- Kotlin 코드는 공식 Kotlin 스타일 가이드 준수
- 함수/클래스는 한글 KDoc 주석 권장
- 테스트는 Kotest (DescribeSpec 스타일)

### 테스트 실행
```bash
# 전체 테스트
./gradlew test

# 특정 모듈 테스트
./gradlew :claude-flow-core:test

# 특정 테스트 클래스
./gradlew :claude-flow-core:test --tests "*KoreanOptimizedRouterTest"
```

### 빌드 및 실행
```bash
# 빌드
./gradlew build

# 앱 실행
./gradlew :claude-flow-app:bootRun

# Docker 실행
cd docker-compose && docker-compose up -d

# 대시보드
cd dashboard && npm run dev
```

## 주요 패턴

### Repository Pattern
Storage 레이어는 Repository Pattern을 사용합니다:
```kotlin
interface Repository<T, ID> {
    fun findById(id: ID): T?
    fun findAll(page: PageRequest): Page<T>
    fun save(entity: T): T
    fun delete(id: ID): Boolean
}
```

### Plugin System
플러그인은 `Plugin` 인터페이스 구현:
```kotlin
interface Plugin {
    val id: String
    val name: String
    suspend fun initialize(config: Map<String, String>)
    suspend fun execute(command: String, args: Map<String, Any>): PluginResult
    fun shouldHandle(message: String): Boolean
}
```

### Rate Limiting
다차원 Rate Limiting 지원:
- 시간 기반: RPM, RPH, RPD
- 리소스 기반: 토큰, 비용
- 범위 기반: 사용자, 프로젝트, 에이전트, 모델별

## 자주 수정하는 파일

| 작업 | 파일 |
|------|------|
| 에이전트 추가 | `claude-flow-core/routing/AgentRouter.kt` |
| API 엔드포인트 | `claude-flow-api/rest/*Controller.kt` |
| 플러그인 추가 | `claude-flow-core/plugin/` |
| 대시보드 페이지 | `dashboard/src/pages/` |
| n8n 워크플로우 | `docker-compose/n8n-workflows/` |

## 환경 변수

```bash
# 필수
SLACK_APP_TOKEN=xapp-xxx
SLACK_BOT_TOKEN=xoxb-xxx
SLACK_SIGNING_SECRET=xxx

# 선택 (GitLab 연동)
GITLAB_URL=https://gitlab.example.com
GITLAB_TOKEN=glpat-xxx

# 선택 (Jira 연동)
JIRA_URL=https://your-company.atlassian.net
JIRA_EMAIL=your-email@company.com
JIRA_API_TOKEN=xxx

# 선택 (n8n 연동)
N8N_URL=http://localhost:5678
N8N_EMAIL=admin@local.dev
N8N_PASSWORD=your-password
```

## Claude Code 통합

이 프로젝트는 Claude Code와 긴밀하게 통합됩니다:

### Slash Commands
- `/health` - 시스템 상태 확인
- `/metrics [period]` - 분석 대시보드
- `/agents` - 에이전트 목록
- `/user-context <user-id>` - 사용자 컨텍스트 조회
- `/gitlab-mr <project> <mr>` - MR 리뷰
- `/n8n <command>` - n8n 워크플로우 관리 및 자동 생성
- `/jira <command>` - Jira 이슈 관리

### Hooks
- `enrich-context.sh` - 사용자 컨텍스트 주입
- `validate-bash.sh` - Bash 명령어 검증
- `log-operation.py` - 작업 로깅

## 문서 자동 업데이트

이 프로젝트의 문서는 Claude Code Hooks와 Rules를 통해 자동으로 관리됩니다.

### 자동화 시스템 구성

```
코드 변경 (Edit/Write)
    ↓
[PostToolUse Hook] → doc-sync.sh
    ↓
파일 타입 감지 (Repository/Plugin/Controller 등)
    ↓
문서 동기화 필요 여부 판단
    ↓
알림 또는 자동 업데이트
```

### 관련 파일
| 파일 | 역할 |
|------|------|
| `.claude/hooks/doc-sync.sh` | 코드 변경 감지 및 문서 업데이트 알림 |
| `.claude/commands/sync-docs.md` | `/sync-docs` 명령으로 문서 동기화 |
| `.claude/rules/documentation.md` | 파일 패턴별 문서화 규칙 정의 |
| `docs/ARCHITECTURE.md` | Mermaid 아키텍처 다이어그램 |

### 자동 감지 패턴

| 파일 패턴 | 업데이트 대상 |
|-----------|---------------|
| `*/storage/repository/*.kt` | CLAUDE.md 모듈 구조, ARCHITECTURE.md ER 다이어그램 |
| `*/plugin/*.kt` | CLAUDE.md 모듈 구조, ARCHITECTURE.md 클래스 다이어그램 |
| `*/routing/*.kt` | CLAUDE.md 모듈 구조, ARCHITECTURE.md 라우팅 다이어그램 |
| `*/rest/*Controller.kt` | CLAUDE.md 자주 수정하는 파일, README.md API 테이블 |
| `build.gradle.kts` | CLAUDE.md 기술 스택 |
| `docker-compose/*.yml` | ARCHITECTURE.md 배포 다이어그램 |

### 문서 동기화 명령

```bash
# 문서 동기화 상태 확인 및 업데이트
/sync-docs

# 전체 문서 검토 (수동 체크리스트)
/update-docs
```

### Hook 동작 방식

1. **PostToolUse Hook**: 파일 편집 후 `doc-sync.sh` 실행
2. **파일 타입 감지**: 경로 패턴으로 Repository/Plugin/Controller 등 분류
3. **문서 확인**: 해당 클래스가 CLAUDE.md/ARCHITECTURE.md에 있는지 확인
4. **알림**: 누락된 경우 터미널에 업데이트 필요 알림 표시
5. **상태 저장**: `/tmp/claude-flow-doc-sync-state.json`에 대기 항목 기록

### Best Practices

1. **새 컴포넌트 추가 시**: Hook 알림 확인 후 `/sync-docs` 실행
2. **아키텍처 변경 시**: `docs/ARCHITECTURE.md` Mermaid 다이어그램 직접 수정
3. **API 변경 시**: README.md API 테이블과 ARCHITECTURE.md 동시 업데이트
4. **대규모 리팩토링 후**: `/sync-docs`로 전체 동기화 상태 확인
