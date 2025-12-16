import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { format } from 'date-fns'
import {
  Search,
  Filter,
  RefreshCw,
  ChevronDown,
  ChevronUp,
  Clock,
  Coins,
  User,
  MessageSquare,
} from 'lucide-react'
import { Card } from '@/components/Card'
import { DataTable, StatusBadge, KeywordBadge } from '@/components/DataTable'
import { executionsApi } from '@/lib/api'
import { formatDuration, cn } from '@/lib/utils'
import type { ExecutionRecord } from '@/types'

export function Executions() {
  const [searchTerm, setSearchTerm] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const { data: executions, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['executions'],
    queryFn: () => executionsApi.getRecent(100),
    refetchInterval: 30000,
  })

  // Use API data directly
  const executionList = executions || []

  const filteredExecutions = executionList.filter((exec) => {
    const matchesSearch =
      searchTerm === '' ||
      exec.prompt.toLowerCase().includes(searchTerm.toLowerCase()) ||
      exec.agentId.toLowerCase().includes(searchTerm.toLowerCase())

    const matchesStatus =
      statusFilter === 'all' || exec.status.toLowerCase() === statusFilter.toLowerCase()

    return matchesSearch && matchesStatus
  })

  const columns = [
    {
      key: 'status',
      header: 'Status',
      render: (exec: ExecutionRecord) => <StatusBadge status={exec.status} />,
      className: 'w-24',
    },
    {
      key: 'prompt',
      header: 'Prompt',
      render: (exec: ExecutionRecord) => (
        <div className="max-w-md">
          <p className="truncate font-medium">{exec.prompt}</p>
          <p className="text-xs text-muted-foreground mt-1">
            {format(new Date(exec.createdAt), 'MMM dd, HH:mm:ss')}
          </p>
        </div>
      ),
    },
    {
      key: 'agentId',
      header: 'Agent',
      render: (exec: ExecutionRecord) => <KeywordBadge keyword={exec.agentId} />,
      className: 'w-32',
    },
    {
      key: 'durationMs',
      header: 'Duration',
      render: (exec: ExecutionRecord) => (
        <span className="text-sm">{formatDuration(exec.durationMs)}</span>
      ),
      className: 'w-24',
    },
    {
      key: 'tokens',
      header: 'Tokens',
      render: (exec: ExecutionRecord) => (
        <span className="text-sm text-muted-foreground">
          {(exec.inputTokens ?? 0) + (exec.outputTokens ?? 0)}
        </span>
      ),
      className: 'w-20',
    },
    {
      key: 'expand',
      header: '',
      render: (exec: ExecutionRecord) => (
        <button
          onClick={(e) => {
            e.stopPropagation()
            setExpandedId(expandedId === exec.id ? null : exec.id)
          }}
          className="p-1 hover:bg-muted rounded"
        >
          {expandedId === exec.id ? (
            <ChevronUp className="h-4 w-4" />
          ) : (
            <ChevronDown className="h-4 w-4" />
          )}
        </button>
      ),
      className: 'w-10',
    },
  ]

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Executions</h1>
          <p className="text-muted-foreground mt-1">
            View and manage execution history
          </p>
        </div>
        <button
          onClick={() => refetch()}
          disabled={isFetching}
          className={cn(
            'flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground',
            'hover:bg-primary/90 transition-colors',
            isFetching && 'opacity-50 cursor-not-allowed'
          )}
        >
          <RefreshCw className={cn('h-4 w-4', isFetching && 'animate-spin')} />
          Refresh
        </button>
      </div>

      {/* Filters */}
      <Card>
        <div className="flex flex-col sm:flex-row gap-4">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <input
              type="text"
              placeholder="Search by prompt or agent..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pl-10 pr-4 py-2 rounded-lg border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
            />
          </div>
          <div className="flex items-center gap-2">
            <Filter className="h-4 w-4 text-muted-foreground" />
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="px-3 py-2 rounded-lg border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
            >
              <option value="all">All Status</option>
              <option value="success">Success</option>
              <option value="error">Error</option>
              <option value="running">Running</option>
              <option value="pending">Pending</option>
            </select>
          </div>
        </div>
      </Card>

      {/* Table */}
      <Card className="p-0 overflow-hidden">
        {isLoading ? (
          <div className="flex items-center justify-center h-64">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
          </div>
        ) : (
          <DataTable
            data={filteredExecutions}
            columns={columns}
            emptyMessage="No executions found"
            expandedId={expandedId}
            renderExpanded={(exec) => (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                <div className="space-y-4">
                  <h4 className="font-semibold flex items-center gap-2">
                    <MessageSquare className="h-4 w-4" />
                    Prompt
                  </h4>
                  <p className="text-sm bg-background p-3 rounded-lg border">
                    {exec.prompt}
                  </p>

                  {exec.result && (
                    <>
                      <h4 className="font-semibold">Result</h4>
                      <div className="text-sm bg-background p-3 rounded-lg border max-h-64 overflow-y-auto">
                        <pre className="whitespace-pre-wrap break-words font-sans">
                          {exec.result}
                        </pre>
                      </div>
                    </>
                  )}

                  {exec.error && (
                    <>
                      <h4 className="font-semibold text-destructive">Error</h4>
                      <p className="text-sm bg-destructive/10 text-destructive p-3 rounded-lg border border-destructive/20">
                        {exec.error}
                      </p>
                    </>
                  )}
                </div>

                <div className="space-y-4">
                  <h4 className="font-semibold">Details</h4>
                  <div className="grid grid-cols-2 gap-3 text-sm">
                    <div className="flex items-center gap-2 p-2 bg-background rounded border">
                      <Clock className="h-4 w-4 text-muted-foreground" />
                      <span className="text-muted-foreground">Duration:</span>
                      <span className="font-medium">{formatDuration(exec.durationMs)}</span>
                    </div>
                    <div className="flex items-center gap-2 p-2 bg-background rounded border">
                      <Coins className="h-4 w-4 text-muted-foreground" />
                      <span className="text-muted-foreground">Tokens:</span>
                      <span className="font-medium">{(exec.inputTokens ?? 0) + (exec.outputTokens ?? 0)}</span>
                    </div>
                    <div className="flex items-center gap-2 p-2 bg-background rounded border">
                      <User className="h-4 w-4 text-muted-foreground" />
                      <span className="text-muted-foreground">User:</span>
                      <span className="font-medium">{exec.userId || 'N/A'}</span>
                    </div>
                    <div className="flex items-center gap-2 p-2 bg-background rounded border">
                      <span className="text-muted-foreground">Cost:</span>
                      <span className="font-medium">
                        {exec.cost ? `$${exec.cost.toFixed(4)}` : 'N/A'}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            )}
          />
        )}
      </Card>

      {/* Summary */}
      <div className="flex items-center justify-between text-sm text-muted-foreground">
        <span>Showing {filteredExecutions.length} executions</span>
        <span>Last updated: {format(new Date(), 'HH:mm:ss')}</span>
      </div>
    </div>
  )
}
