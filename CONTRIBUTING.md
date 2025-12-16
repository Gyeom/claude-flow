# Contributing to Claude Flow

Claude Flow에 기여해 주셔서 감사합니다!

## 개발 환경 설정

### 필수 요구사항

- **Java 21** 이상
- **Node.js 18** 이상
- **Docker** & **Docker Compose**
- **Claude CLI** (claude.ai/code에서 설치)

### 로컬 개발 시작

```bash
# 1. 저장소 클론
git clone https://github.com/your-org/claude-flow.git
cd claude-flow

# 2. 환경 설정
cp docker-compose/.env.example docker-compose/.env
# .env 파일에 Slack 토큰 설정

# 3. 의존성 설치
./gradlew build
cd dashboard && npm install && cd ..

# 4. 서비스 시작
./start.sh
```

### Claude Code와 함께 개발

Claude Code에서 이 프로젝트를 열면 자동으로 설정이 적용됩니다:

```bash
cd claude-flow
claude
```

`.claude/settings.json`에 기본 권한과 훅이 설정되어 있습니다.

## 프로젝트 구조

```
claude-flow/
├── claude-flow-core/    # 핵심 도메인 로직 (Kotlin)
├── claude-flow-api/     # REST API (Spring WebFlux)
├── claude-flow-app/     # Spring Boot 애플리케이션
├── claude-flow-executor/# Claude CLI 래퍼
├── dashboard/           # React 대시보드
├── docker-compose/      # Docker 설정 및 n8n 워크플로우
└── .claude/             # Claude Code 설정
```

## 코딩 컨벤션

### Kotlin
- 공식 [Kotlin 스타일 가이드](https://kotlinlang.org/docs/coding-conventions.html) 준수
- 한글 KDoc 주석 권장
- 테스트는 Kotest (DescribeSpec 스타일)

### TypeScript/React
- ESLint/Prettier 설정 준수
- 함수형 컴포넌트 사용

## 테스트

```bash
# 전체 테스트
./gradlew test

# 특정 모듈 테스트
./gradlew :claude-flow-core:test

# 대시보드 테스트
cd dashboard && npm test
```

## Pull Request 가이드

1. **브랜치 생성**: `feature/기능명` 또는 `fix/버그명`
2. **커밋 메시지**: [Conventional Commits](https://www.conventionalcommits.org/) 형식
   ```
   feat: 새로운 기능 추가
   fix: 버그 수정
   docs: 문서 수정
   refactor: 리팩토링
   test: 테스트 추가/수정
   ```
3. **PR 제목**: 변경 사항을 명확히 설명
4. **테스트 통과**: CI 파이프라인 통과 필수

## 이슈 리포트

버그 리포트나 기능 요청은 GitHub Issues를 사용해 주세요:

- **버그 리포트**: 재현 단계, 예상 결과, 실제 결과 포함
- **기능 요청**: 유스케이스와 기대 효과 설명

## 라이선스

이 프로젝트는 MIT 라이선스를 따릅니다. 기여하시는 코드도 동일 라이선스가 적용됩니다.

## 새 환경에서 실행 체크리스트

새 머신/환경에서 처음 실행할 때 확인해야 할 사항들입니다:

### 필수 의존성

- [ ] **Docker**: `docker --version` (20.10 이상)
- [ ] **Docker Compose**: `docker compose version` (v2.0 이상)
- [ ] **Java 21**: `java -version`
- [ ] **Node.js**: `node --version` (18 이상)
- [ ] **Claude CLI**: `claude --version` (최신 버전)

### 초기 설정

1. **환경 파일 생성**
   ```bash
   cp docker-compose/.env.example docker-compose/.env
   ```

2. **필수 환경 변수 설정** (`.env` 파일 편집)
   - [ ] `SLACK_APP_TOKEN` - Slack App Token (xapp-xxx)
   - [ ] `SLACK_BOT_TOKEN` - Slack Bot Token (xoxb-xxx)
   - [ ] `SLACK_SIGNING_SECRET` - Slack Signing Secret

3. **선택 환경 변수 설정**
   - [ ] `GITLAB_URL`, `GITLAB_TOKEN` - GitLab MR 리뷰 기능
   - [ ] `JIRA_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN` - Jira 연동
   - [ ] `N8N_DEFAULT_EMAIL`, `N8N_DEFAULT_PASSWORD` - n8n 계정 (프로덕션용)

### 실행 테스트

```bash
# 1. 서비스 시작
./start.sh

# 2. 헬스 체크
curl http://localhost:8080/api/v1/health

# 3. n8n 접속 확인
curl -s -o /dev/null -w "%{http_code}" http://localhost:5678/

# 4. Slack 테스트
# Slack에서 @claude-flow 멘션
```

### 문제 해결

- **포트 충돌**: `lsof -i :8080` 또는 `lsof -i :5678`로 확인
- **Docker 권한**: `sudo` 없이 실행하려면 docker 그룹에 사용자 추가
- **로그 확인**: `./start.sh --logs`

## 질문?

- GitHub Issues에 질문 남기기
- 문서: [CLAUDE.md](./CLAUDE.md)
