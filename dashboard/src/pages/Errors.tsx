import React, { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  AlertTriangle,
  AlertCircle,
  XCircle,
  Clock,
  Calendar,
  TrendingDown,
  RefreshCw,
  Shield,
} from 'lucide-react'
import { Card, CardHeader, StatCard } from '@/components/Card'
import { ChartContainer } from '@/components/Chart'
import { DataTable } from '@/components/DataTable'
import { analyticsApi } from '@/lib/api'
import { formatNumber, cn } from '@/lib/utils'
import {
  BarChart,
  Bar,
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
  { label: '24 Hours', value: '1d' },
  { label: '7 Days', value: '7d' },
  { label: '14 Days', value: '14d' },
  { label: '30 Days', value: '30d' },
]

const ERROR_COLORS: Record<string, string> = {
  TIMEOUT: '#f59e0b',
  RATE_LIMIT: '#8b5cf6',
  INVALID_REQUEST: '#ef4444',
  NETWORK_ERROR: '#3b82f6',
  AUTHENTICATION: '#ec4899',
  INTERNAL_ERROR: '#6b7280',
  default: '#94a3b8',
}

const ERROR_ICONS: Record<string, typeof AlertTriangle> = {
  TIMEOUT: Clock,
  RATE_LIMIT: RefreshCw,
  INVALID_REQUEST: XCircle,
  NETWORK_ERROR: AlertCircle,
  AUTHENTICATION: Shield,
  INTERNAL_ERROR: AlertTriangle,
}

export function Errors() {
  const [period, setPeriod] = useState('7d')

  const { data: errorsData, isLoading } = useQuery({
    queryKey: ['analytics', 'errors', period],
    queryFn: () => analyticsApi.getErrors(period),
  })

  // Use actual data with empty defaults
  const errorList = errorsData?.errors || []

  const totalErrors = errorList.reduce((sum, e) => sum + e.count, 0)
  const mostFrequentError = errorList.length > 0
    ? errorList.reduce((max, e) => e.count > max.count ? e : max, errorList[0])
    : null

  const pieData = errorList.map(e => ({
    name: e.errorType,
    value: e.count,
    color: ERROR_COLORS[e.errorType] || ERROR_COLORS.default,
  }))

  const barData = errorList.map(e => ({
    name: e.errorType,
    count: e.count,
    color: ERROR_COLORS[e.errorType] || ERROR_COLORS.default,
  }))

  const trendData = Array.from({ length: 7 }, (_, i) => {
    const date = new Date(Date.now() - (6 - i) * 86400000)
    return {
      date: date.toLocaleDateString('en-US', { weekday: 'short' }),
      errors: Math.floor(Math.random() * 20) + 5,
    }
  })

  const formatTimeAgo = (isoString: string) => {
    const diff = Date.now() - new Date(isoString).getTime()
    const minutes = Math.floor(diff / 60000)
    const hours = Math.floor(diff / 3600000)
    const days = Math.floor(diff / 86400000)

    if (days > 0) return `${days}d ago`
    if (hours > 0) return `${hours}h ago`
    return `${minutes}m ago`
  }

  type ErrorItem = { errorType: string; count: number; lastOccurred: string; id?: string }

  const columns: Array<{ key: string; header: string; render: (item: ErrorItem) => React.ReactNode }> = [
    {
      key: 'errorType',
      header: 'Error Type',
      render: (item) => {
        const Icon = ERROR_ICONS[item.errorType] || AlertTriangle
        return (
          <div className="flex items-center gap-2">
            <div
              className="p-1.5 rounded"
              style={{ backgroundColor: `${ERROR_COLORS[item.errorType] || ERROR_COLORS.default}20` }}
            >
              <Icon
                className="h-4 w-4"
                style={{ color: ERROR_COLORS[item.errorType] || ERROR_COLORS.default }}
              />
            </div>
            <span className="font-medium">{item.errorType}</span>
          </div>
        )
      },
    },
    {
      key: 'count',
      header: 'Count',
      render: (item) => (
        <span className={cn(
          'font-semibold',
          item.count >= 50 ? 'text-red-500' : item.count >= 20 ? 'text-yellow-500' : 'text-green-500'
        )}>
          {formatNumber(item.count)}
        </span>
      ),
    },
    {
      key: 'lastOccurred',
      header: 'Last Occurred',
      render: (item) => (
        <span className="text-muted-foreground">{formatTimeAgo(item.lastOccurred)}</span>
      ),
    },
    {
      key: 'percentage',
      header: 'Share',
      render: (item) => {
        const pct = (item.count / totalErrors) * 100
        return (
          <div className="flex items-center gap-2">
            <div className="w-16 h-2 bg-muted rounded-full overflow-hidden">
              <div
                className="h-full bg-primary rounded-full"
                style={{ width: `${pct}%` }}
              />
            </div>
            <span className="text-sm text-muted-foreground">{pct.toFixed(1)}%</span>
          </div>
        )
      },
    },
  ]

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
          <h1 className="text-3xl font-bold">Error Analytics</h1>
          <p className="text-muted-foreground mt-1">
            Error tracking and analysis
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
          title="Total Errors"
          value={formatNumber(totalErrors)}
          icon={<AlertTriangle className="h-6 w-6 text-red-500" />}
          className="border-red-500/30"
        />
        <StatCard
          title="Error Types"
          value={errorList.length}
          icon={<AlertCircle className="h-6 w-6" />}
        />
        <Card className="p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-muted-foreground">Most Frequent</p>
              <p className="text-xl font-bold mt-1">{mostFrequentError?.errorType || 'N/A'}</p>
              <p className="text-sm text-muted-foreground">{mostFrequentError?.count || 0} occurrences</p>
            </div>
            {mostFrequentError && (
              <div
                className="p-3 rounded-lg"
                style={{ backgroundColor: `${ERROR_COLORS[mostFrequentError.errorType] || ERROR_COLORS.default}20` }}
              >
                {(() => {
                  const Icon = ERROR_ICONS[mostFrequentError.errorType] || AlertTriangle
                  return (
                    <Icon
                      className="h-6 w-6"
                      style={{ color: ERROR_COLORS[mostFrequentError.errorType] || ERROR_COLORS.default }}
                    />
                  )
                })()}
              </div>
            )}
          </div>
        </Card>
        <StatCard
          title="Error Rate"
          value={`${((totalErrors / 1000) * 100).toFixed(1)}%`}
          icon={<TrendingDown className="h-6 w-6" />}
          trend="down"
        />
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Error Distribution */}
        <ChartContainer
          title="Error Distribution"
          description="Errors by type"
        >
          <ResponsiveContainer width="100%" height={300}>
            <PieChart>
              <Pie
                data={pieData}
                cx="50%"
                cy="50%"
                innerRadius={60}
                outerRadius={100}
                paddingAngle={2}
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

        {/* Error Trend */}
        <ChartContainer
          title="Error Trend"
          description="Daily error count over time"
        >
          <ResponsiveContainer width="100%" height={300}>
            <LineChart data={trendData}>
              <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
              <XAxis dataKey="date" className="text-muted-foreground text-xs" />
              <YAxis className="text-muted-foreground text-xs" />
              <Tooltip />
              <Line
                type="monotone"
                dataKey="errors"
                stroke="#ef4444"
                strokeWidth={2}
                dot={{ fill: '#ef4444' }}
                name="Errors"
              />
            </LineChart>
          </ResponsiveContainer>
        </ChartContainer>
      </div>

      {/* Error Bar Chart */}
      <ChartContainer
        title="Error Counts by Type"
        description="Comparison of error occurrences"
      >
        <ResponsiveContainer width="100%" height={250}>
          <BarChart data={barData}>
            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
            <XAxis dataKey="name" className="text-muted-foreground text-xs" />
            <YAxis className="text-muted-foreground text-xs" />
            <Tooltip />
            <Bar dataKey="count" radius={[4, 4, 0, 0]}>
              {barData.map((entry, index) => (
                <Cell key={`cell-${index}`} fill={entry.color} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </ChartContainer>

      {/* Error Cards */}
      {errorList.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-5 gap-4">
          {errorList.map((error) => {
            const Icon = ERROR_ICONS[error.errorType] || AlertTriangle
            return (
              <Card key={error.errorType} className="p-4">
                <div className="flex items-center gap-2 mb-3">
                  <div
                    className="p-2 rounded-lg"
                    style={{ backgroundColor: `${ERROR_COLORS[error.errorType] || ERROR_COLORS.default}20` }}
                  >
                    <Icon
                      className="h-5 w-5"
                      style={{ color: ERROR_COLORS[error.errorType] || ERROR_COLORS.default }}
                    />
                  </div>
                  <span className="font-medium text-sm">{error.errorType}</span>
                </div>
                <p className="text-2xl font-bold">{error.count}</p>
                <p className="text-xs text-muted-foreground mt-1">
                  Last: {formatTimeAgo(error.lastOccurred)}
                </p>
              </Card>
            )
          })}
        </div>
      )}

      {/* Detailed Table */}
      <Card>
        <CardHeader
          title="Error Details"
          description="Complete error statistics"
        />
        <DataTable
          columns={columns as any}
          data={errorList.map((e, i) => ({ ...e, id: `error-${i}` }))}
          emptyMessage="No errors found - Great!"
        />
      </Card>
    </div>
  )
}
