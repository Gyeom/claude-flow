# Claude Flow - AI Agent Context

이 문서는 Claude가 claude-flow 프로젝트를 이해하고 효과적으로 작업하기 위한 컨텍스트 문서입니다.

## 프로젝트 개요

**Claude Flow**는 Slack에서 Claude를 호출하고 GitLab MR 리뷰를 자동화하는 팀 내부 AI 에이전트 플랫폼입니다.

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
│   │   └── PluginConfigManager.kt  # 설정 관리
│   ├── ratelimit/          # Rate Limiting
│   │   ├── RateLimitPolicy.kt      # 정책 정의
│   │   └── AdvancedRateLimiter.kt  # 다차원 제한
│   ├── session/            # 세션 관리
│   │   └── SessionContextBuilder.kt # 컨텍스트 빌더
│   └── analytics/          # 분석
│       └── Analytics.kt            # 통계 수집
│
├── claude-flow-executor/   # Claude CLI 래퍼
├── claude-flow-api/        # REST API (Spring WebFlux)
├── claude-flow-app/        # Spring Boot 앱 (Slack 연동)
├── dashboard/              # React 대시보드
├── docker-compose/         # Docker 설정, n8n 워크플로우
└── config/                 # 설정 파일
    └── plugins.toml        # 플러그인 설정
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
```

## Claude Code 통합

이 프로젝트는 Claude Code와 긴밀하게 통합됩니다:

### Slash Commands
- `/health` - 시스템 상태 확인
- `/metrics [period]` - 분석 대시보드
- `/agents` - 에이전트 목록
- `/user-context <user-id>` - 사용자 컨텍스트 조회
- `/gitlab-mr <project> <mr>` - MR 리뷰

### Hooks
- `enrich-context.sh` - 사용자 컨텍스트 주입
- `validate-bash.sh` - Bash 명령어 검증
- `log-operation.py` - 작업 로깅

## 문서 자동 업데이트

이 프로젝트의 문서는 Claude Code에 의해 자동으로 업데이트됩니다:

1. **코드 변경 시**: 새 컴포넌트 추가/변경 시 이 문서 업데이트
2. **아키텍처 변경 시**: 모듈 구조 다이어그램 업데이트
3. **API 변경 시**: README.md의 API 섹션 업데이트

### 자동화 규칙
- 새 Repository 추가 → 모듈 구조 섹션 업데이트
- 새 Plugin 추가 → 플러그인 시스템 섹션 업데이트
- 새 API 엔드포인트 → README.md API 테이블 업데이트
- 테스트 추가 → 테스트 실행 예제 업데이트
