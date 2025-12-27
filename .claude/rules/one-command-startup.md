# 원커맨드 시작 원칙

> 이 프로젝트는 **로컬 개발 환경**용입니다.

모든 기능 개발 시 반드시 준수해야 하는 핵심 원칙입니다.

## 원칙

```
/start 하나로 → 모든 서비스 구동 → 설정 자동 로드 → 즉시 사용 가능
```

## 새 기능/서비스 추가 시 체크리스트

### 1. 시작 통합

- [ ] `/start` 명령으로 자동 시작되는가?
- [ ] `.claude/commands/start.md`에 시작 로직이 포함되어 있는가?
- [ ] 의존성 순서가 올바른가? (인프라 → 앱)

### 2. 설정 자동 로드

- [ ] 필수 설정이 `docker-compose/.env`에 정의되어 있는가?
- [ ] 프로젝트 설정이 `config/projects.json`에 정의되어 있는가?
- [ ] Spring 설정이 `application.yml`에 정의되어 있는가?
- [ ] 환경변수 누락 시 적절한 기본값이 있는가?

### 3. 재시작 시 최신화

- [ ] 설정 변경 후 재시작하면 자동 반영되는가?
- [ ] n8n 워크플로우가 `sync-workflows.mjs`로 동기화되는가?
- [ ] `projects.json` 변경이 DB에 자동 반영되는가?

### 4. 에러 처리

- [ ] 설정 누락 시 명확한 에러 메시지가 출력되는가?
- [ ] 포트 충돌 시 감지 및 안내가 되는가?
- [ ] 의존 서비스 미실행 시 재시도 또는 안내가 있는가?

## 설정 파일 역할

| 파일 | 용도 | 로드 시점 |
|------|------|----------|
| `docker-compose/.env` | 환경변수 (토큰, URL 등) | Gradle bootRun |
| `config/projects.json` | 프로젝트 정의 (GitLab, 별칭) | Storage 초기화 |
| `application.yml` | Spring 앱 설정 | Spring Boot 시작 |
| `n8n-workflows/*.json` | 워크플로우 정의 | n8n 재시작 |

## 금지 사항

- 수동 설정 요구 (예: "이 파일을 직접 수정하세요")
- 별도 초기화 스크립트 실행 요구 (예: "먼저 setup.sh를 실행하세요")
- 하드코딩된 경로나 값 (환경변수 또는 설정 파일 사용)
- 재시작 후 수동 동기화 필요 (예: "설정 변경 후 DB를 직접 업데이트하세요")

## 예시

### 새 외부 서비스 연동 추가

```
1. docker-compose/.env.example에 필수 환경변수 추가
   NEW_SERVICE_URL=
   NEW_SERVICE_TOKEN=

2. application.yml에 설정 바인딩 추가
   claude-flow:
     new-service:
       url: ${NEW_SERVICE_URL:}
       token: ${NEW_SERVICE_TOKEN:}

3. 환경변수 누락 시 로그 경고 추가
   if (newServiceUrl.isBlank()) {
       logger.warn { "NEW_SERVICE_URL not configured, feature disabled" }
   }
```

### 새 워크플로우 추가

```
1. docker-compose/n8n-workflows/에 JSON 파일 추가
2. sync-workflows.mjs가 자동으로 n8n에 동기화
3. /start 또는 n8n 재시작으로 활성화
```

## 참고 문서

- `docs/STARTUP.md` - 전체 시작/초기화 메커니즘
- `CLAUDE.md` - 프로젝트 핵심 가치 및 원칙
