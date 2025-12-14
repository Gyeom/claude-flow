---
name: analytics
description: Claude Flow analytics and metrics analysis
---

# Analytics Skill

This skill provides analytics capabilities for monitoring and optimizing Claude Flow.

## API Endpoints

### 1. Dashboard Overview

```bash
# Get comprehensive dashboard data
analytics_dashboard() {
  local period="${1:-7d}"  # 1h, 24h, 7d, 30d
  curl -s "http://localhost:8080/api/v1/analytics/dashboard?period=$period"
}
```

### 2. Performance Metrics

```bash
# Get P50/P90/P95/P99 percentiles
analytics_percentiles() {
  local period="${1:-7d}"
  curl -s "http://localhost:8080/api/v1/analytics/overview?period=$period" | \
    jq '{p50: .p50DurationMs, p90: .p90DurationMs, p95: .p95DurationMs, p99: .p99DurationMs}'
}
```

### 3. Cost Analysis

```bash
# Get token usage and cost breakdown
analytics_costs() {
  local period="${1:-30d}"
  curl -s "http://localhost:8080/api/v1/analytics/dashboard?period=$period" | \
    jq '{totalCost: .overview.totalCostUsd, inputTokens: .overview.totalInputTokens, outputTokens: .overview.totalOutputTokens, models: .models}'
}
```

### 4. User Satisfaction

```bash
# Get feedback metrics
analytics_feedback() {
  curl -s "http://localhost:8080/api/v1/analytics/dashboard?period=30d" | \
    jq '.feedback | {thumbsUp, thumbsDown, satisfactionScore}'
}
```

### 5. Routing Analysis

```bash
# Analyze routing method effectiveness
analytics_routing() {
  curl -s "http://localhost:8080/api/v1/analytics/dashboard?period=7d" | \
    jq '.routing[] | {method, requests, successRate, avgConfidence}'
}
```

## Key Metrics to Monitor

### Performance Thresholds
| Metric | Good | Warning | Critical |
|--------|------|---------|----------|
| P50 | < 3s | 3-5s | > 5s |
| P90 | < 8s | 8-15s | > 15s |
| P99 | < 15s | 15-30s | > 30s |
| Success Rate | > 98% | 95-98% | < 95% |

### Cost Optimization
- **Input tokens**: Should be < 60% of total
- **Cache hit rate**: Target > 30%
- **Model mix**: Use Haiku for routing, Sonnet for execution

### User Satisfaction
- **NPS Score**: Target > 70
- **Thumbs up ratio**: Target > 85%

## Analysis Recommendations

When analyzing metrics:

1. **Performance Issues**
   - P99 > 15s → Check for long-running agents
   - High variance → Investigate specific requests

2. **Cost Optimization**
   - High input tokens → Improve context management
   - Low cache hits → Tune caching strategy

3. **Quality Issues**
   - Low success rate → Review error patterns
   - Low satisfaction → Analyze negative feedback

## Output Format

Present analytics as:

```
## Claude Flow Analytics Report (${period})

### Performance
- P50: ${p50}ms | P90: ${p90}ms | P95: ${p95}ms | P99: ${p99}ms
- Success Rate: ${successRate}%

### Costs
- Total: $${cost} (${totalTokens} tokens)
- Input: ${inputTokens} | Output: ${outputTokens}

### User Satisfaction
- Score: ${nps}/100
- Positive: ${thumbsUp} | Negative: ${thumbsDown}

### Recommendations
1. ...
2. ...
```
