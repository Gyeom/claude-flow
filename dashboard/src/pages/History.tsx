import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  History as HistoryIcon,
  Search,
  Filter,
  Calendar,
  CheckCircle,
  XCircle,
  Bot,
  User,
  MessageSquare,
  ChevronDown,
  ChevronUp,
  Copy,
  ExternalLink,
  AlertCircle,
  RefreshCw,
} from 'lucide-react'
import { Card } from '@/components/Card'
import { StatusBadge } from '@/components/DataTable'
import { executionsApi } from '@/lib/api'
import { formatDuration } from '@/lib/utils'

export function History() {
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [limit, setLimit] = useState(50)
  const queryClient = useQueryClient()

  const { data: executions, isLoading, error } = useQuery({
    queryKey: ['executions', 'recent', limit],
    queryFn: () => executionsApi.getRecent(limit),
  })

  // Use API data directly
  const executionList = executions || []

  const filteredExecutions = executionList.filter(exec => {
    if (statusFilter !== 'all' && exec.status.toLowerCase() !== statusFilter) return false
    if (searchQuery && !exec.prompt.toLowerCase().includes(searchQuery.toLowerCase())) return false
    return true
  })

  const formatTime = (isoString: string) => {
    const date = new Date(isoString)
    return date.toLocaleString()
  }

  const formatTimeAgo = (isoString: string) => {
    const diff = Date.now() - new Date(isoString).getTime()
    const minutes = Math.floor(diff / 60000)
    const hours = Math.floor(diff / 3600000)
    if (hours > 0) return `${hours}h ago`
    return `${minutes}m ago`
  }

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text)
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-4">
        <AlertCircle className="h-12 w-12 text-destructive" />
        <p className="text-muted-foreground">Failed to load execution history</p>
        <button
          onClick={() => queryClient.invalidateQueries({ queryKey: ['executions'] })}
          className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground"
        >
          <RefreshCw className="h-4 w-4" />
          Retry
        </button>
      </div>
    )
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Execution History</h1>
          <p className="text-muted-foreground mt-1">
            Detailed log of all executions
          </p>
        </div>
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <HistoryIcon className="h-4 w-4" />
          {filteredExecutions.length} executions
        </div>
      </div>

      {/* Filters */}
      <Card className="p-4">
        <div className="flex flex-wrap gap-4">
          <div className="flex-1 min-w-[200px]">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search prompts..."
                className="w-full pl-10 pr-4 py-2 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50"
              />
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Filter className="h-4 w-4 text-muted-foreground" />
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="px-3 py-2 rounded-lg border border-border bg-background"
            >
              <option value="all">All Status</option>
              <option value="success">Success</option>
              <option value="error">Error</option>
              <option value="running">Running</option>
            </select>
          </div>
          <div className="flex items-center gap-2">
            <Calendar className="h-4 w-4 text-muted-foreground" />
            <select
              value={limit}
              onChange={(e) => setLimit(Number(e.target.value))}
              className="px-3 py-2 rounded-lg border border-border bg-background"
            >
              <option value={20}>Last 20</option>
              <option value={50}>Last 50</option>
              <option value={100}>Last 100</option>
            </select>
          </div>
        </div>
      </Card>

      {/* Execution List */}
      <div className="space-y-3">
        {filteredExecutions.map((exec) => (
          <Card key={exec.id} className="p-0 overflow-hidden">
            {/* Summary Row */}
            <div
              className="flex items-center gap-4 p-4 cursor-pointer hover:bg-muted/50 transition-colors"
              onClick={() => setExpandedId(expandedId === exec.id ? null : exec.id)}
            >
              {exec.status === 'SUCCESS' ? (
                <CheckCircle className="h-5 w-5 text-green-500 flex-shrink-0" />
              ) : (
                <XCircle className="h-5 w-5 text-red-500 flex-shrink-0" />
              )}

              <div className="flex-1 min-w-0">
                <p className="font-medium truncate">{exec.prompt}</p>
                <div className="flex items-center gap-3 mt-1 text-sm text-muted-foreground">
                  <span className="flex items-center gap-1">
                    <Bot className="h-3 w-3" />
                    {exec.agentId}
                  </span>
                  {exec.userId && (
                    <span className="flex items-center gap-1">
                      <User className="h-3 w-3" />
                      {exec.userId}
                    </span>
                  )}
                  {exec.channel && (
                    <span className="flex items-center gap-1">
                      <MessageSquare className="h-3 w-3" />
                      {exec.channel}
                    </span>
                  )}
                </div>
              </div>

              <div className="flex items-center gap-4 flex-shrink-0">
                <StatusBadge status={exec.status} />
                <span className="text-sm text-muted-foreground">
                  {formatDuration(exec.durationMs)}
                </span>
                <span className="text-sm text-muted-foreground">
                  {formatTimeAgo(exec.createdAt)}
                </span>
                {expandedId === exec.id ? (
                  <ChevronUp className="h-4 w-4 text-muted-foreground" />
                ) : (
                  <ChevronDown className="h-4 w-4 text-muted-foreground" />
                )}
              </div>
            </div>

            {/* Expanded Details */}
            {expandedId === exec.id && (
              <div className="border-t border-border p-4 bg-muted/30 space-y-4">
                {/* Metadata */}
                <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
                  <div>
                    <p className="text-muted-foreground">Execution ID</p>
                    <div className="flex items-center gap-2">
                      <code className="font-mono">{exec.id}</code>
                      <button
                        onClick={() => copyToClipboard(exec.id)}
                        className="p-1 hover:bg-muted rounded"
                      >
                        <Copy className="h-3 w-3" />
                      </button>
                    </div>
                  </div>
                  <div>
                    <p className="text-muted-foreground">Created At</p>
                    <p>{formatTime(exec.createdAt)}</p>
                  </div>
                  <div>
                    <p className="text-muted-foreground">Duration</p>
                    <p>{formatDuration(exec.durationMs)}</p>
                  </div>
                  <div>
                    <p className="text-muted-foreground">Cost</p>
                    <p>${exec.cost?.toFixed(4) || '0.0000'}</p>
                  </div>
                </div>

                {/* Token Usage */}
                <div className="flex items-center gap-6 text-sm">
                  <div className="flex items-center gap-2">
                    <span className="text-muted-foreground">Input:</span>
                    <span className="font-medium">{(exec.inputTokens ?? 0).toLocaleString()} tokens</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-muted-foreground">Output:</span>
                    <span className="font-medium">{(exec.outputTokens ?? 0).toLocaleString()} tokens</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-muted-foreground">Total:</span>
                    <span className="font-medium">{((exec.inputTokens ?? 0) + (exec.outputTokens ?? 0)).toLocaleString()} tokens</span>
                  </div>
                </div>

                {/* Prompt */}
                <div>
                  <p className="text-sm text-muted-foreground mb-2">Prompt</p>
                  <div className="p-3 rounded-lg bg-background border border-border">
                    <p className="whitespace-pre-wrap text-sm">{exec.prompt}</p>
                  </div>
                </div>

                {/* Result or Error */}
                {exec.status === 'SUCCESS' && exec.result && (
                  <div>
                    <p className="text-sm text-muted-foreground mb-2">Result</p>
                    <div className="p-3 rounded-lg bg-background border border-border max-h-64 overflow-auto">
                      <pre className="whitespace-pre-wrap text-sm font-mono">{exec.result}</pre>
                    </div>
                  </div>
                )}

                {exec.status === 'ERROR' && exec.error && (
                  <div>
                    <p className="text-sm text-red-500 mb-2">Error</p>
                    <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/30">
                      <p className="text-sm text-red-500">{exec.error}</p>
                    </div>
                  </div>
                )}

                {/* Thread Link */}
                {exec.threadTs && (
                  <div className="flex items-center gap-2 text-sm">
                    <ExternalLink className="h-4 w-4 text-muted-foreground" />
                    <span className="text-muted-foreground">Thread:</span>
                    <code className="font-mono text-xs">{exec.threadTs}</code>
                  </div>
                )}
              </div>
            )}
          </Card>
        ))}

        {filteredExecutions.length === 0 && (
          <Card className="p-12 text-center">
            <HistoryIcon className="h-12 w-12 text-muted-foreground mx-auto mb-4" />
            <p className="text-muted-foreground">No executions found</p>
          </Card>
        )}
      </div>
    </div>
  )
}
