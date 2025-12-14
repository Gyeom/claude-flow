import {
  AreaChart,
  Area,
  BarChart,
  Bar,
  LineChart,
  Line,
  PieChart,
  Pie,
  Cell,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts'
import { Card, CardHeader } from './Card'

interface ChartContainerProps {
  title: string
  description?: string
  children: React.ReactNode
  className?: string
}

export function ChartContainer({ title, description, children, className }: ChartContainerProps) {
  return (
    <Card className={className}>
      <CardHeader title={title} description={description} />
      <div className="h-[300px] w-full">
        {children}
      </div>
    </Card>
  )
}

// Hourly Trend Chart
interface HourlyTrendData {
  hour: number
  count: number
}

export function HourlyTrendChart({ data }: { data: HourlyTrendData[] }) {
  const formattedData = data.map(d => ({
    ...d,
    label: `${d.hour}:00`,
  }))

  return (
    <ResponsiveContainer width="100%" height="100%">
      <AreaChart data={formattedData}>
        <defs>
          <linearGradient id="colorCount" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor="hsl(var(--primary))" stopOpacity={0.3} />
            <stop offset="95%" stopColor="hsl(var(--primary))" stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
        <XAxis
          dataKey="label"
          stroke="hsl(var(--muted-foreground))"
          fontSize={12}
          tickLine={false}
        />
        <YAxis
          stroke="hsl(var(--muted-foreground))"
          fontSize={12}
          tickLine={false}
          axisLine={false}
        />
        <Tooltip
          contentStyle={{
            backgroundColor: 'hsl(var(--card))',
            border: '1px solid hsl(var(--border))',
            borderRadius: '8px',
          }}
        />
        <Area
          type="monotone"
          dataKey="count"
          stroke="hsl(var(--primary))"
          fill="url(#colorCount)"
          strokeWidth={2}
        />
      </AreaChart>
    </ResponsiveContainer>
  )
}

// Agent Performance Bar Chart
interface AgentPerformanceData {
  name: string
  executions: number
  successRate: number
}

export function AgentPerformanceChart({ data }: { data: AgentPerformanceData[] }) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={data} layout="vertical">
        <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
        <XAxis type="number" stroke="hsl(var(--muted-foreground))" fontSize={12} />
        <YAxis
          type="category"
          dataKey="name"
          stroke="hsl(var(--muted-foreground))"
          fontSize={12}
          width={100}
        />
        <Tooltip
          contentStyle={{
            backgroundColor: 'hsl(var(--card))',
            border: '1px solid hsl(var(--border))',
            borderRadius: '8px',
          }}
        />
        <Bar dataKey="executions" fill="hsl(var(--primary))" radius={[0, 4, 4, 0]} />
      </BarChart>
    </ResponsiveContainer>
  )
}

// Success Rate Pie Chart
interface SuccessRateData {
  name: string
  value: number
  color: string
}

export function SuccessRatePieChart({ successRate }: { successRate: number }) {
  const data: SuccessRateData[] = [
    { name: 'Success', value: successRate * 100, color: '#22c55e' },
    { name: 'Failed', value: (1 - successRate) * 100, color: '#ef4444' },
  ]

  return (
    <ResponsiveContainer width="100%" height="100%">
      <PieChart>
        <Pie
          data={data}
          cx="50%"
          cy="50%"
          innerRadius={60}
          outerRadius={100}
          paddingAngle={2}
          dataKey="value"
        >
          {data.map((entry, index) => (
            <Cell key={`cell-${index}`} fill={entry.color} />
          ))}
        </Pie>
        <Tooltip
          contentStyle={{
            backgroundColor: 'hsl(var(--card))',
            border: '1px solid hsl(var(--border))',
            borderRadius: '8px',
          }}
          formatter={(value: number) => `${value.toFixed(1)}%`}
        />
        <Legend />
      </PieChart>
    </ResponsiveContainer>
  )
}

// Token Usage Over Time
interface TokenUsageData {
  date: string
  input: number
  output: number
}

export function TokenUsageChart({ data }: { data: TokenUsageData[] }) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <LineChart data={data}>
        <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
        <XAxis
          dataKey="date"
          stroke="hsl(var(--muted-foreground))"
          fontSize={12}
          tickLine={false}
        />
        <YAxis
          stroke="hsl(var(--muted-foreground))"
          fontSize={12}
          tickLine={false}
          axisLine={false}
        />
        <Tooltip
          contentStyle={{
            backgroundColor: 'hsl(var(--card))',
            border: '1px solid hsl(var(--border))',
            borderRadius: '8px',
          }}
        />
        <Legend />
        <Line
          type="monotone"
          dataKey="input"
          stroke="#8b5cf6"
          strokeWidth={2}
          dot={false}
          name="Input Tokens"
        />
        <Line
          type="monotone"
          dataKey="output"
          stroke="#06b6d4"
          strokeWidth={2}
          dot={false}
          name="Output Tokens"
        />
      </LineChart>
    </ResponsiveContainer>
  )
}

// Feedback Distribution
export function FeedbackChart({ thumbsUp, thumbsDown }: { thumbsUp: number; thumbsDown: number }) {
  const data = [
    { name: 'Positive', value: thumbsUp, color: '#22c55e' },
    { name: 'Negative', value: thumbsDown, color: '#ef4444' },
  ]

  const total = thumbsUp + thumbsDown

  return (
    <ResponsiveContainer width="100%" height="100%">
      <PieChart>
        <Pie
          data={data}
          cx="50%"
          cy="50%"
          innerRadius={50}
          outerRadius={80}
          paddingAngle={2}
          dataKey="value"
          label={({ name, value }) => `${name}: ${value}`}
        >
          {data.map((entry, index) => (
            <Cell key={`cell-${index}`} fill={entry.color} />
          ))}
        </Pie>
        <Tooltip
          contentStyle={{
            backgroundColor: 'hsl(var(--card))',
            border: '1px solid hsl(var(--border))',
            borderRadius: '8px',
          }}
          formatter={(value: number) => [
            `${value} (${total > 0 ? ((value / total) * 100).toFixed(1) : 0}%)`,
            '',
          ]}
        />
      </PieChart>
    </ResponsiveContainer>
  )
}
