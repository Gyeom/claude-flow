---
description: "Backup n8n workflows to JSON files"
---

# n8n 워크플로우 백업

n8n에서 작업한 워크플로우를 JSON 파일로 백업합니다.

## 백업 실행

```bash
./start.sh --backup
```

또는 직접:
```bash
cd docker-compose/scripts
N8N_URL=http://localhost:5678 node backup-workflows.mjs --output-dir ../n8n-backup/$(date +%Y%m%d)
```

## 백업 위치

- 기본: `docker-compose/n8n-backup/<timestamp>/`
- 각 워크플로우는 개별 JSON 파일로 저장

## 자동 백업 설정 (권장)

크론탭에 추가:
```bash
# 매일 새벽 3시에 백업
0 3 * * * /path/to/claude-flow/start.sh --backup
```

## 복원 방법

백업된 JSON 파일을 `docker-compose/n8n-workflows/`에 복사하면
다음 시작 시 자동으로 동기화됩니다.
