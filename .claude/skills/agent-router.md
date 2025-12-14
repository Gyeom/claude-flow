---
name: agent-router
description: Smart agent routing and orchestration
---

# Agent Router Skill

This skill provides intelligent routing and multi-agent orchestration for Claude Flow.

## Routing Methods

Claude Flow uses a 4-layer routing cascade:

### 1. Keyword Matching (Fastest)
```bash
# Direct keyword/regex match
# Priority: Highest | Latency: ~1ms | Confidence: 1.0
route_keyword() {
  local prompt="$1"
  curl -s -X POST "http://localhost:8080/api/v1/route" \
    -H "Content-Type: application/json" \
    -d "{\"prompt\": \"$prompt\", \"method\": \"keyword\"}"
}
```

### 2. Pattern Matching (Fast)
```bash
# Regex pattern matching
# Priority: High | Latency: ~5ms | Confidence: 0.95
route_pattern() {
  local prompt="$1"
  curl -s -X POST "http://localhost:8080/api/v1/route" \
    -H "Content-Type: application/json" \
    -d "{\"prompt\": \"$prompt\", \"method\": \"pattern\"}"
}
```

### 3. Semantic Matching (Medium)
```bash
# Embedding-based similarity
# Priority: Medium | Latency: ~100ms | Confidence: 0.7-0.95
route_semantic() {
  local prompt="$1"
  curl -s -X POST "http://localhost:8080/api/v1/route" \
    -H "Content-Type: application/json" \
    -d "{\"prompt\": \"$prompt\", \"method\": \"semantic\"}"
}
```

### 4. LLM Classification (Fallback)
```bash
# Claude-based classification
# Priority: Low | Latency: ~2-5s | Confidence: variable
route_llm() {
  local prompt="$1"
  curl -s -X POST "http://localhost:8080/api/v1/route" \
    -H "Content-Type: application/json" \
    -d "{\"prompt\": \"$prompt\", \"method\": \"llm\"}"
}
```

## Agent Management

### List Agents
```bash
agents_list() {
  curl -s "http://localhost:8080/api/v1/agents" | \
    jq '.[] | {id, name, priority, enabled, keywords}'
}
```

### Get Agent Details
```bash
agent_get() {
  local agent_id="$1"
  curl -s "http://localhost:8080/api/v1/agents/${agent_id}"
}
```

### Create Agent
```bash
agent_create() {
  local agent_json="$1"
  curl -s -X POST "http://localhost:8080/api/v1/agents" \
    -H "Content-Type: application/json" \
    -d "$agent_json"
}
```

## Agent Configuration

```json
{
  "id": "code-reviewer",
  "name": "Code Reviewer",
  "description": "Reviews code for quality and security",
  "keywords": ["review", "mr", "pr", "/^!\\d+/"],
  "examples": [
    "Review this pull request",
    "Check this code for bugs"
  ],
  "systemPrompt": "You are a senior code reviewer...",
  "model": "claude-sonnet-4-20250514",
  "maxTokens": 8192,
  "allowedTools": ["Read", "Grep", "Glob"],
  "priority": 100,
  "enabled": true
}
```

## Routing Response

```json
{
  "agent": "code-reviewer",
  "instruction": "Focus on security vulnerabilities",
  "model": "claude-sonnet-4-20250514",
  "confidence": 0.95,
  "method": "keyword",
  "matchedKeyword": "review",
  "reasoning": null
}
```

## Multi-Agent Orchestration

For complex tasks, orchestrate multiple agents:

```bash
# Sequential execution
orchestrate_sequential() {
  local prompts=("$@")
  for prompt in "${prompts[@]}"; do
    route_and_execute "$prompt"
  done
}

# Parallel execution (fan-out)
orchestrate_parallel() {
  local prompts=("$@")
  for prompt in "${prompts[@]}"; do
    route_and_execute "$prompt" &
  done
  wait
}
```

## Routing Optimization

### Cache Classification Results
- Cache key: hash(prompt + projectId)
- TTL: 1 hour for keyword/pattern, 5 min for semantic/llm
- Hit rate target: > 30%

### Priority Tuning
- 0-100: Low priority agents
- 100-500: Standard agents
- 500-1000: High priority / critical agents

### Confidence Thresholds
- Keyword: Always 1.0
- Pattern: 0.95
- Semantic: Require >= 0.7
- LLM: Require >= 0.6

## Best Practices

1. **Keyword Design**
   - Use specific, non-ambiguous keywords
   - Support regex for complex patterns
   - Avoid overlapping keywords

2. **Examples for Semantic**
   - Provide 3-5 diverse examples
   - Cover edge cases
   - Update based on misroutes

3. **Priority Management**
   - Higher priority = checked first
   - Use gaps (100, 200, 300) for flexibility
   - Review monthly
