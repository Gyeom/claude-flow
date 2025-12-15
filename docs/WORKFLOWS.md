# n8n Workflows Guide

Claude Flow uses n8n as its workflow engine for flexible event handling and automation. This document describes the available workflows and how to customize them.

## Overview

Workflows are stored in `docker-compose/n8n-workflows/` and automatically loaded when n8n starts.

```
docker-compose/n8n-workflows/
├── slack-mention-handler.json      # Handle @claude mentions
├── slack-feedback-handler.json     # Process thumbs up/down reactions
├── slack-action-handler.json       # Handle emoji action triggers
└── ... (more workflows)
```

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

### 4. GitLab MR Review

**Trigger**: Scheduled or webhook
**Purpose**: Automated code review for GitLab merge requests

**Features**:
- Fetch MR diff via GitLab API
- Analyze code changes with Claude
- Post review comments on GitLab
- Support for `ai:review` label trigger

**Configuration**:
```
GITLAB_URL=https://gitlab.example.com
GITLAB_TOKEN=glpat-xxx
```

### 5. Daily Report

**Trigger**: Scheduled (configurable, default: 9 AM weekdays)
**Purpose**: Generate daily activity summary

**Includes**:
- Execution statistics
- Success/failure rates
- Top users
- Common issues

## Creating Custom Workflows

### 1. Access n8n Editor

Navigate to `http://localhost:5678` and log in with:
- Email: `admin@local.dev`
- Password: `Localdev123`

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
# n8n
N8N_BASIC_AUTH_USER=admin@local.dev
N8N_BASIC_AUTH_PASSWORD=Localdev123

# Webhook base URL
N8N_WEBHOOK_URL=http://localhost:5678

# External integrations
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
