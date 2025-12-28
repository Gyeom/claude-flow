# n8n Workflows Guide

Claude Flow uses n8n as its workflow engine for flexible event handling and automation. This document describes the available workflows and how to customize them.

> **Last Updated**: 2025-12-28

## Overview

Workflows are stored in `docker-compose/n8n-workflows/` and automatically loaded when n8n starts.

```
docker-compose/n8n-workflows/
├── slack-mention-handler.json      # Handle @claude mentions
├── slack-action-handler.json       # Handle emoji action triggers
├── slack-feedback-handler.json     # Process thumbs up/down reactions
├── scheduled-mr-review.json        # 5분마다 자동 MR 리뷰 (Opus)
├── gitlab-feedback-poller.json     # GitLab 이모지 피드백 수집
├── alert-channel-monitor.json      # 장애 알람 모니터링 (비활성)
└── alert-to-mr-pipeline.json       # 알람 → MR 생성 (비활성)
```

## Workflow Summary

| Workflow | Trigger | Model | Status |
|----------|---------|-------|--------|
| slack-mention-handler | Slack @멘션 | Sonnet/Opus | ✅ Active |
| slack-action-handler | Slack 이모지 | - | ✅ Active |
| slack-feedback-handler | 👍/👎 리액션 | - | ✅ Active |
| **scheduled-mr-review** | 5분 스케줄 | **Opus** | ✅ Active |
| **gitlab-feedback-poller** | 5분 스케줄 | - | ✅ Active |
| alert-channel-monitor | Slack 알람 | Haiku | ⏸️ Inactive |
| alert-to-mr-pipeline | 수동/자동 | Sonnet | ⏸️ Inactive |

## Core Workflows

### 1. Slack Mention Handler

**Trigger**: Webhook from Slack Socket Mode Bridge
**Purpose**: Process `@claude-flow` mentions in Slack

**Flow**:
1. Receive Slack mention event
2. Extract user context and conversation history
3. Route to appropriate agent
4. Execute Claude CLI
5. Send response back to Slack thread

**Webhook URL**: `POST /webhook/slack-mention`

**Payload**:
```json
{
  "event_type": "app_mention",
  "channel": "C01234567",
  "user": "U01234567",
  "text": "@claude-flow help me with this code",
  "thread_ts": "1234567890.123456",
  "ts": "1234567890.123457"
}
```

### 2. Slack Feedback Handler

**Trigger**: Webhook from reaction events
**Purpose**: Track user satisfaction via emoji reactions

**Supported Reactions**:
- `:+1:` (thumbsup) - Positive feedback
- `:-1:` (thumbsdown) - Negative feedback

**Flow**:
1. Receive reaction event
2. Find associated execution via `reply_ts`
3. Save feedback to database
4. Update analytics

**Webhook URL**: `POST /webhook/slack-feedback`

### 3. Slack Action Handler

**Trigger**: Webhook from specific emoji reactions
**Purpose**: Trigger actions based on emoji reactions

**Supported Actions**:
| Emoji | Action | Description |
|-------|--------|-------------|
| `:jira:` | create_ticket | Create JIRA ticket |
| `:wrench:` | fix_code | Request code fix |
| `:memo:` | summarize | Summarize content |
| `:eyes:` | review | Request code review |
| `:rocket:` | deploy | Request deployment |
| `:one:` | select_option | Select option 1 |
| `:two:` | select_option | Select option 2 |
| `:three:` | select_option | Select option 3 |

**Webhook URL**: `POST /webhook/slack-action`

### 4. Scheduled MR Review (scheduled-mr-review.json)

**Trigger**: Schedule (every 5 minutes)
**Model**: **Claude Opus** (high-quality reviews)
**Purpose**: Automatically review new GitLab merge requests

**Flow**:
```
5분마다 실행
    ↓
GitLab 프로젝트 목록 조회 (/api/v1/projects/gitlab-enabled)
    ↓
각 프로젝트별 MR 목록 조회
    ↓
필터링:
  - target_branch = develop
  - ai-review::done, ai-review::skip 라벨 없음
    ↓
MR 상세 정보 + 컨텍스트 조회 (/api/v1/mr-review/context)
    ↓
Chat API 호출 (agentId: code-reviewer, Opus 모델)
    ↓
GitLab 코멘트로 리뷰 결과 게시
    ↓
ai-review::done 라벨 적용
    ↓
리뷰 레코드 저장 (/api/v1/feedback/gitlab-review)
```

**Configuration**:
```bash
GITLAB_URL=https://gitlab.example.com
GITLAB_TOKEN=glpat-xxx
```

### 5. GitLab Feedback Poller (gitlab-feedback-poller.json)

**Trigger**: Schedule (every 5 minutes)
**Purpose**: Collect emoji feedback on AI review comments in GitLab

**Flow**:
```
5분마다 실행
    ↓
AI 리뷰 레코드 조회 (feedback 없는 것)
    ↓
GitLab 코멘트 이모지 조회
    ↓
👍/👎 이모지 수집 → feedback 저장
    ↓
피드백 학습 시스템 반영
```

**Supported Emojis**:
| Emoji | Feedback Type |
|-------|---------------|
| 👍, ❤️, 🎉, 🚀 | Positive |
| 👎, 😕, ❌ | Negative |

### 6. Alert Channel Monitor (alert-channel-monitor.json)

**Status**: ⏸️ Inactive (manually enable if needed)
**Trigger**: Scheduled monitoring
**Model**: Claude Haiku (fast classification)
**Purpose**: Monitor Slack alert channels for automated incident response

### 7. Alert to MR Pipeline (alert-to-mr-pipeline.json)

**Status**: ⏸️ Inactive (manually enable if needed)
**Trigger**: From alert-channel-monitor
**Model**: Claude Sonnet
**Purpose**: Automatically create Jira issues and GitLab MRs from alerts

## Creating Custom Workflows

### 1. Access n8n Editor

Navigate to `http://localhost:5678` and log in with credentials from your `.env` file:
- Email: `N8N_DEFAULT_EMAIL` (default: `admin@local.dev`)
- Password: `N8N_DEFAULT_PASSWORD` (default: `Localdev123`)

### 2. Create New Workflow

1. Click "Add Workflow"
2. Add a Webhook node as trigger
3. Add HTTP Request node to call Claude Flow API

**Example: Custom Notification Workflow**

```
[Webhook] → [Claude Flow Execute API] → [Slack Send Message]
```

### 3. Claude Flow API Integration

**Execute with Routing**:
```
POST http://claude-flow-app:8080/api/v1/execute-with-routing
Content-Type: application/json

{
  "prompt": "{{$node['Webhook'].json.text}}",
  "channel": "{{$node['Webhook'].json.channel}}",
  "userId": "{{$node['Webhook'].json.user}}",
  "threadTs": "{{$node['Webhook'].json.thread_ts}}"
}
```

**Send Slack Message**:
```
POST http://claude-flow-app:8080/api/v1/slack/message
Content-Type: application/json

{
  "channel": "{{$node['Webhook'].json.channel}}",
  "text": "{{$node['Execute'].json.result}}",
  "threadTs": "{{$node['Webhook'].json.thread_ts}}"
}
```

## Workflow Configuration

### Environment Variables

Set in `docker-compose/.env`:

```bash
# n8n admin credentials (change for production!)
N8N_DEFAULT_EMAIL=admin@local.dev
N8N_DEFAULT_PASSWORD=Localdev123

# Webhook base URL
N8N_WEBHOOK_URL=http://localhost:5678

# External integrations (configure as needed)
GITLAB_URL=https://gitlab.example.com
GITLAB_TOKEN=glpat-xxx
JIRA_URL=https://your-org.atlassian.net
JIRA_EMAIL=your-email@example.com
JIRA_API_TOKEN=xxx
```

### Workflow Settings

Each workflow can be configured via n8n editor:

- **Active/Inactive**: Enable or disable workflow
- **Timeout**: Set execution timeout
- **Retry**: Configure retry on failure
- **Error handling**: Define error workflow

## Debugging

### View Execution Logs

1. Open n8n at `http://localhost:5678`
2. Navigate to "Executions" in sidebar
3. Click on execution to see detailed logs

### Test Webhook Manually

```bash
curl -X POST http://localhost:5678/webhook/slack-mention \
  -H "Content-Type: application/json" \
  -d '{
    "event_type": "app_mention",
    "channel": "C01234567",
    "user": "U01234567",
    "text": "test message",
    "ts": "1234567890.123456"
  }'
```

### Check Workflow Status

```bash
# List all workflows
curl -s -b /tmp/n8n-cookie.txt http://localhost:5678/rest/workflows

# Check specific workflow
curl -s -b /tmp/n8n-cookie.txt http://localhost:5678/rest/workflows/{workflow_id}
```

## Best Practices

1. **Use Error Handling**: Always add error workflow for production
2. **Set Timeouts**: Prevent long-running executions
3. **Log Important Data**: Use Set node to log key information
4. **Version Control**: Export workflows and commit to git
5. **Test Locally**: Use manual webhook triggers for testing

## Troubleshooting

### Workflow Not Triggering

1. Check if workflow is active
2. Verify webhook URL is correct
3. Check n8n logs: `docker-compose logs n8n`

### API Connection Failed

1. Verify Claude Flow app is running
2. Check network connectivity between containers
3. Verify API endpoint URLs

### Slow Execution

1. Check Claude API rate limits
2. Monitor execution time in n8n
3. Consider adding caching for repeated queries
