---
description: Show Claude Flow analytics dashboard (P50/P90/P95/P99)
argument-hint: [period: 1h|24h|7d|30d]
---

# Claude Flow Metrics Dashboard

Fetch and analyze real-time metrics from Claude Flow.

```bash
PERIOD="${ARGUMENTS:-7d}"
curl -s "http://localhost:8080/api/v1/analytics/dashboard?period=$PERIOD"
```

Present the metrics in a formatted summary:

## Key Metrics to Highlight:
1. **Response Time Percentiles** (P50, P90, P95, P99)
2. **Success Rate** and error breakdown
3. **Token Usage** and estimated cost
4. **Top Requesters** and their satisfaction scores
5. **Routing Method Distribution** (keyword, semantic, LLM, fallback)

## Recommendations:
- Flag any P99 > 10s (performance issue)
- Alert if success rate < 95%
- Identify cost optimization opportunities
