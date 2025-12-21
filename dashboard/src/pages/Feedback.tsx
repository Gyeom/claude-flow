import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  ThumbsUp,
  ThumbsDown,
  TrendingUp,
  Smile,
  Meh,
  Frown,
  Calendar,
  ShieldCheck,
  Tags,
} from 'lucide-react'
import { Card, CardHeader, StatCard } from '@/components/Card'
import { ChartContainer } from '@/components/Chart'
import { analyticsApi, verifiedFeedbackApi, type FeedbackTrendPoint } from '@/lib/api'
import { formatNumber, formatPercent, cn } from '@/lib/utils'
import {
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
  LineChart,
  Line,
} from 'recharts'

const PERIODS = [
  { label: '7 Days', value: '7d' },
  { label: '14 Days', value: '14d' },
  { label: '30 Days', value: '30d' },
  { label: '90 Days', value: '90d' },
]

const COLORS = {
  positive: '#22c55e',
  negative: '#ef4444',
  neutral: '#94a3b8',
}

export function Feedback() {
  const [period, setPeriod] = useState('7d')

  const { data: feedback, isLoading: feedbackLoading } = useQuery({
    queryKey: ['analytics', 'feedback', period],
    queryFn: () => analyticsApi.getFeedback(period),
  })

  const { data: verifiedStats, isLoading: verifiedLoading } = useQuery({
    queryKey: ['analytics', 'feedback', 'verified', period],
    queryFn: () => verifiedFeedbackApi.getStats(parseInt(period.replace('d', ''))),
  })

  const { data: categoryStats, isLoading: categoryLoading } = useQuery({
    queryKey: ['analytics', 'feedback', 'categories', period],
    queryFn: () => verifiedFeedbackApi.getByCategory(parseInt(period.replace('d', ''))),
  })

  const { data: feedbackTrend, isLoading: trendLoading } = useQuery({
    queryKey: ['analytics', 'feedback', 'trend', period],
    queryFn: () => analyticsApi.getFeedbackTrend(period),
  })

  const isLoading = feedbackLoading || verifiedLoading || categoryLoading || trendLoading

  // Use API data directly with default values
  const feedbackData = feedback || {
    totalFeedback: 0,
    positiveCount: 0,
    negativeCount: 0,
    positiveRate: 0,
    negativeRate: 0,
    satisfactionScore: 0,
  }

  const pieData = [
    { name: 'Positive', value: feedbackData.positiveCount, color: COLORS.positive },
    { name: 'Negative', value: feedbackData.negativeCount, color: COLORS.negative },
  ]

  // Trend data from backend API
  const trendData = (feedbackTrend || []).map((point: FeedbackTrendPoint) => ({
    date: new Date(point.date).toLocaleDateString('en-US', { weekday: 'short' }),
    positive: point.positive,
    negative: point.negative,
  }))

  const getSatisfactionIcon = (score: number) => {
    if (score >= 70) return <Smile className="h-8 w-8 text-green-500" />
    if (score >= 50) return <Meh className="h-8 w-8 text-yellow-500" />
    return <Frown className="h-8 w-8 text-red-500" />
  }

  const getSatisfactionColor = (score: number) => {
    if (score >= 70) return 'text-green-500'
    if (score >= 50) return 'text-yellow-500'
    return 'text-red-500'
  }

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
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Feedback Analytics</h1>
          <p className="text-muted-foreground mt-1">
            User satisfaction and feedback insights
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Calendar className="h-4 w-4 text-muted-foreground" />
          <select
            value={period}
            onChange={(e) => setPeriod(e.target.value)}
            className="bg-card border border-border rounded-lg px-3 py-2 text-sm"
          >
            {PERIODS.map((p) => (
              <option key={p.value} value={p.value}>
                {p.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Overview Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <StatCard
          title="Total Feedback"
          value={formatNumber(feedbackData.totalFeedback)}
          icon={<TrendingUp className="h-6 w-6" />}
        />
        <StatCard
          title="Positive"
          value={formatNumber(feedbackData.positiveCount)}
          icon={<ThumbsUp className="h-6 w-6 text-green-500" />}
          trend="up"
          className="border-green-500/30"
        />
        <StatCard
          title="Negative"
          value={formatNumber(feedbackData.negativeCount)}
          icon={<ThumbsDown className="h-6 w-6 text-red-500" />}
          trend="down"
          className="border-red-500/30"
        />
        <Card className="p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-muted-foreground">Satisfaction Score</p>
              <p className={cn("text-3xl font-bold mt-1", getSatisfactionColor(feedbackData.satisfactionScore))}>
                {feedbackData.satisfactionScore.toFixed(1)}
              </p>
            </div>
            {getSatisfactionIcon(feedbackData.satisfactionScore)}
          </div>
        </Card>
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Pie Chart */}
        <ChartContainer
          title="Feedback Distribution"
          description="Positive vs Negative feedback ratio"
        >
          <ResponsiveContainer width="100%" height={300}>
            <PieChart>
              <Pie
                data={pieData}
                cx="50%"
                cy="50%"
                innerRadius={60}
                outerRadius={100}
                paddingAngle={5}
                dataKey="value"
                label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
              >
                {pieData.map((entry, index) => (
                  <Cell key={`cell-${index}`} fill={entry.color} />
                ))}
              </Pie>
              <Tooltip />
            </PieChart>
          </ResponsiveContainer>
        </ChartContainer>

        {/* Trend Chart */}
        <ChartContainer
          title="Feedback Trend"
          description="Daily feedback over time"
        >
          <ResponsiveContainer width="100%" height={300}>
            <LineChart data={trendData}>
              <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
              <XAxis dataKey="date" className="text-muted-foreground text-xs" />
              <YAxis className="text-muted-foreground text-xs" />
              <Tooltip />
              <Line
                type="monotone"
                dataKey="positive"
                stroke={COLORS.positive}
                strokeWidth={2}
                name="Positive"
              />
              <Line
                type="monotone"
                dataKey="negative"
                stroke={COLORS.negative}
                strokeWidth={2}
                name="Negative"
              />
            </LineChart>
          </ResponsiveContainer>
        </ChartContainer>
      </div>

      {/* Verified Feedback Section */}
      {verifiedStats && (
        <div>
          <h2 className="text-xl font-semibold mb-4 flex items-center gap-2">
            <ShieldCheck className="h-5 w-5 text-green-500" />
            Verified Feedback
          </h2>
          <p className="text-sm text-muted-foreground mb-4">
            Only feedback from the original requester is counted for satisfaction scores
          </p>
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <StatCard
              title="Total Feedback"
              value={formatNumber(verifiedStats.totalFeedback)}
              icon={<TrendingUp className="h-6 w-6" />}
            />
            <StatCard
              title="Verified"
              value={formatNumber(verifiedStats.verifiedFeedback)}
              icon={<ShieldCheck className="h-6 w-6 text-green-500" />}
              className="border-green-500/30"
            />
            <StatCard
              title="Verification Rate"
              value={formatPercent(verifiedStats.verificationRate)}
              icon={<TrendingUp className="h-6 w-6 text-blue-500" />}
            />
            <Card className="p-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-muted-foreground">Verified Satisfaction</p>
                  <p className={cn(
                    "text-3xl font-bold mt-1",
                    verifiedStats.satisfactionRate >= 0.7 ? 'text-green-500' :
                    verifiedStats.satisfactionRate >= 0.5 ? 'text-yellow-500' : 'text-red-500'
                  )}>
                    {formatPercent(verifiedStats.satisfactionRate)}
                  </p>
                </div>
                <ShieldCheck className={cn(
                  "h-8 w-8",
                  verifiedStats.satisfactionRate >= 0.7 ? 'text-green-500' :
                  verifiedStats.satisfactionRate >= 0.5 ? 'text-yellow-500' : 'text-red-500'
                )} />
              </div>
            </Card>
          </div>
        </div>
      )}

      {/* Category Breakdown */}
      {categoryStats && categoryStats.length > 0 && (
        <div>
          <h2 className="text-xl font-semibold mb-4 flex items-center gap-2">
            <Tags className="h-5 w-5" />
            Feedback by Category
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            {categoryStats.map((cat) => (
              <Card key={cat.category} className="p-4">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-sm font-medium capitalize">{cat.category}</span>
                  <span className={cn(
                    "text-xs px-2 py-1 rounded-full",
                    cat.category === 'feedback' ? 'bg-green-500/10 text-green-500' :
                    cat.category === 'trigger' ? 'bg-blue-500/10 text-blue-500' :
                    cat.category === 'action' ? 'bg-purple-500/10 text-purple-500' :
                    'bg-gray-500/10 text-gray-500'
                  )}>
                    {cat.category === 'feedback' ? 'thumbsup/down' :
                     cat.category === 'trigger' ? 'jira/gitlab' :
                     cat.category === 'action' ? 'numbers' : 'other'}
                  </span>
                </div>
                <p className="text-2xl font-bold">{formatNumber(cat.count)}</p>
                <div className="flex items-center gap-2 mt-2 text-xs text-muted-foreground">
                  <ShieldCheck className="h-3 w-3 text-green-500" />
                  <span>{formatNumber(cat.verifiedCount)} verified</span>
                </div>
              </Card>
            ))}
          </div>
        </div>
      )}

      {/* Detailed Metrics */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <CardHeader
            title="Rate Breakdown"
            description="Feedback rate analysis"
          />
          <div className="space-y-6">
            <div>
              <div className="flex justify-between mb-2">
                <span className="text-sm flex items-center gap-2">
                  <ThumbsUp className="h-4 w-4 text-green-500" />
                  Positive Rate
                </span>
                <span className="font-semibold text-green-500">
                  {formatPercent(feedbackData.positiveRate)}
                </span>
              </div>
              <div className="h-3 bg-muted rounded-full overflow-hidden">
                <div
                  className="h-full bg-green-500 rounded-full transition-all"
                  style={{ width: `${feedbackData.positiveRate * 100}%` }}
                />
              </div>
            </div>

            <div>
              <div className="flex justify-between mb-2">
                <span className="text-sm flex items-center gap-2">
                  <ThumbsDown className="h-4 w-4 text-red-500" />
                  Negative Rate
                </span>
                <span className="font-semibold text-red-500">
                  {formatPercent(feedbackData.negativeRate)}
                </span>
              </div>
              <div className="h-3 bg-muted rounded-full overflow-hidden">
                <div
                  className="h-full bg-red-500 rounded-full transition-all"
                  style={{ width: `${feedbackData.negativeRate * 100}%` }}
                />
              </div>
            </div>
          </div>
        </Card>

        <Card>
          <CardHeader
            title="Satisfaction Gauge"
            description="Overall user satisfaction"
          />
          <div className="flex flex-col items-center py-8">
            <div className="relative w-48 h-24 overflow-hidden">
              <div className="absolute inset-0 bg-gradient-to-r from-red-500 via-yellow-500 to-green-500 rounded-t-full" />
              <div className="absolute bottom-0 left-1/2 w-2 h-20 bg-foreground origin-bottom -translate-x-1/2"
                style={{
                  transform: `translateX(-50%) rotate(${(feedbackData.satisfactionScore - 50) * 1.8}deg)`,
                }}
              />
            </div>
            <p className={cn("text-4xl font-bold mt-4", getSatisfactionColor(feedbackData.satisfactionScore))}>
              {feedbackData.satisfactionScore.toFixed(1)}%
            </p>
            <p className="text-sm text-muted-foreground mt-1">
              {feedbackData.satisfactionScore >= 70 ? 'Great!' : feedbackData.satisfactionScore >= 50 ? 'Good' : 'Needs Improvement'}
            </p>
          </div>
        </Card>
      </div>
    </div>
  )
}
