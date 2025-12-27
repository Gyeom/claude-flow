import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import {
  Activity,
  CheckCircle,
  Clock,
  Coins,
  ThumbsUp,
  ThumbsDown,
  Users,
  Timer,
  DollarSign,
  AlertTriangle,
} from 'lucide-react'
import { StatCard, Card, CardHeader } from '@/components/Card'
import {
  ChartContainer,
  FeedbackChart,
  TokenUsageChart,
} from '@/components/Chart'
import { dashboardApi, analyticsApi, type TokenTrendPoint } from '@/lib/api'
import { formatNumber, formatDuration, formatPercent, formatCost, getSatisfactionColor, cn } from '@/lib/utils'
import {
  PieChart,
  Pie,
  Cell,
  Tooltip,
  ResponsiveContainer,
} from 'recharts'

type Period = '1h' | '24h' | '7d' | '30d'

const MODEL_COLORS: Record<string, string> = {
  'claude-sonnet-4-20250514': '#8b5cf6',
  'claude-3-5-sonnet-20241022': '#8b5cf6',
  'claude-3-opus-20240229': '#f59e0b',
  'claude-3-sonnet-20240229': '#3b82f6',
  'claude-3-haiku-20240307': '#10b981',
  'gpt-4': '#ef4444',
  'gpt-4-turbo': '#f97316',
  'gpt-3.5-turbo': '#6366f1',
  default: '#94a3b8',
}

export function Dashboard() {
  const [period, setPeriod] = useState<Period>('7d')

  const { data: stats, isLoading: statsLoading, error } = useQuery({
    queryKey: ['dashboard', period],
    queryFn: () => dashboardApi.getStats(period),
    refetchInterval: 30000,
  })

  const { data: tokenTrend, isLoading: tokenTrendLoading } = useQuery({
    queryKey: ['dashboard', 'tokens', 'trend', period],
    queryFn: () => analyticsApi.getTokensTrend(period),
  })

  const { data: modelsData, isLoading: modelsLoading } = useQuery({
    queryKey: ['dashboard', 'models', period],
    queryFn: () => analyticsApi.getModels(period),
  })

  const isLoading = statsLoading || tokenTrendLoading || modelsLoading

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

  const timeseriesChartData = (tokenTrend || []).map((point: TokenTrendPoint) => ({
    date: new Date(point.date).toLocaleDateString('en-US', { weekday: 'short' }),
    input: point.inputTokens,
    output: point.outputTokens,
  }))

  // Model data for pie chart
  const modelList = modelsData?.models || []
  const totalModelRequests = modelList.reduce((sum, m) => sum + (m.requests || 0), 0)
  const totalModelCost = modelList.reduce((sum, m) => sum + (m.costUsd || 0), 0)

  const pieData = modelList.map(m => ({
    name: m.model.split('-').slice(-2).join('-'),
    value: m.requests,
    fullName: m.model,
    color: MODEL_COLORS[m.model] || MODEL_COLORS.default,
  }))

  return (
    <div className="space-y-8">
      {/* Header with Period Selector */}
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-3xl font-bold">Dashboard</h1>
          <p className="text-muted-foreground mt-1">
            AI 에이전트 플랫폼 통합 대시보드
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

      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        <StatCard
          title="Total Requests"
          value={formatNumber(dashboardStats.overview.totalRequests)}
          icon={<Activity className="h-6 w-6" />}
        />
        <StatCard
          title="Success Rate"
          value={formatPercent(dashboardStats.overview.successRate)}
          icon={<CheckCircle className="h-6 w-6" />}
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
          title="Model Distribution"
          description={`${formatNumber(totalModelRequests)} requests · ${formatCost(totalModelCost)} total`}
        >
          {pieData.length > 0 ? (
            <ResponsiveContainer width="100%" height={240}>
              <PieChart>
                <Pie
                  data={pieData}
                  cx="50%"
                  cy="50%"
                  outerRadius={80}
                  dataKey="value"
                  label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
                >
                  {pieData.map((entry, index) => (
                    <Cell key={`cell-${index}`} fill={entry.color} />
                  ))}
                </Pie>
                <Tooltip formatter={(value: number) => formatNumber(value)} />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <div className="h-[240px] flex items-center justify-center text-muted-foreground">
              No model data available
            </div>
          )}
        </ChartContainer>
      </div>

      {/* Model Stats Table */}
      {modelList.length > 0 && (
        <Card>
          <CardHeader title="Model Usage" description="Performance by model" />
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border">
                  <th className="text-left py-3 px-4 font-medium text-muted-foreground">Model</th>
                  <th className="text-right py-3 px-4 font-medium text-muted-foreground">Requests</th>
                  <th className="text-right py-3 px-4 font-medium text-muted-foreground">Tokens</th>
                  <th className="text-right py-3 px-4 font-medium text-muted-foreground">Avg Duration</th>
                  <th className="text-right py-3 px-4 font-medium text-muted-foreground">Success</th>
                  <th className="text-right py-3 px-4 font-medium text-muted-foreground">Cost</th>
                </tr>
              </thead>
              <tbody>
                {modelList.map((model) => (
                  <tr key={model.model} className="border-b border-border/50 hover:bg-muted/30">
                    <td className="py-3 px-4">
                      <div className="flex items-center gap-2">
                        <div
                          className="w-3 h-3 rounded-full"
                          style={{ backgroundColor: MODEL_COLORS[model.model] || MODEL_COLORS.default }}
                        />
                        <span className="font-medium truncate max-w-[200px]" title={model.model}>
                          {model.model}
                        </span>
                      </div>
                    </td>
                    <td className="text-right py-3 px-4">{formatNumber(model.requests)}</td>
                    <td className="text-right py-3 px-4">{formatNumber(model.totalTokens)}</td>
                    <td className="text-right py-3 px-4">{(model.avgDurationMs / 1000).toFixed(2)}s</td>
                    <td className="text-right py-3 px-4">
                      <span className={cn(
                        model.successRate >= 0.95 ? 'text-green-500' :
                        model.successRate >= 0.9 ? 'text-yellow-500' : 'text-red-500'
                      )}>
                        {formatPercent(model.successRate)}
                      </span>
                    </td>
                    <td className="text-right py-3 px-4">{formatCost(model.costUsd)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Bottom Row 3 Columns */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
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

        {/* Quick Stats */}
        <Card>
          <CardHeader title="Quick Stats" description="Key metrics" />
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
                <Activity className="h-5 w-5 text-primary" />
                <span className="text-sm">Avg Tokens/Req</span>
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
