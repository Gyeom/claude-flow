import { useQuery } from '@tanstack/react-query'
import {
  TrendingUp,
  TrendingDown,
  DollarSign,
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
  SuccessRatePieChart,
  AgentPerformanceChart,
} from '@/components/Chart'
import { analyticsApi } from '@/lib/api'
import { formatNumber, formatCost, formatPercent, cn } from '@/lib/utils'

export function Analytics() {
  const { data: feedback, isLoading: feedbackLoading } = useQuery({
    queryKey: ['analytics', 'feedback'],
    queryFn: () => analyticsApi.getFeedback(30),
  })

  const { data: tokenUsage, isLoading: tokenLoading } = useQuery({
    queryKey: ['analytics', 'tokens'],
    queryFn: () => analyticsApi.getTokenUsage(30),
  })

  const { data: routing, isLoading: routingLoading } = useQuery({
    queryKey: ['analytics', 'routing'],
    queryFn: analyticsApi.getRoutingEfficiency,
  })

  const { data: projects, isLoading: projectsLoading } = useQuery({
    queryKey: ['analytics', 'projects'],
    queryFn: analyticsApi.getProjectStats,
  })

  // Mock data for demo
  const mockFeedback = feedback || {
    totalFeedback: 101,
    positiveCount: 89,
    negativeCount: 12,
    positiveRate: 0.881,
    negativeRate: 0.119,
    satisfactionScore: 76.2,
  }

  const mockTokenUsage = tokenUsage || {
    totalTokens: 2500000,
    inputTokens: 1000000,
    outputTokens: 1500000,
    estimatedCost: 7.50,
    avgTokensPerRequest: 2028,
  }

  const mockRouting = routing || {
    keywordMatchRate: 0.45,
    semanticMatchRate: 0.35,
    llmFallbackRate: 0.08,
    defaultFallbackRate: 0.12,
    avgRoutingTimeMs: 25,
  }

  const mockProjects = projects || [
    { projectId: 'project-alpha', agentCount: 3, totalExecutions: 450, avgDurationMs: 3200 },
    { projectId: 'project-beta', agentCount: 2, totalExecutions: 320, avgDurationMs: 2800 },
    { projectId: 'global', agentCount: 3, totalExecutions: 280, avgDurationMs: 4100 },
  ]

  const tokenTrendData = Array.from({ length: 7 }, (_, i) => ({
    date: new Date(Date.now() - (6 - i) * 86400000).toLocaleDateString('en-US', { weekday: 'short' }),
    input: Math.floor(Math.random() * 200000) + 100000,
    output: Math.floor(Math.random() * 300000) + 150000,
  }))

  const routingData = [
    { name: 'Keyword', executions: Math.round(mockRouting.keywordMatchRate * 1000), successRate: 0.98 },
    { name: 'Semantic', executions: Math.round(mockRouting.semanticMatchRate * 1000), successRate: 0.95 },
    { name: 'LLM', executions: Math.round(mockRouting.llmFallbackRate * 1000), successRate: 0.92 },
    { name: 'Default', executions: Math.round(mockRouting.defaultFallbackRate * 1000), successRate: 0.88 },
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
            value={mockFeedback.totalFeedback}
            icon={<PieChart className="h-6 w-6" />}
          />
          <StatCard
            title="Positive Rate"
            value={formatPercent(mockFeedback.positiveRate)}
            icon={<TrendingUp className="h-6 w-6" />}
            trend="up"
          />
          <StatCard
            title="NPS Score"
            value={mockFeedback.satisfactionScore.toFixed(1)}
            icon={<Target className="h-6 w-6" />}
            className={cn(
              mockFeedback.satisfactionScore >= 50 ? 'border-green-500/30' : 'border-yellow-500/30'
            )}
          />
          <StatCard
            title="Negative Rate"
            value={formatPercent(mockFeedback.negativeRate)}
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
                  <span className="font-semibold">{formatNumber(mockTokenUsage.totalTokens)}</span>
                </div>
                <div className="flex justify-between items-center p-3 rounded-lg bg-purple-500/10">
                  <span className="text-sm text-purple-600">Input Tokens</span>
                  <span className="font-semibold text-purple-600">
                    {formatNumber(mockTokenUsage.inputTokens)}
                  </span>
                </div>
                <div className="flex justify-between items-center p-3 rounded-lg bg-cyan-500/10">
                  <span className="text-sm text-cyan-600">Output Tokens</span>
                  <span className="font-semibold text-cyan-600">
                    {formatNumber(mockTokenUsage.outputTokens)}
                  </span>
                </div>
                <div className="flex justify-between items-center p-3 rounded-lg bg-green-500/10">
                  <span className="text-sm text-green-600">Estimated Cost</span>
                  <span className="font-semibold text-green-600">
                    {formatCost(mockTokenUsage.estimatedCost)}
                  </span>
                </div>
                <div className="flex justify-between items-center p-3 rounded-lg bg-muted/50">
                  <span className="text-sm text-muted-foreground">Avg per Request</span>
                  <span className="font-semibold">
                    {formatNumber(mockTokenUsage.avgTokensPerRequest)}
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
            <AgentPerformanceChart data={routingData} />
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
                  <span className="font-medium">{formatPercent(mockRouting.keywordMatchRate)}</span>
                </div>
                <div className="h-2 bg-muted rounded-full overflow-hidden">
                  <div
                    className="h-full bg-green-500 rounded-full"
                    style={{ width: `${mockRouting.keywordMatchRate * 100}%` }}
                  />
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex justify-between text-sm">
                  <span>Semantic Match</span>
                  <span className="font-medium">{formatPercent(mockRouting.semanticMatchRate)}</span>
                </div>
                <div className="h-2 bg-muted rounded-full overflow-hidden">
                  <div
                    className="h-full bg-blue-500 rounded-full"
                    style={{ width: `${mockRouting.semanticMatchRate * 100}%` }}
                  />
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex justify-between text-sm">
                  <span>LLM Fallback</span>
                  <span className="font-medium">{formatPercent(mockRouting.llmFallbackRate)}</span>
                </div>
                <div className="h-2 bg-muted rounded-full overflow-hidden">
                  <div
                    className="h-full bg-yellow-500 rounded-full"
                    style={{ width: `${mockRouting.llmFallbackRate * 100}%` }}
                  />
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex justify-between text-sm">
                  <span>Default Fallback</span>
                  <span className="font-medium">{formatPercent(mockRouting.defaultFallbackRate)}</span>
                </div>
                <div className="h-2 bg-muted rounded-full overflow-hidden">
                  <div
                    className="h-full bg-gray-500 rounded-full"
                    style={{ width: `${mockRouting.defaultFallbackRate * 100}%` }}
                  />
                </div>
              </div>

              <div className="pt-4 border-t border-border">
                <div className="flex justify-between items-center">
                  <span className="text-sm text-muted-foreground">Avg Routing Time</span>
                  <span className="font-semibold">{mockRouting.avgRoutingTimeMs}ms</span>
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
          {mockProjects.map((project) => (
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
