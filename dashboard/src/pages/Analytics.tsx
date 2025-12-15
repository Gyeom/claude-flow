import { useQuery } from '@tanstack/react-query'
import {
  TrendingUp,
  TrendingDown,
  Zap,
  Target,
  Gauge,
  PieChart,
  BarChart3,
} from 'lucide-react'
import { Card, CardHeader, StatCard } from '@/components/Card'
import {
  ChartContainer,
  TokenUsageChart,
  AgentPerformanceChart,
} from '@/components/Chart'
import { analyticsApi } from '@/lib/api'
import { formatNumber, formatCost, formatPercent, cn } from '@/lib/utils'
import type { RoutingEfficiency } from '@/types'

export function Analytics() {
  const { data: feedback, isLoading: feedbackLoading } = useQuery({
    queryKey: ['analytics', 'feedback'],
    queryFn: () => analyticsApi.getFeedback('30d'),
  })

  const { data: tokenUsage, isLoading: tokenLoading } = useQuery({
    queryKey: ['analytics', 'tokens'],
    queryFn: () => analyticsApi.getTokenUsage('30d'),
  })

  const { data: routing, isLoading: routingLoading } = useQuery({
    queryKey: ['analytics', 'routing'],
    queryFn: () => analyticsApi.getRoutingEfficiency('30d'),
  })

  const { data: projects, isLoading: projectsLoading } = useQuery({
    queryKey: ['analytics', 'projects'],
    queryFn: () => analyticsApi.getProjectStats(),
  })

  // Use actual data with empty defaults
  const feedbackData = feedback || {
    totalFeedback: 0,
    positiveCount: 0,
    negativeCount: 0,
    positiveRate: 0,
    negativeRate: 0,
    satisfactionScore: 0,
  }

  const tokenData = tokenUsage || {
    totalTokens: 0,
    inputTokens: 0,
    outputTokens: 0,
    estimatedCost: 0,
    avgTokensPerRequest: 0,
  }

  const routingData: RoutingEfficiency = (routing as unknown as RoutingEfficiency) || {
    keywordMatchRate: 0,
    semanticMatchRate: 0,
    llmFallbackRate: 0,
    defaultFallbackRate: 0,
    avgRoutingTimeMs: 0,
  }

  const projectData = projects || []

  const tokenTrendData: { date: string; input: number; output: number }[] = []

  const routingChartData = [
    { name: 'Keyword', executions: Math.round(routingData.keywordMatchRate * 1000), successRate: 0.98 },
    { name: 'Semantic', executions: Math.round(routingData.semanticMatchRate * 1000), successRate: 0.95 },
    { name: 'LLM', executions: Math.round(routingData.llmFallbackRate * 1000), successRate: 0.92 },
    { name: 'Default', executions: Math.round(routingData.defaultFallbackRate * 1000), successRate: 0.88 },
  ]

  const isLoading = feedbackLoading || tokenLoading || routingLoading || projectsLoading

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    )
  }

  return (
    <div className="space-y-8">
      {/* Header */}
      <div>
        <h1 className="text-3xl font-bold">Analytics</h1>
        <p className="text-muted-foreground mt-1">
          Deep insights into your AI agent platform
        </p>
      </div>

      {/* Feedback Section */}
      <div>
        <h2 className="text-xl font-semibold mb-4 flex items-center gap-2">
          <TrendingUp className="h-5 w-5" />
          User Satisfaction
        </h2>
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <StatCard
            title="Total Feedback"
            value={feedbackData.totalFeedback}
            icon={<PieChart className="h-6 w-6" />}
          />
          <StatCard
            title="Positive Rate"
            value={formatPercent(feedbackData.positiveRate)}
            icon={<TrendingUp className="h-6 w-6" />}
            trend="up"
          />
          <StatCard
            title="NPS Score"
            value={feedbackData.satisfactionScore.toFixed(1)}
            icon={<Target className="h-6 w-6" />}
            className={cn(
              feedbackData.satisfactionScore >= 50 ? 'border-green-500/30' : 'border-yellow-500/30'
            )}
          />
          <StatCard
            title="Negative Rate"
            value={formatPercent(feedbackData.negativeRate)}
            icon={<TrendingDown className="h-6 w-6" />}
            trend="down"
          />
        </div>
      </div>

      {/* Token Usage Section */}
      <div>
        <h2 className="text-xl font-semibold mb-4 flex items-center gap-2">
          <Zap className="h-5 w-5" />
          Token Usage & Cost
        </h2>
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="lg:col-span-2">
            <ChartContainer
              title="Token Usage Trend"
              description="Daily token consumption over the last 7 days"
            >
              <TokenUsageChart data={tokenTrendData} />
            </ChartContainer>
          </div>
          <div className="space-y-4">
            <Card>
              <CardHeader title="Token Summary" />
              <div className="space-y-4">
                <div className="flex justify-between items-center p-3 rounded-lg bg-muted/50">
                  <span className="text-sm text-muted-foreground">Total Tokens</span>
                  <span className="font-semibold">{formatNumber(tokenData.totalTokens)}</span>
                </div>
                <div className="flex justify-between items-center p-3 rounded-lg bg-purple-500/10">
                  <span className="text-sm text-purple-600">Input Tokens</span>
                  <span className="font-semibold text-purple-600">
                    {formatNumber(tokenData.inputTokens)}
                  </span>
                </div>
                <div className="flex justify-between items-center p-3 rounded-lg bg-cyan-500/10">
                  <span className="text-sm text-cyan-600">Output Tokens</span>
                  <span className="font-semibold text-cyan-600">
                    {formatNumber(tokenData.outputTokens)}
                  </span>
                </div>
                <div className="flex justify-between items-center p-3 rounded-lg bg-green-500/10">
                  <span className="text-sm text-green-600">Estimated Cost</span>
                  <span className="font-semibold text-green-600">
                    {formatCost(tokenData.estimatedCost)}
                  </span>
                </div>
                <div className="flex justify-between items-center p-3 rounded-lg bg-muted/50">
                  <span className="text-sm text-muted-foreground">Avg per Request</span>
                  <span className="font-semibold">
                    {formatNumber(tokenData.avgTokensPerRequest)}
                  </span>
                </div>
              </div>
            </Card>
          </div>
        </div>
      </div>

      {/* Routing Efficiency Section */}
      <div>
        <h2 className="text-xl font-semibold mb-4 flex items-center gap-2">
          <Gauge className="h-5 w-5" />
          Routing Efficiency
        </h2>
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <ChartContainer
            title="Routing Method Distribution"
            description="How requests are being routed to agents"
          >
            <AgentPerformanceChart data={routingChartData} />
          </ChartContainer>

          <Card>
            <CardHeader
              title="Routing Breakdown"
              description="Detailed routing statistics"
            />
            <div className="space-y-4">
              <div className="space-y-2">
                <div className="flex justify-between text-sm">
                  <span>Keyword Match</span>
                  <span className="font-medium">{formatPercent(routingData.keywordMatchRate)}</span>
                </div>
                <div className="h-2 bg-muted rounded-full overflow-hidden">
                  <div
                    className="h-full bg-green-500 rounded-full"
                    style={{ width: `${routingData.keywordMatchRate * 100}%` }}
                  />
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex justify-between text-sm">
                  <span>Semantic Match</span>
                  <span className="font-medium">{formatPercent(routingData.semanticMatchRate)}</span>
                </div>
                <div className="h-2 bg-muted rounded-full overflow-hidden">
                  <div
                    className="h-full bg-blue-500 rounded-full"
                    style={{ width: `${routingData.semanticMatchRate * 100}%` }}
                  />
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex justify-between text-sm">
                  <span>LLM Fallback</span>
                  <span className="font-medium">{formatPercent(routingData.llmFallbackRate)}</span>
                </div>
                <div className="h-2 bg-muted rounded-full overflow-hidden">
                  <div
                    className="h-full bg-yellow-500 rounded-full"
                    style={{ width: `${routingData.llmFallbackRate * 100}%` }}
                  />
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex justify-between text-sm">
                  <span>Default Fallback</span>
                  <span className="font-medium">{formatPercent(routingData.defaultFallbackRate)}</span>
                </div>
                <div className="h-2 bg-muted rounded-full overflow-hidden">
                  <div
                    className="h-full bg-gray-500 rounded-full"
                    style={{ width: `${routingData.defaultFallbackRate * 100}%` }}
                  />
                </div>
              </div>

              <div className="pt-4 border-t border-border">
                <div className="flex justify-between items-center">
                  <span className="text-sm text-muted-foreground">Avg Routing Time</span>
                  <span className="font-semibold">{routingData.avgRoutingTimeMs}ms</span>
                </div>
              </div>
            </div>
          </Card>
        </div>
      </div>

      {/* Project Stats Section */}
      <div>
        <h2 className="text-xl font-semibold mb-4 flex items-center gap-2">
          <BarChart3 className="h-5 w-5" />
          Project Statistics
        </h2>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {projectData.map((project) => (
            <Card key={project.projectId}>
              <CardHeader title={project.projectId} />
              <div className="grid grid-cols-2 gap-4 text-sm">
                <div className="p-3 rounded-lg bg-muted/50">
                  <p className="text-muted-foreground">Agents</p>
                  <p className="font-semibold text-lg">{project.agentCount}</p>
                </div>
                <div className="p-3 rounded-lg bg-muted/50">
                  <p className="text-muted-foreground">Executions</p>
                  <p className="font-semibold text-lg">{project.totalExecutions}</p>
                </div>
                <div className="col-span-2 p-3 rounded-lg bg-primary/5">
                  <p className="text-muted-foreground">Avg Duration</p>
                  <p className="font-semibold text-lg">{(project.avgDurationMs / 1000).toFixed(1)}s</p>
                </div>
              </div>
            </Card>
          ))}
        </div>
      </div>
    </div>
  )
}
