# n8n Workflow Management

n8n 워크플로우 관리 및 자동 생성을 위한 명령입니다.

## Usage

```
/n8n <command> [arguments]
```

## Commands

### 워크플로우 관리

| Command | Description | Example |
|---------|-------------|---------|
| `list` | 모든 워크플로우 목록 조회 | `/n8n list` |
| `sync` | JSON 파일과 n8n DB 동기화 | `/n8n sync` |
| `get <id>` | 특정 워크플로우 상세 조회 | `/n8n get 1` |
| `run <id>` | 워크플로우 수동 실행 | `/n8n run 1` |
| `activate <id>` | 워크플로우 활성화 | `/n8n activate 1` |
| `deactivate <id>` | 워크플로우 비활성화 | `/n8n deactivate 1` |
| `delete <id>` | 워크플로우 삭제 | `/n8n delete 1` |

### 워크플로우 생성

| Command | Description | Example |
|---------|-------------|---------|
| `create <설명>` | 자연어로 워크플로우 생성 | `/n8n create Slack 메시지 받으면 AI로 응답` |
| `template <id>` | 템플릿 기반 생성 | `/n8n template slack-mention-handler` |
| `templates` | 사용 가능한 템플릿 목록 | `/n8n templates` |
| `import <path>` | JSON 파일에서 가져오기 | `/n8n import /path/to/workflow.json` |

### 실행 내역

| Command | Description | Example |
|---------|-------------|---------|
| `executions [limit]` | 최근 실행 내역 조회 | `/n8n executions 50` |

## Templates

다음 템플릿을 사용할 수 있습니다:

- `slack-mention-handler` - Slack 멘션 수신 → Claude 처리 → 응답 전송
- `gitlab-mr-review` - GitLab MR 생성 시 자동 코드 리뷰
- `daily-report` - 매일 아침 Jira/GitLab 요약 Slack 전송
- `jira-auto-fix` - 미해결 이슈 자동 분석 및 해결 제안
- `slack-feedback-handler` - Slack 리액션 피드백 수집
- `webhook-to-slack` - Webhook → Slack 알림
- `schedule-api-call` - 주기적 API 호출

## Examples

### 1. 워크플로우 목록 확인

```
/n8n list
```

### 2. 자연어로 워크플로우 생성

```
/n8n create 매일 아침 9시에 Jira 미해결 이슈 요약을 Slack #dev 채널로 전송
```

```
/n8n create GitLab에 새 MR이 생성되면 Claude로 코드 리뷰하고 댓글 작성
```

```
/n8n create Slack에서 @bot 멘션하면 GPT로 응답 생성해서 스레드에 답장
```

### 3. 템플릿으로 빠르게 생성

```
/n8n template slack-mention-handler
```

### 4. 실행 내역 확인

```
/n8n executions 20
```

## Environment Variables

n8n 연동에 필요한 환경변수:

```bash
N8N_URL=http://localhost:5678
N8N_EMAIL=admin@local.dev
N8N_PASSWORD=your-password
```

---

## Instructions

### sync 명령 실행 방법

`/n8n sync` 명령이 실행되면 다음 작업을 수행합니다:

1. **n8n 로그인**: admin@local.dev / Localdev123 (기본값)
2. **JSON 파일 스캔**: `docker-compose/n8n-workflows/*.json` 파일 목록 조회
3. **n8n DB 조회**: 현재 n8n에 등록된 워크플로우 목록 조회
4. **동기화 실행**:
   - JSON에 있고 n8n에 없는 워크플로우 → **생성**
   - JSON에 있고 n8n에 있는 워크플로우 → **업데이트** (nodes, connections 동기화)
   - n8n에만 있는 워크플로우 → 유지 (삭제하지 않음)

```bash
# n8n 로그인
curl -s -X POST http://localhost:5678/rest/login \
  -H "Content-Type: application/json" \
  -d '{"emailOrLdapLoginId":"admin@local.dev","password":"Localdev123"}' \
  -c /tmp/n8n-cookie.txt

# JSON 파일 목록
ls docker-compose/n8n-workflows/*.json

# 각 파일에 대해 동기화
for file in docker-compose/n8n-workflows/*.json; do
  # 워크플로우 이름으로 기존 ID 조회
  # 있으면 PATCH, 없으면 POST
done
```

### 파일명 ↔ 워크플로우 이름 매핑

| JSON 파일 | 워크플로우 이름 |
|-----------|----------------|
| slack-mention-handler.json | Slack Mention Handler |
| slack-mr-review.json | Slack MR Review Handler |
| slack-action-handler.json | Slack Action Handler |
| slack-feedback-handler.json | Slack Feedback Handler |
| user-context-handler.json | User Context Handler |
| alert-channel-monitor.json | Alert Channel Monitor |
| alert-to-mr-pipeline.json | Alert to MR Pipeline |

---

$ARGUMENTS
