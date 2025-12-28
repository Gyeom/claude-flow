import { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  MessageSquare,
  Search,
  Filter,
  Calendar,
  CheckCircle,
  XCircle,
  Bot,
  User,
  ChevronDown,
  ChevronUp,
  Copy,
  ExternalLink,
  AlertCircle,
  RefreshCw,
  GitPullRequestArrow,
  MessageCircle,
  Layers,
  Slack,
  Hash,
} from 'lucide-react'
import { Card } from '@/components/Card'
import { StatusBadge } from '@/components/DataTable'
import { interactionsApi } from '@/lib/api'
import { formatDuration } from '@/lib/utils'
import type { Interaction, InteractionSourceStats } from '@/types'

// Source 필터 옵션 (MR Review > Slack > Chat > 기타 순서)
const SOURCE_OPTIONS = [
  { value: 'all', label: '전체', icon: Layers },
  { value: 'mr_review', label: 'MR Review', icon: GitPullRequestArrow },
  { value: 'slack', label: 'Slack', icon: Slack },
  { value: 'chat', label: 'Chat', icon: MessageCircle },
  { value: 'other', label: '기타', icon: MessageSquare },
]

// Source 정렬 순서
const SOURCE_ORDER = ['mr_review', 'slack', 'chat', 'other', 'api']

export function Interactions() {
  const [searchQuery, setSearchQuery] = useState('')
  const [sourceFilter, setSourceFilter] = useState<string>('all')
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [days, setDays] = useState(30)
  const [page, setPage] = useState(0)
  const [pageSize] = useState(50)

  // Source별 통계
  const { data: sourceStats } = useQuery({
    queryKey: ['interactions', 'stats', 'by-source', days],
    queryFn: () => interactionsApi.getStatsBySource(days),
  })


  // Interactions 조회
  const { data: interactionsData, isLoading, error, refetch } = useQuery({
    queryKey: ['interactions', sourceFilter, days, page, pageSize],
    queryFn: () => interactionsApi.getAll({
      sources: sourceFilter === 'all' ? undefined : [sourceFilter],
      days,
      page,
      size: pageSize,
    }),
  })

  const interactions = interactionsData?.items || []
  const totalCount = interactionsData?.totalCount || 0
  const totalPages = interactionsData?.totalPages || 1

  // 로컬 필터링 (검색어, 상태)
  const filteredInteractions = useMemo(() => {
    return interactions.filter(item => {
      if (statusFilter !== 'all' && item.status.toLowerCase() !== statusFilter) return false
      if (searchQuery) {
        const query = searchQuery.toLowerCase()
        const matchPrompt = item.prompt?.toLowerCase().includes(query)
        const matchResult = item.result?.toLowerCase().includes(query)
        const matchContext = item.mrContext?.toLowerCase().includes(query)
        if (!matchPrompt && !matchResult && !matchContext) return false
      }
      return true
    })
  }, [interactions, statusFilter, searchQuery])

  const formatTime = (isoString: string) => {
    const date = new Date(isoString)
    return date.toLocaleString()
  }

  const formatTimeAgo = (isoString: string) => {
    const diff = Date.now() - new Date(isoString).getTime()
    const minutes = Math.floor(diff / 60000)
    const hours = Math.floor(diff / 3600000)
    const days = Math.floor(diff / 86400000)
    if (days > 0) return `${days}d ago`
    if (hours > 0) return `${hours}h ago`
    return `${minutes}m ago`
  }

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text)
  }

  // Source 아이콘 컴포넌트
  const SourceIcon = ({ source, size = 'sm' }: { source: string; size?: 'sm' | 'lg' }) => {
    const sizeClass = size === 'lg' ? 'h-6 w-6' : 'h-4 w-4'
    switch (source) {
      case 'mr_review':
        return <GitPullRequestArrow className={`${sizeClass} text-emerald-500`} />
      case 'slack':
        return <Slack className={`${sizeClass} text-purple-500`} />
      case 'chat':
        return <MessageCircle className={`${sizeClass} text-sky-500`} />
      default:
        return <MessageSquare className={`${sizeClass} text-slate-400`} />
    }
  }

  // Source 통계 정렬 및 API를 기타로 병합
  const sortedSourceStats = useMemo(() => {
    if (!sourceStats) return []

    // API를 기타로 병합
    const merged = sourceStats.reduce((acc: InteractionSourceStats[], stat) => {
      if (stat.source === 'api') {
        const otherIdx = acc.findIndex(s => s.source === 'other')
        if (otherIdx >= 0) {
          acc[otherIdx] = {
            ...acc[otherIdx],
            count: acc[otherIdx].count + stat.count,
            successRate: (acc[otherIdx].successRate * acc[otherIdx].count + stat.successRate * stat.count) / (acc[otherIdx].count + stat.count)
          }
        } else {
          acc.push({ ...stat, source: 'other', displayName: '기타', icon: '📋' })
        }
      } else {
        acc.push(stat)
      }
      return acc
    }, [])

    // 정렬
    return merged.sort((a, b) => {
      const orderA = SOURCE_ORDER.indexOf(a.source)
      const orderB = SOURCE_ORDER.indexOf(b.source)
      return (orderA === -1 ? 999 : orderA) - (orderB === -1 ? 999 : orderB)
    })
  }, [sourceStats])

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-4">
        <AlertCircle className="h-12 w-12 text-destructive" />
        <p className="text-muted-foreground">Failed to load interactions</p>
        <button
          onClick={() => refetch()}
          className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground"
        >
          <RefreshCw className="h-4 w-4" />
          Retry
        </button>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Activity</h1>
          <p className="text-muted-foreground mt-1">
            Slack, Chat, MR Review 모든 활동 기록
          </p>
        </div>
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <MessageSquare className="h-4 w-4" />
          {totalCount.toLocaleString()} interactions
        </div>
      </div>

      {/* Source Stats */}
      {sortedSourceStats.length > 0 && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {sortedSourceStats.map((stat: InteractionSourceStats) => (
            <Card
              key={stat.source}
              className={`p-4 cursor-pointer transition-all ${
                sourceFilter === stat.source
                  ? 'ring-2 ring-primary shadow-md'
                  : 'hover:bg-muted/50 hover:shadow-sm'
              }`}
              onClick={() => setSourceFilter(
                sourceFilter === stat.source ? 'all' : stat.source
              )}
            >
              <div className="flex items-center gap-4">
                <div className={`p-3 rounded-xl ${
                  stat.source === 'mr_review' ? 'bg-emerald-500/10' :
                  stat.source === 'slack' ? 'bg-purple-500/10' :
                  stat.source === 'chat' ? 'bg-sky-500/10' :
                  'bg-slate-500/10'
                }`}>
                  <SourceIcon source={stat.source} size="lg" />
                </div>
                <div>
                  <p className="text-sm font-medium text-muted-foreground">{stat.displayName}</p>
                  <p className="text-2xl font-bold">{stat.count.toLocaleString()}</p>
                  <p className="text-xs text-muted-foreground">
                    {(stat.successRate * 100).toFixed(1)}% success
                  </p>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {/* Filters */}
      <Card className="p-4">
        <div className="flex flex-wrap gap-4">
          {/* 검색 */}
          <div className="flex-1 min-w-[200px]">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search prompts, results..."
                className="w-full pl-10 pr-4 py-2 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50"
              />
            </div>
          </div>

          {/* Source 필터 */}
          <div className="flex items-center gap-2">
            <Filter className="h-4 w-4 text-muted-foreground" />
            <select
              value={sourceFilter}
              onChange={(e) => {
                setSourceFilter(e.target.value)
                setPage(0)
              }}
              className="px-3 py-2 rounded-lg border border-border bg-background"
            >
              {SOURCE_OPTIONS.map(opt => (
                <option key={opt.value} value={opt.value}>{opt.label}</option>
              ))}
            </select>
          </div>

          {/* Status 필터 */}
          <div className="flex items-center gap-2">
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="px-3 py-2 rounded-lg border border-border bg-background"
            >
              <option value="all">All Status</option>
              <option value="success">Success</option>
              <option value="error">Error</option>
            </select>
          </div>

          {/* 기간 필터 */}
          <div className="flex items-center gap-2">
            <Calendar className="h-4 w-4 text-muted-foreground" />
            <select
              value={days}
              onChange={(e) => {
                setDays(Number(e.target.value))
                setPage(0)
              }}
              className="px-3 py-2 rounded-lg border border-border bg-background"
            >
              <option value={7}>Last 7 days</option>
              <option value={30}>Last 30 days</option>
              <option value={90}>Last 90 days</option>
            </select>
          </div>
        </div>
      </Card>

      {/* Loading */}
      {isLoading && (
        <div className="flex items-center justify-center h-32">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
        </div>
      )}

      {/* Interaction List */}
      {!isLoading && (
        <div className="space-y-3">
          {filteredInteractions.map((item: Interaction) => (
            <InteractionCard
              key={item.id}
              interaction={item}
              expanded={expandedId === item.id}
              onToggle={() => setExpandedId(expandedId === item.id ? null : item.id)}
              formatTime={formatTime}
              formatTimeAgo={formatTimeAgo}
              copyToClipboard={copyToClipboard}
              SourceIcon={SourceIcon}
            />
          ))}

          {filteredInteractions.length === 0 && (
            <Card className="p-12 text-center">
              <MessageSquare className="h-12 w-12 text-muted-foreground mx-auto mb-4" />
              <p className="text-muted-foreground">No interactions found</p>
            </Card>
          )}
        </div>
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-4">
          <button
            onClick={() => setPage(p => Math.max(0, p - 1))}
            disabled={page === 0}
            className="px-4 py-2 rounded-lg border border-border bg-background disabled:opacity-50"
          >
            Previous
          </button>
          <span className="text-sm text-muted-foreground">
            Page {page + 1} of {totalPages}
          </span>
          <button
            onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
            disabled={page >= totalPages - 1}
            className="px-4 py-2 rounded-lg border border-border bg-background disabled:opacity-50"
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}

// 개별 Interaction 카드 컴포넌트
interface InteractionCardProps {
  interaction: Interaction
  expanded: boolean
  onToggle: () => void
  formatTime: (iso: string) => string
  formatTimeAgo: (iso: string) => string
  copyToClipboard: (text: string) => void
  SourceIcon: React.FC<{ source: string; size?: 'sm' | 'lg' }>
}

function InteractionCard({
  interaction: item,
  expanded,
  onToggle,
  formatTime,
  formatTimeAgo,
  copyToClipboard,
  SourceIcon,
}: InteractionCardProps) {
  return (
    <Card className="p-0 overflow-hidden">
      {/* Summary Row */}
      <div
        className="flex items-center gap-4 p-4 cursor-pointer hover:bg-muted/50 transition-colors"
        onClick={onToggle}
      >
        {/* Source Icon */}
        <SourceIcon source={item.source} />

        {/* Status Icon */}
        {item.status === 'SUCCESS' ? (
          <CheckCircle className="h-5 w-5 text-green-500 flex-shrink-0" />
        ) : (
          <XCircle className="h-5 w-5 text-red-500 flex-shrink-0" />
        )}

        {/* Content */}
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-xs px-2 py-0.5 rounded-full bg-muted">
              {item.sourceDisplayName}
            </span>
            {item.mrIid && (
              <span className="text-xs px-2 py-0.5 rounded-full bg-orange-500/10 text-orange-600">
                MR !{item.mrIid}
              </span>
            )}
          </div>
          <p className="font-medium truncate">
            {item.source === 'mr_review' ? item.mrContext || item.prompt : item.prompt}
          </p>
          <div className="flex items-center gap-3 mt-1 text-sm text-muted-foreground">
            <span className="flex items-center gap-1">
              <Bot className="h-3 w-3" />
              {item.agentId}
            </span>
            {item.userId && (
              <span className="flex items-center gap-1">
                <User className="h-3 w-3" />
                {item.userId}
              </span>
            )}
            {item.channel && (
              <span className="flex items-center gap-1">
                <Hash className="h-3 w-3" />
                {item.channel}
              </span>
            )}
          </div>
        </div>

        {/* Feedback + Meta */}
        <div className="flex items-center gap-3 flex-shrink-0">
          {/* Feedback - 이모지 스타일 */}
          <div className="flex items-center gap-1.5 text-sm">
            <span className={`flex items-center gap-0.5 ${
              item.feedbackPositive > 0
                ? 'opacity-100'
                : 'opacity-30 grayscale'
            }`}>
              <span className="text-base">👍</span>
              <span className={item.feedbackPositive > 0 ? 'text-green-600 font-medium' : 'text-muted-foreground'}>
                {item.feedbackPositive}
              </span>
            </span>
            <span className={`flex items-center gap-0.5 ${
              item.feedbackNegative > 0
                ? 'opacity-100'
                : 'opacity-30 grayscale'
            }`}>
              <span className="text-base">👎</span>
              <span className={item.feedbackNegative > 0 ? 'text-red-600 font-medium' : 'text-muted-foreground'}>
                {item.feedbackNegative}
              </span>
            </span>
          </div>
          <span className="text-muted-foreground/30">|</span>
          <StatusBadge status={item.status} />
          <span className="text-sm text-muted-foreground">
            {formatDuration(item.durationMs)}
          </span>
          <span className="text-sm text-muted-foreground">
            {formatTimeAgo(item.createdAt)}
          </span>
          {expanded ? (
            <ChevronUp className="h-4 w-4 text-muted-foreground" />
          ) : (
            <ChevronDown className="h-4 w-4 text-muted-foreground" />
          )}
        </div>
      </div>

      {/* Expanded Details */}
      {expanded && (
        <div className="border-t border-border p-4 bg-muted/30 space-y-4">
          {/* Metadata */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
            <div>
              <p className="text-muted-foreground">ID</p>
              <div className="flex items-center gap-2">
                <code className="font-mono text-xs">{item.id.slice(0, 8)}...</code>
                <button
                  onClick={(e) => {
                    e.stopPropagation()
                    copyToClipboard(item.id)
                  }}
                  className="p-1 hover:bg-muted rounded"
                >
                  <Copy className="h-3 w-3" />
                </button>
              </div>
            </div>
            <div>
              <p className="text-muted-foreground">Created At</p>
              <p>{formatTime(item.createdAt)}</p>
            </div>
            <div>
              <p className="text-muted-foreground">Duration</p>
              <p>{formatDuration(item.durationMs)}</p>
            </div>
            <div>
              <p className="text-muted-foreground">Cost</p>
              <p>${item.cost?.toFixed(4) || '0.0000'}</p>
            </div>
          </div>

          {/* Token Usage */}
          <div className="flex items-center gap-6 text-sm">
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground">Input:</span>
              <span className="font-medium">{item.inputTokens.toLocaleString()} tokens</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground">Output:</span>
              <span className="font-medium">{item.outputTokens.toLocaleString()} tokens</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground">Total:</span>
              <span className="font-medium">
                {(item.inputTokens + item.outputTokens).toLocaleString()} tokens
              </span>
            </div>
          </div>

          {/* MR Context (for MR Reviews) */}
          {item.source === 'mr_review' && item.mrContext && (
            <div>
              <p className="text-sm text-muted-foreground mb-2">MR Context</p>
              <div className="p-3 rounded-lg bg-orange-500/5 border border-orange-500/20 max-h-64 overflow-y-auto scrollbar-visible">
                <p className="whitespace-pre-wrap text-sm break-words">{item.mrContext}</p>
              </div>
            </div>
          )}

          {/* Prompt */}
          <div>
            <p className="text-sm text-muted-foreground mb-2">
              {item.source === 'mr_review' ? 'Review Request' : 'Prompt'}
            </p>
            <div className="p-3 rounded-lg bg-background border border-border max-h-64 overflow-y-auto scrollbar-visible">
              <p className="whitespace-pre-wrap text-sm break-words">{item.prompt}</p>
            </div>
          </div>

          {/* Result or Error */}
          {item.status === 'SUCCESS' && item.result && (
            <div>
              <p className="text-sm text-muted-foreground mb-2">
                {item.source === 'mr_review' ? 'Review Content' : 'Result'}
              </p>
              <div className="p-3 rounded-lg bg-background border border-border max-h-96 overflow-y-auto scrollbar-visible">
                <pre className="whitespace-pre-wrap text-sm font-mono">{item.result}</pre>
              </div>
            </div>
          )}

          {item.status === 'ERROR' && item.error && (
            <div>
              <p className="text-sm text-red-500 mb-2">Error</p>
              <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/30">
                <p className="text-sm text-red-500">{item.error}</p>
              </div>
            </div>
          )}

          {/* Feedback Details */}
          {item.feedbacks && item.feedbacks.length > 0 && (
            <div>
              <p className="text-sm text-muted-foreground mb-2">Feedback</p>
              <div className="flex flex-wrap gap-2">
                {item.feedbacks.map(fb => (
                  <div
                    key={fb.id}
                    className={`px-3 py-1 rounded-full text-xs ${
                      fb.reaction === 'thumbsup' || fb.reaction === '+1'
                        ? 'bg-green-500/10 text-green-600'
                        : 'bg-red-500/10 text-red-600'
                    }`}
                  >
                    {fb.reaction === 'thumbsup' || fb.reaction === '+1' ? '👍' : '👎'}{' '}
                    {fb.userId}
                    {fb.isVerified && ' ✓'}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* External Links */}
          {item.mrIid && item.projectId && (
            <div className="flex items-center gap-2 text-sm">
              <ExternalLink className="h-4 w-4 text-muted-foreground" />
              <span className="text-muted-foreground">GitLab:</span>
              <code className="font-mono text-xs">
                {item.projectId} !{item.mrIid}
              </code>
            </div>
          )}
        </div>
      )}
    </Card>
  )
}
