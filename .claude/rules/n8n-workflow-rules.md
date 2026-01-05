# n8n 워크플로우 작성 규칙

n8n 워크플로우 작성 시 반드시 준수해야 하는 규칙입니다.

## 핵심 원칙: 외부 서비스는 claude-flow API를 통해 호출

> **n8n 워크플로우에서 외부 서비스(GitLab, Jira, GitHub 등)를 직접 호출하지 마세요.**
> **항상 claude-flow API를 통해 프록시 호출해야 합니다.**

### 이유

n8n은 Docker 컨테이너에서 실행되므로:
1. **DNS 문제**: 회사 내부 도메인(예: `gitlab.company.com`)을 resolve하지 못할 수 있음
2. **네트워크 격리**: Docker bridge 네트워크가 호스트 네트워크와 다름
3. **토큰 관리**: claude-flow에서 토큰을 중앙 관리하는 것이 보안상 유리
4. **로깅/모니터링**: API 호출을 한 곳에서 추적 가능

### 잘못된 예시 (금지)

```json
{
  "parameters": {
    "method": "GET",
    "url": "={{ $env.GITLAB_URL }}/api/v4/projects/xxx/merge_requests",
    "sendHeaders": true,
    "headerParameters": {
      "parameters": [
        { "name": "PRIVATE-TOKEN", "value": "={{ $env.GITLAB_TOKEN }}" }
      ]
    }
  }
}
```

### 올바른 예시 (권장)

```json
{
  "parameters": {
    "method": "GET",
    "url": "={{ $env.CLAUDE_FLOW_API_URL }}/api/v1/plugins/gitlab/mrs/reviewed",
    "sendQuery": true,
    "queryParameters": {
      "parameters": [
        { "name": "project", "value": "={{ $json.gitlabPath }}" }
      ]
    }
  }
}
```

## 사용 가능한 프록시 API 목록

### GitLab API (`/api/v1/plugins/gitlab/...`)

| 용도 | API 경로 | 비고 |
|------|----------|------|
| MR 목록 | `GET /plugins/gitlab/mrs?project=xxx` | |
| MR 상세 | `GET /plugins/gitlab/mrs/{project}/{mrId}` | |
| AI 리뷰된 MR | `GET /plugins/gitlab/mrs/reviewed?project=xxx&days=3` | 피드백 폴러용 |
| MR 노트 | `GET /plugins/gitlab/mrs/{project}/{mrId}/notes` | |
| 노트 이모지 | `GET /plugins/gitlab/mrs/{project}/{mrId}/notes/{noteId}/emojis` | |
| 파이프라인 | `GET /plugins/gitlab/pipelines/{project}` | |
| MR 검색 | `GET /plugins/gitlab/mrs/search?issueKey=xxx` | Jira 이슈로 검색 |

### Jira API (`/api/v1/plugins/jira/...`)

| 용도 | API 경로 | 비고 |
|------|----------|------|
| 이슈 조회 | `GET /plugins/jira/issues/{issueKey}` | |
| 이슈 검색 | `GET /plugins/jira/search?jql=xxx` | |
| 이슈 생성 | `POST /plugins/jira/issues` | |
| 상태 변경 | `POST /plugins/jira/issues/{issueKey}/transition` | |
| 댓글 추가 | `POST /plugins/jira/issues/{issueKey}/comments` | |

### 프로젝트 API (`/api/v1/projects/...`)

| 용도 | API 경로 | 비고 |
|------|----------|------|
| GitLab 연동 프로젝트 | `GET /projects/gitlab-enabled` | |
| 알람 채널 매핑 | `GET /projects/by-alert-channel/{channelId}` | |

## 워크플로우 작성 체크리스트

워크플로우를 작성하거나 수정할 때 다음을 확인하세요:

- [ ] `$env.GITLAB_URL` 또는 `$env.JIRA_URL`을 직접 사용하지 않았는가?
- [ ] 모든 외부 API 호출이 `$env.CLAUDE_FLOW_API_URL`을 통하는가?
- [ ] `PRIVATE-TOKEN`, `Authorization` 헤더를 직접 설정하지 않았는가?
- [ ] API 응답 형식이 `{ success: true, data: [...] }` 임을 고려했는가?

## API 응답 처리

claude-flow API 응답은 다음 형식입니다:

```json
{
  "success": true,
  "data": [...],
  "message": "..."
}
```

n8n Code 노드에서 데이터 추출:

```javascript
const response = $input.first().json;
if (response.success && response.data) {
  return response.data.map(item => ({ json: item }));
}
return [];
```

## 새 API가 필요한 경우

필요한 프록시 API가 없으면:
1. `claude-flow-core/plugin/` 해당 플러그인에 기능 추가
2. `claude-flow-api/rest/PluginController.kt`에 엔드포인트 추가
3. 이 문서에 API 목록 업데이트

## 검증 방법

워크플로우 JSON 파일에서 직접 호출 패턴 검사:

```bash
# GitLab/Jira 직접 호출 패턴 검사
grep -l "GITLAB_URL\|GITLAB_TOKEN\|JIRA_URL\|JIRA_API_TOKEN" docker-compose/n8n-workflows/*.json
```

결과가 있으면 해당 워크플로우 수정 필요.
