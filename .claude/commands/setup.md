---
description: "One-click setup and run Claude Flow"
---

# Claude Flow 원클릭 설치 및 실행

사용자가 Claude Flow를 처음 설치하거나 실행하려고 합니다.

## 작업 순서

1. **의존성 확인**
   - Docker와 Docker Compose 설치 여부 확인
   - 미설치 시 안내 메시지 제공

2. **환경 설정**
   - `docker-compose/.env` 파일 존재 확인
   - 없으면 `.env.example`에서 복사
   - Slack 토큰이 설정되지 않았으면 설정 안내

3. **서비스 시작**
   ```bash
   cd docker-compose && docker compose up -d
   ```

4. **상태 확인**
   - 컨테이너 상태 확인
   - API 헬스체크 (http://localhost:8080/api/v1/health)
   - n8n 연결 확인 (http://localhost:5678)

5. **완료 메시지**
   - 접속 URL 안내
   - 다음 단계 안내

## 실행

다음 명령어를 실행하세요:

```bash
./start.sh
```

또는 RAG 기능과 함께:
```bash
./start.sh --with-rag
```

## 주요 환경변수

```
SLACK_APP_TOKEN=xapp-xxx    # Slack App Token (Socket Mode)
SLACK_BOT_TOKEN=xoxb-xxx    # Slack Bot Token
SLACK_SIGNING_SECRET=xxx    # Slack Signing Secret
```

## 트러블슈팅

- Docker가 실행 중인지 확인: `docker info`
- 포트 충돌 확인: `lsof -i :8080` / `lsof -i :5678`
- 로그 확인: `./start.sh --logs`
