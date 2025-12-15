import React, { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Cpu,
  Zap,
  DollarSign,
  TrendingUp,
  Calendar,
} from 'lucide-react'
import { Card, CardHeader, StatCard } from '@/components/Card'
import { ChartContainer } from '@/components/Chart'
import { DataTable } from '@/components/DataTable'
import { analyticsApi } from '@/lib/api'
import { formatNumber, formatCost, formatPercent, cn } from '@/lib/utils'
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
} from 'recharts'

const PERIODS = [
  { label: '7 Days', value: '7d' },
  { label: '14 Days', value: '14d' },
  { label: '30 Days', value: '30d' },
]

const MODEL_COLORS: Record<string, string> = {
  'claude-3-5-sonnet-20241022': '#8b5cf6',
  'claude-3-opus-20240229': '#f59e0b',
  'claude-3-sonnet-20240229': '#3b82f6',
  'claude-3-haiku-20240307': '#10b981',
  'gpt-4': '#ef4444',
  'gpt-4-turbo': '#f97316',
  'gpt-3.5-turbo': '#6366f1',
  default: '#94a3b8',
}

export function Models() {
  const [period, setPeriod] = useState('7d')

  const { data: modelsData, isLoading } = useQuery({
    queryKey: ['analytics', 'models', period],
    queryFn: () => analyticsApi.getModels(period),
  })

  // Use actual data with empty defaults
  const modelList = modelsData?.models || []

  const totalRequests = modelList.reduce((sum, m) => sum + m.requests, 0)
  const totalCost = modelList.reduce((sum, m) => sum + m.costUsd, 0)
  const totalTokens = modelList.reduce((sum, m) => sum + m.totalTokens, 0)
  const avgSuccessRate = totalRequests > 0
    ? modelList.reduce((sum, m) => sum + m.successRate * m.requests, 0) / totalRequests
    : 0

  const pieData = modelList.map(m => ({
    name: m.model.split('-').slice(-2).join('-'),
    value: m.requests,
    fullName: m.model,
    color: MODEL_COLORS[m.model] || MODEL_COLORS.default,
  }))

  const costBarData = modelList.map(m => ({
    name: m.model.split('-').slice(-2).join('-'),
    cost: m.costUsd,
    requests: m.requests,
    color: MODEL_COLORS[m.model] || MODEL_COLORS.default,
  }))

  type ModelItem = { model: string; requests: number; avgDurationMs: number; totalTokens: number; successRate: number; costUsd: number; id?: string }

  const columns: Array<{ key: string; header: string; render: (item: ModelItem) => React.ReactNode }> = [
    {
      key: 'model',
      header: 'Model',
      render: (item) => (
        <div className="flex items-center gap-2">
          <div
            className="w-3 h-3 rounded-full"
            style={{ backgroundColor: MODEL_COLORS[item.model] || MODEL_COLORS.default }}
          />
          <span className="font-medium">{item.model}</span>
        </div>
      ),
    },
    {
      key: 'requests',
      header: 'Requests',
      render: (item) => formatNumber(item.requests),
    },
    {
      key: 'avgDurationMs',
      header: 'Avg Duration',
      render: (item) => `${(item.avgDurationMs / 1000).toFixed(2)}s`,
    },
    {
      key: 'totalTokens',
      header: 'Total Tokens',
      render: (item) => formatNumber(item.totalTokens),
    },
    {
      key: 'successRate',
      header: 'Success Rate',
      render: (item) => (
        <span className={cn(
          item.successRate >= 0.95 ? 'text-green-500' : item.successRate >= 0.9 ? 'text-yellow-500' : 'text-red-500'
        )}>
          {formatPercent(item.successRate)}
        </span>
      ),
    },
    {
      key: 'costUsd',
      header: 'Cost',
      render: (item) => formatCost(item.costUsd),
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
          <h1 className="text-3xl font-bold">Model Analytics</h1>
          <p className="text-muted-foreground mt-1">
            AI model usage and performance metrics
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
          title="Total Requests"
          value={formatNumber(totalRequests)}
          icon={<Zap className="h-6 w-6" />}
        />
        <StatCard
          title="Total Cost"
          value={formatCost(totalCost)}
          icon={<DollarSign className="h-6 w-6" />}
        />
        <StatCard
          title="Total Tokens"
          value={formatNumber(totalTokens)}
          icon={<Cpu className="h-6 w-6" />}
        />
        <StatCard
          title="Avg Success Rate"
          value={formatPercent(avgSuccessRate)}
          icon={<TrendingUp className="h-6 w-6" />}
          trend="up"
        />
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Request Distribution */}
        <ChartContainer
          title="Request Distribution"
          description="Requests by model"
        >
          <ResponsiveContainer width="100%" height={300}>
            <PieChart>
              <Pie
                data={pieData}
                cx="50%"
                cy="50%"
                outerRadius={100}
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
        </ChartContainer>

        {/* Cost by Model */}
        <ChartContainer
          title="Cost by Model"
          description="Total cost breakdown"
        >
          <ResponsiveContainer width="100%" height={300}>
            <BarChart data={costBarData} layout="vertical">
              <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
              <XAxis type="number" tickFormatter={(v) => `$${v}`} />
              <YAxis type="category" dataKey="name" width={100} />
              <Tooltip formatter={(value: number) => formatCost(value)} />
              <Bar dataKey="cost" radius={[0, 4, 4, 0]}>
                {costBarData.map((entry, index) => (
                  <Cell key={`cell-${index}`} fill={entry.color} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </ChartContainer>
      </div>

      {/* Model Cards */}
      {modelList.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {modelList.map((model) => (
            <Card key={model.model} className="p-6">
              <div className="flex items-center gap-3 mb-4">
                <div
                  className="w-4 h-4 rounded-full"
                  style={{ backgroundColor: MODEL_COLORS[model.model] || MODEL_COLORS.default }}
                />
                <h3 className="font-semibold text-sm truncate">{model.model}</h3>
              </div>
              <div className="grid grid-cols-2 gap-4 text-sm">
                <div>
                  <p className="text-muted-foreground">Requests</p>
                  <p className="font-semibold">{formatNumber(model.requests)}</p>
                </div>
                <div>
                  <p className="text-muted-foreground">Cost</p>
                  <p className="font-semibold">{formatCost(model.costUsd)}</p>
                </div>
                <div>
                  <p className="text-muted-foreground">Avg Duration</p>
                  <p className="font-semibold">{(model.avgDurationMs / 1000).toFixed(2)}s</p>
                </div>
                <div>
                  <p className="text-muted-foreground">Success Rate</p>
                  <p className={cn(
                    "font-semibold",
                    model.successRate >= 0.95 ? 'text-green-500' : 'text-yellow-500'
                  )}>
                    {formatPercent(model.successRate)}
                  </p>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {/* Detailed Table */}
      <Card>
        <CardHeader
          title="Model Details"
          description="Complete model usage statistics"
        />
        <DataTable
          columns={columns as any}
          data={modelList.map((m, i) => ({ ...m, id: `model-${i}` }))}
          emptyMessage="No model data available"
        />
      </Card>
    </div>
  )
}
