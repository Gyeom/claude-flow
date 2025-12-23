---
description: "Manage React dashboard (start/stop/status)"
---

# Dashboard Management Command

Claude Flow 대시보드(React)를 관리합니다.

## Arguments
- `$ARGUMENTS` - 액션: start, stop, status, build (기본: status)

## Instructions

### 1. 액션별 동작

**status (기본)**
```bash
# 대시보드 프로세스 확인
lsof -i :5173 | head -3
# 접속 테스트
curl -s -o /dev/null -w "%{http_code}" http://localhost:5173/ 2>/dev/null || echo "Not running"
```

**start**
```bash
PROJECT_ROOT="${CLAUDE_FLOW_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$PROJECT_ROOT/dashboard"
npm run dev &
```
- 백그라운드로 실행
- 약 3초 후 http://localhost:5173 에서 접속 가능
- Vite dev server 사용

**stop**
```bash
pkill -f "vite"
# 또는
lsof -ti:5173 | xargs kill -9
```

**build**
```bash
PROJECT_ROOT="${CLAUDE_FLOW_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$PROJECT_ROOT/dashboard"
npm run build
```
- 프로덕션 빌드 생성
- dist/ 디렉토리에 출력

### 2. 주의사항
- 대시보드는 백엔드 API(localhost:8080)에 의존
- 백엔드가 실행 중이어야 데이터가 표시됨
- 개발 모드에서는 HMR(Hot Module Replacement) 지원
