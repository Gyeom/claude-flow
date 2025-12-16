# Deploy Command

Claude Flow를 빌드하고 배포합니다.

## Arguments
- `$ARGUMENTS` - 배포 타겟: all, backend, dashboard, docker (기본: all)

## Instructions

### 1. 배포 타겟별 동작

**all (기본)**
전체 시스템을 빌드하고 배포합니다:
1. 백엔드 빌드
2. 대시보드 빌드
3. 서비스 재시작

**backend**
백엔드만 빌드하고 재시작합니다:

```bash
# 1. 테스트 실행
./gradlew test

# 2. 빌드
./gradlew build

# 3. 기존 서비스 중지
lsof -ti:8080 | xargs kill -9 2>/dev/null || true

# 4. 새 버전 실행
source docker-compose/.env
SLACK_APP_TOKEN="$SLACK_APP_TOKEN" SLACK_BOT_TOKEN="$SLACK_BOT_TOKEN" \
  ./gradlew :claude-flow-app:bootRun > /tmp/claude-flow.log 2>&1 &

# 5. Health check 대기
for i in {1..30}; do
  curl -s http://localhost:8080/api/v1/health && break
  sleep 2
done
```

**dashboard**
대시보드만 빌드합니다:

```bash
cd dashboard

# 1. 의존성 설치 (필요시)
npm install

# 2. 타입 체크
npm run typecheck 2>/dev/null || true

# 3. 빌드
npm run build

# 4. 개발 서버 재시작 (선택)
pkill -f "vite" 2>/dev/null || true
npm run dev &
```

**docker**
Docker 이미지를 빌드하고 컨테이너를 재시작합니다:

```bash
cd docker-compose

# 1. 이미지 빌드
docker-compose build

# 2. 서비스 재시작
docker-compose down
docker-compose up -d

# 3. 상태 확인
docker-compose ps
```

### 2. 배포 전 체크리스트

배포 전 자동으로 확인하는 항목:
- [ ] 모든 테스트 통과
- [ ] 빌드 성공
- [ ] 커밋되지 않은 변경 사항 경고

### 3. 배포 후 확인

```bash
# 백엔드 상태
curl -s http://localhost:8080/api/v1/health

# 대시보드 접속
echo "Dashboard: http://localhost:3000"

# 로그 확인
tail -20 /tmp/claude-flow.log
```

### 4. 롤백

문제 발생 시 이전 버전으로 롤백:

```bash
# Git으로 이전 커밋 체크아웃
git checkout HEAD~1

# 재배포
./gradlew build
./gradlew :claude-flow-app:bootRun
```

### 5. 환경별 배포

| 환경 | 설명 | 명령 |
|------|------|------|
| local | 로컬 개발 | `/deploy` |
| staging | 스테이징 | Docker 배포 |
| production | 프로덕션 | CI/CD 파이프라인 |
