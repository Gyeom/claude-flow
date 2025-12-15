import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import {
  Activity,
  CheckCircle,
  Clock,
  Coins,
  ThumbsUp,
  ThumbsDown,
  TrendingUp,
  Users,
  Timer,
  DollarSign,
  Zap,
  AlertTriangle,
} from 'lucide-react'
import { StatCard, Card, CardHeader } from '@/components/Card'
import {
  ChartContainer,
  AgentPerformanceChart,
  FeedbackChart,
  TokenUsageChart,
} from '@/components/Chart'
import { dashboardApi } from '@/lib/api'
import { formatNumber, formatDuration, formatPercent, formatCost, getSatisfactionColor, cn } from '@/lib/utils'

type Period = '1h' | '24h' | '7d' | '30d'

export function Dashboard() {
  const [period, setPeriod] = useState<Period>('7d')

  const { data: stats, isLoading, error } = useQuery({
    queryKey: ['dashboard', period],
    queryFn: () => dashboardApi.getStats(period),
    refetchInterval: 30000,
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    )
  }

  if (error) {
    return (
      <div className="text-center py-12">
        <p className="text-destructive">Failed to load dashboard data</p>
        <p className="text-sm text-muted-foreground mt-2">
          Make sure the API server is running
        </p>
      </div>
    )
  }

  // Default empty stats
  const emptyStats = {
    period: period,
    overview: {
      totalRequests: 0,
      successful: 0,
      failed: 0,
      successRate: 0,
      avgDurationMs: 0,
      p50DurationMs: 0,
      p90DurationMs: 0,
      p95DurationMs: 0,
      p99DurationMs: 0,
      totalCostUsd: 0,
      totalInputTokens: 0,
      totalOutputTokens: 0,
    },
    timeseries: [],
    models: [],
    sources: [],
    routing: [],
    topRequesters: [],
    feedback: {
      thumbsUp: 0,
      thumbsDown: 0,
      satisfactionScore: 0,
    },
  }

  // Use actual data with deep merge for safety
  const dashboardStats = {
    ...emptyStats,
    ...stats,
    overview: { ...emptyStats.overview, ...stats?.overview },
    feedback: { ...emptyStats.feedback, ...stats?.feedback },
    timeseries: stats?.timeseries || [],
    models: stats?.models || [],
    sources: stats?.sources || [],
    routing: stats?.routing || [],
    topRequesters: stats?.topRequesters || [],
  }

  const totalTokens = dashboardStats.overview.totalInputTokens + dashboardStats.overview.totalOutputTokens

  // Transform timeseries for charts
  const timeseriesChartData = dashboardStats.timeseries.map(t => ({
    date: new Date(t.timestamp).toLocaleDateString('en-US', { weekday: 'short' }),
    input: Math.floor(t.totalTokens * 0.4),
    output: Math.floor(t.totalTokens * 0.6),
  }))

  const routingChartData = dashboardStats.routing.map(r => ({
    name: r.method.charAt(0).toUpperCase() + r.method.slice(1),
    executions: r.requests,
    successRate: r.successRate,
  }))

  return (
    <div className="space-y-8">
      {/* Header with Period Selector */}
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-3xl font-bold">Dashboard</h1>
          <p className="text-muted-foreground mt-1">
            엔터프라이즈급 AI 에이전트 오케스트레이션 플랫폼
          </p>
        </div>
        <div className="flex gap-2">
          {(['1h', '24h', '7d', '30d'] as Period[]).map((p) => (
            <button
              key={p}
              onClick={() => setPeriod(p)}
              className={cn(
                'px-3 py-1.5 rounded-md text-sm font-medium transition-colors',
                period === p
                  ? 'bg-primary text-primary-foreground'
                  : 'bg-muted hover:bg-muted/80'
              )}
            >
              {p}
            </button>
          ))}
        </div>
      </div>

      {/* KPI Cards with P50/P90/P95/P99 */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        <StatCard
          title="Total Requests"
          value={formatNumber(dashboardStats.overview.totalRequests)}
          icon={<Activity className="h-6 w-6" />}
          change={{ value: 12.5, label: 'vs last period' }}
          trend="up"
        />
        <StatCard
          title="Success Rate"
          value={formatPercent(dashboardStats.overview.successRate)}
          icon={<CheckCircle className="h-6 w-6" />}
          change={{ value: 2.1, label: 'vs last period' }}
          trend="up"
        />
        <StatCard
          title="P50 Duration"
          value={formatDuration(dashboardStats.overview.p50DurationMs)}
          icon={<Timer className="h-6 w-6" />}
        />
        <StatCard
          title="Total Cost"
          value={formatCost(dashboardStats.overview.totalCostUsd)}
          icon={<DollarSign className="h-6 w-6" />}
        />
      </div>

      {/* Percentile Stats Bar */}
      <Card className="p-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Clock className="h-5 w-5 text-muted-foreground" />
            <span className="font-medium">Response Time Percentiles</span>
          </div>
          <div className="flex gap-6 text-sm">
            <div className="text-center">
              <p className="text-muted-foreground">P50</p>
              <p className="font-semibold">{formatDuration(dashboardStats.overview.p50DurationMs)}</p>
            </div>
            <div className="text-center">
              <p className="text-muted-foreground">P90</p>
              <p className="font-semibold text-yellow-600">{formatDuration(dashboardStats.overview.p90DurationMs)}</p>
            </div>
            <div className="text-center">
              <p className="text-muted-foreground">P95</p>
              <p className="font-semibold text-orange-600">{formatDuration(dashboardStats.overview.p95DurationMs)}</p>
            </div>
            <div className="text-center">
              <p className="text-muted-foreground">P99</p>
              <p className="font-semibold text-red-600">{formatDuration(dashboardStats.overview.p99DurationMs)}</p>
            </div>
          </div>
        </div>
      </Card>

      {/* Charts Row */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <ChartContainer
          title="Token Usage Trend"
          description="Daily token consumption"
        >
          <TokenUsageChart data={timeseriesChartData} />
        </ChartContainer>

        <ChartContainer
          title="Routing Method Distribution"
          description="How requests are being routed"
        >
          <AgentPerformanceChart data={routingChartData} />
        </ChartContainer>
      </div>

      {/* Bottom Row 3 Columns */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Model Stats */}
        <Card>
          <CardHeader title="Model Usage" description="By model type" />
          <div className="space-y-3">
            {dashboardStats.models.map((model) => (
              <div key={model.model} className="p-3 rounded-lg bg-muted/50">
                <div className="flex justify-between items-center mb-2">
                  <span className="font-medium text-sm">{model.model}</span>
                  <span className="text-xs text-muted-foreground">{formatCost(model.costUsd)}</span>
                </div>
                <div className="flex justify-between text-xs text-muted-foreground">
                  <span>{formatNumber(model.requests)} requests</span>
                  <span>{formatPercent(model.successRate)} success</span>
                </div>
                <div className="h-1.5 bg-muted rounded-full overflow-hidden mt-2">
                  <div
                    className="h-full bg-primary rounded-full"
                    style={{ width: `${(model.requests / dashboardStats.overview.totalRequests) * 100}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </Card>

        {/* User Feedback */}
        <Card>
          <CardHeader title="User Feedback" description="Satisfaction metrics" />
          <div className="h-[160px]">
            <FeedbackChart
              thumbsUp={dashboardStats.feedback.thumbsUp}
              thumbsDown={dashboardStats.feedback.thumbsDown}
            />
          </div>
          <div className="mt-4 flex items-center justify-between text-sm">
            <div className="flex items-center gap-2">
              <ThumbsUp className="h-4 w-4 text-green-500" />
              <span>{dashboardStats.feedback.thumbsUp}</span>
            </div>
            <div className={`font-semibold ${getSatisfactionColor(dashboardStats.feedback.satisfactionScore)}`}>
              NPS: {dashboardStats.feedback.satisfactionScore.toFixed(1)}
            </div>
            <div className="flex items-center gap-2">
              <ThumbsDown className="h-4 w-4 text-red-500" />
              <span>{dashboardStats.feedback.thumbsDown}</span>
            </div>
          </div>
        </Card>

        {/* Top Requesters */}
        <Card>
          <CardHeader title="Top Requesters" description="Most active users" />
          <div className="space-y-3">
            {dashboardStats.topRequesters.slice(0, 5).map((user, idx) => (
              <div key={user.userId} className="flex items-center justify-between p-2 rounded-lg hover:bg-muted/50">
                <div className="flex items-center gap-3">
                  <span className={cn(
                    'w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold',
                    idx === 0 ? 'bg-yellow-500 text-white' :
                    idx === 1 ? 'bg-gray-400 text-white' :
                    idx === 2 ? 'bg-orange-600 text-white' :
                    'bg-muted text-muted-foreground'
                  )}>
                    {idx + 1}
                  </span>
                  <div>
                    <p className="font-medium text-sm">{user.displayName || user.userId}</p>
                    <p className="text-xs text-muted-foreground">{formatNumber(user.requests)} requests</p>
                  </div>
                </div>
                <span className="text-xs text-muted-foreground">
                  {formatPercent(user.successRate)}
                </span>
              </div>
            ))}
          </div>
        </Card>
      </div>

      {/* Source & Routing Details */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Source Stats */}
        <Card>
          <CardHeader title="Request Sources" description="Where requests come from" />
          <div className="grid grid-cols-2 gap-4">
            {dashboardStats.sources.map((source) => (
              <div key={source.source} className="p-4 rounded-lg bg-muted/50">
                <div className="flex items-center gap-2 mb-2">
                  {source.source === 'slack' ? (
                    <Zap className="h-4 w-4 text-purple-500" />
                  ) : (
                    <Activity className="h-4 w-4 text-blue-500" />
                  )}
                  <span className="font-medium capitalize">{source.source}</span>
                </div>
                <p className="text-2xl font-bold">{formatNumber(source.requests)}</p>
                <p className="text-xs text-muted-foreground">
                  {formatPercent(source.successRate)} success rate
                </p>
              </div>
            ))}
          </div>
        </Card>

        {/* Quick Stats */}
        <Card>
          <CardHeader title="Quick Stats" description="Key metrics at a glance" />
          <div className="space-y-4">
            <div className="flex items-center justify-between p-3 rounded-lg bg-muted/50">
              <div className="flex items-center gap-3">
                <Coins className="h-5 w-5 text-muted-foreground" />
                <span className="text-sm">Total Tokens</span>
              </div>
              <span className="font-semibold">{formatNumber(totalTokens)}</span>
            </div>
            <div className="flex items-center justify-between p-3 rounded-lg bg-muted/50">
              <div className="flex items-center gap-3">
                <Users className="h-5 w-5 text-muted-foreground" />
                <span className="text-sm">Active Users</span>
              </div>
              <span className="font-semibold">{dashboardStats.topRequesters.length}</span>
            </div>
            <div className="flex items-center justify-between p-3 rounded-lg bg-muted/50">
              <div className="flex items-center gap-3">
                <AlertTriangle className="h-5 w-5 text-muted-foreground" />
                <span className="text-sm">Failed Requests</span>
              </div>
              <span className="font-semibold text-red-500">{dashboardStats.overview.failed}</span>
            </div>
            <div className="flex items-center justify-between p-3 rounded-lg bg-primary/5">
              <div className="flex items-center gap-3">
                <TrendingUp className="h-5 w-5 text-primary" />
                <span className="text-sm">Avg Tokens/Request</span>
              </div>
              <span className="font-semibold">
                {formatNumber(dashboardStats.overview.totalRequests > 0
                  ? Math.round(totalTokens / dashboardStats.overview.totalRequests)
                  : 0)}
              </span>
            </div>
          </div>
        </Card>
      </div>
    </div>
  )
}
