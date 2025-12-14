---
name: gitlab
description: GitLab API operations - MR review, pipeline status, issue management
---

# GitLab Integration Skill

This skill provides GitLab API capabilities for Claude Flow.

## Configuration

GitLab credentials are loaded from environment:
- `GITLAB_URL`: GitLab server URL (e.g., https://gitlab.example.com)
- `GITLAB_TOKEN`: Personal Access Token with api scope

## Available Operations

### 1. Get Merge Request Details

```bash
# Get MR information
gitlab_get_mr() {
  local project="$1"
  local mr_iid="$2"
  curl -s -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    "${GITLAB_URL}/api/v4/projects/${project}/merge_requests/${mr_iid}"
}
```

### 2. Get MR Changes (Diff)

```bash
# Get MR diff
gitlab_get_mr_changes() {
  local project="$1"
  local mr_iid="$2"
  curl -s -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    "${GITLAB_URL}/api/v4/projects/${project}/merge_requests/${mr_iid}/changes"
}
```

### 3. Post MR Comment

```bash
# Add comment to MR
gitlab_comment_mr() {
  local project="$1"
  local mr_iid="$2"
  local body="$3"
  curl -s -X POST -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"body\": \"$body\"}" \
    "${GITLAB_URL}/api/v4/projects/${project}/merge_requests/${mr_iid}/notes"
}
```

### 4. Get Pipeline Status

```bash
# Get pipeline status for MR
gitlab_get_pipeline() {
  local project="$1"
  local mr_iid="$2"
  curl -s -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    "${GITLAB_URL}/api/v4/projects/${project}/merge_requests/${mr_iid}/pipelines"
}
```

### 5. Approve/Unapprove MR

```bash
# Approve MR
gitlab_approve_mr() {
  local project="$1"
  local mr_iid="$2"
  curl -s -X POST -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    "${GITLAB_URL}/api/v4/projects/${project}/merge_requests/${mr_iid}/approve"
}
```

## Usage Examples

When reviewing a GitLab MR:

1. **Fetch MR details**: Use `gitlab_get_mr` to get title, description, author
2. **Get code changes**: Use `gitlab_get_mr_changes` for the diff
3. **Analyze changes**: Review code quality, security, best practices
4. **Post feedback**: Use `gitlab_comment_mr` to leave review comments
5. **Check CI/CD**: Use `gitlab_get_pipeline` to verify tests pass

## Response Format

Always structure GitLab review responses as:

```json
{
  "summary": "Brief overall assessment",
  "issues": [
    {"severity": "critical|major|minor", "file": "path", "line": 42, "description": "..."}
  ],
  "suggestions": ["..."],
  "approval": "approved|changes_requested"
}
```
