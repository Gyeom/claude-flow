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
  Copy,
  ExternalLink,
  AlertCircle,
  RefreshCw,
  GitPullRequestArrow,
  MessageCircle,
  Layers,
  Slack,
  Hash,
  Crown,
  ThumbsUp,
  ThumbsDown,
  Star,
} from 'lucide-react'
import { Card } from '@/components/Card'
import { StatusBadge } from '@/components/DataTable'
import { AdminFeedbackPanel } from '@/components/chat/AdminFeedbackPanel'
import { interactionsApi } from '@/lib/api'
import { formatDuration, cn } from '@/lib/utils'
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

// 평가 상태 필터 옵션
const REVIEW_STATUS_OPTIONS = [
  { value: 'all', label: '전체 상태' },
  { value: 'pending', label: '평가 대기' },
  { value: 'reviewed', label: '평가 완료' },
  { value: 'exemplary', label: '모범 응답' },
]

export function Interactions() {
  const [searchQuery, setSearchQuery] = useState('')
  const [sourceFilter, setSourceFilter] = useState<string>('all')
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [reviewStatusFilter, setReviewStatusFilter] = useState<string>('all')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [adminMode, setAdminMode] = useState(false)
  const [days, setDays] = useState(30)
  const [page, setPage] = useState(0)
  const [pageSize] = useState(50)

  // Admin Feedback 상태 캐시
  const [adminFeedbackCache, setAdminFeedbackCache] = useState<Record<string, {
    quickRating?: string
    isExemplary?: boolean
  }>>({})

  // Source별 통계
  const { data: sourceStats } = useQuery({
    queryKey: ['interactions', 'stats', 'by-source', days],
    queryFn: () => interactionsApi.getStatsBySource(days),
  })

  // Admin Feedback 통계
  const { data: adminStats } = useQuery({
    queryKey: ['admin', 'feedback', 'stats'],
    queryFn: async () => {
      const res = await fetch('/api/v1/admin/feedback/stats')
      return res.json()
    },
    enabled: adminMode,
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

  // Admin Feedback 로드 (adminMode일 때만)
  const { data: adminFeedbackData } = useQuery({
    queryKey: ['admin', 'feedback', 'all', interactionsData?.items?.map(i => i.id)],
    queryFn: async () => {
      if (!interactionsData?.items) return {}
      const feedbacks: Record<string, { quickRating?: string; isExemplary?: boolean }> = {}
      await Promise.all(
        interactionsData.items.map(async (item) => {
          try {
            const res = await fetch(`/api/v1/admin/feedback/execution/${item.id}`)
            if (res.ok) {
              feedbacks[item.id] = await res.json()
            }
          } catch {
            // ignore
          }
        })
      )
      return feedbacks
    },
    enabled: adminMode && !!interactionsData?.items?.length,
  })

  // Admin Feedback 캐시 업데이트
  useMemo(() => {
    if (adminFeedbackData) {
      setAdminFeedbackCache(prev => ({ ...prev, ...adminFeedbackData }))
    }
  }, [adminFeedbackData])

  const interactions = interactionsData?.items || []
  const totalCount = interactionsData?.totalCount || 0
  const totalPages = interactionsData?.totalPages || 1

  // 로컬 필터링 (검색어, 상태, 평가 상태)
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
      // 평가 상태 필터 (adminMode일 때만)
      if (adminMode && reviewStatusFilter !== 'all') {
        const feedback = adminFeedbackCache[item.id]
        if (reviewStatusFilter === 'pending') {
          if (feedback?.quickRating && feedback.quickRating !== 'PENDING') return false
        }
        if (reviewStatusFilter === 'reviewed') {
          if (!feedback?.quickRating || feedback.quickRating === 'PENDING') return false
        }
        if (reviewStatusFilter === 'exemplary') {
          if (!feedback?.isExemplary) return false
        }
      }
      return true
    })
  }, [interactions, statusFilter, searchQuery, adminMode, reviewStatusFilter, adminFeedbackCache])

  const selectedInteraction = useMemo(() => {
    return filteredInteractions.find(i => i.id === selectedId)
  }, [filteredInteractions, selectedId])

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

  // 평가 상태 아이콘
  const ReviewStatusIcon = ({ id }: { id: string }) => {
    const feedback = adminFeedbackCache[id]
    if (feedback?.isExemplary) {
      return <Star className="h-4 w-4 text-yellow-500 fill-yellow-500" />
    }
    if (feedback?.quickRating === 'POSITIVE') {
      return <ThumbsUp className="h-4 w-4 text-green-500" />
    }
    if (feedback?.quickRating === 'NEGATIVE') {
      return <ThumbsDown className="h-4 w-4 text-red-500" />
    }
    return <div className="h-4 w-4 rounded-full border-2 border-muted-foreground/30" />
  }

  const handleFeedbackComplete = () => {
    // 선택된 항목의 캐시 업데이트를 위해 refetch
    refetch()
  }

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
    <div className="flex h-[calc(100vh-2rem)] gap-4">
      {/* Left Panel - List */}
      <div className={cn(
        'flex flex-col transition-all duration-300',
        selectedId ? 'w-1/2' : 'w-full'
      )}>
        {/* Header */}
        <div className="flex items-center justify-between mb-4">
          <div>
            <h1 className="text-3xl font-bold">Activity</h1>
            <p className="text-muted-foreground mt-1">
              Slack, Chat, MR Review 모든 활동 기록
            </p>
          </div>
          <div className="flex items-center gap-4">
            {/* Admin Mode Toggle */}
            <button
              onClick={() => setAdminMode(!adminMode)}
              className={cn(
                'flex items-center gap-2 px-4 py-2 rounded-lg border transition-all',
                adminMode
                  ? 'bg-yellow-500/10 border-yellow-500/50 text-yellow-600'
                  : 'border-border hover:bg-muted'
              )}
            >
              <Crown className={cn('h-4 w-4', adminMode && 'fill-yellow-500')} />
              <span className="text-sm font-medium">평가 모드</span>
            </button>
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <MessageSquare className="h-4 w-4" />
              {totalCount.toLocaleString()} interactions
            </div>
          </div>
        </div>

        {/* Admin Stats (평가 모드일 때만) */}
        {adminMode && adminStats && (
          <div className="grid grid-cols-4 gap-3 mb-4">
            <Card className="p-3">
              <div className="text-xs text-muted-foreground">평가 완료</div>
              <div className="text-xl font-bold">{adminStats.totalReviewed}</div>
            </Card>
            <Card className="p-3">
              <div className="text-xs text-muted-foreground flex items-center gap-1">
                <ThumbsUp className="h-3 w-3 text-green-500" /> 긍정
              </div>
              <div className="text-xl font-bold text-green-600">{adminStats.positiveCount}</div>
            </Card>
            <Card className="p-3">
              <div className="text-xs text-muted-foreground flex items-center gap-1">
                <ThumbsDown className="h-3 w-3 text-red-500" /> 부정
              </div>
              <div className="text-xl font-bold text-red-600">{adminStats.negativeCount}</div>
            </Card>
            <Card className="p-3">
              <div className="text-xs text-muted-foreground flex items-center gap-1">
                <Star className="h-3 w-3 text-yellow-500" /> 모범
              </div>
              <div className="text-xl font-bold text-yellow-600">{adminStats.exemplaryCount}</div>
            </Card>
          </div>
        )}

        {/* Source Stats */}
        {sortedSourceStats.length > 0 && !selectedId && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
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
        <Card className="p-4 mb-4">
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

            {/* 평가 상태 필터 (평가 모드일 때만) */}
            {adminMode && (
              <div className="flex items-center gap-2">
                <Crown className="h-4 w-4 text-yellow-500" />
                <select
                  value={reviewStatusFilter}
                  onChange={(e) => setReviewStatusFilter(e.target.value)}
                  className="px-3 py-2 rounded-lg border border-border bg-background"
                >
                  {REVIEW_STATUS_OPTIONS.map(opt => (
                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                  ))}
                </select>
              </div>
            )}

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
          <div className="flex-1 overflow-y-auto space-y-2">
            {filteredInteractions.map((item: Interaction) => (
              <Card
                key={item.id}
                className={cn(
                  'p-4 cursor-pointer transition-all hover:bg-muted/50',
                  selectedId === item.id && 'ring-2 ring-primary bg-muted/30'
                )}
                onClick={() => setSelectedId(selectedId === item.id ? null : item.id)}
              >
                <div className="flex items-center gap-4">
                  {/* Admin Mode: 평가 상태 아이콘 */}
                  {adminMode && <ReviewStatusIcon id={item.id} />}

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
                  </div>
                </div>
              </Card>
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
          <div className="flex items-center justify-center gap-4 mt-4 pt-4 border-t">
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

      {/* Right Panel - Detail */}
      {selectedId && selectedInteraction && (
        <div className="w-1/2 flex flex-col border-l pl-4">
          <InteractionDetail
            interaction={selectedInteraction}
            formatTime={formatTime}
            copyToClipboard={copyToClipboard}
            SourceIcon={SourceIcon}
            adminMode={adminMode}
            onFeedbackComplete={handleFeedbackComplete}
            onClose={() => setSelectedId(null)}
          />
        </div>
      )}
    </div>
  )
}

// 상세 패널 컴포넌트
interface InteractionDetailProps {
  interaction: Interaction
  formatTime: (iso: string) => string
  copyToClipboard: (text: string) => void
  SourceIcon: React.FC<{ source: string; size?: 'sm' | 'lg' }>
  adminMode: boolean
  onFeedbackComplete: () => void
  onClose: () => void
}

function InteractionDetail({
  interaction: item,
  formatTime,
  copyToClipboard,
  SourceIcon,
  adminMode,
  onFeedbackComplete,
  onClose,
}: InteractionDetailProps) {
  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <SourceIcon source={item.source} size="lg" />
          <div>
            <h2 className="text-lg font-bold">{item.sourceDisplayName}</h2>
            <p className="text-sm text-muted-foreground">{formatTime(item.createdAt)}</p>
          </div>
        </div>
        <button
          onClick={onClose}
          className="p-2 hover:bg-muted rounded-lg"
        >
          <XCircle className="h-5 w-5" />
        </button>
      </div>

      {/* Content - Scrollable */}
      <div className="flex-1 overflow-y-auto space-y-4">
        {/* Metadata */}
        <Card className="p-4">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
            <div>
              <p className="text-muted-foreground">ID</p>
              <div className="flex items-center gap-2">
                <code className="font-mono text-xs">{item.id.slice(0, 8)}...</code>
                <button
                  onClick={() => copyToClipboard(item.id)}
                  className="p-1 hover:bg-muted rounded"
                >
                  <Copy className="h-3 w-3" />
                </button>
              </div>
            </div>
            <div>
              <p className="text-muted-foreground">Agent</p>
              <p className="flex items-center gap-1">
                <Bot className="h-3 w-3" />
                {item.agentId}
              </p>
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
          <div className="flex items-center gap-6 text-sm mt-4 pt-4 border-t">
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
        </Card>

        {/* MR Context (for MR Reviews) */}
        {item.source === 'mr_review' && item.mrContext && (
          <div>
            <p className="text-sm text-muted-foreground mb-2">MR Context</p>
            <Card className="p-3 bg-orange-500/5 border-orange-500/20 max-h-80 overflow-y-auto scrollbar-visible">
              <p className="whitespace-pre-wrap text-sm break-words">{item.mrContext}</p>
            </Card>
          </div>
        )}

        {/* Prompt */}
        <div>
          <p className="text-sm text-muted-foreground mb-2">
            {item.source === 'mr_review' ? 'Review Request' : 'Prompt'}
          </p>
          <Card className="p-3 max-h-80 overflow-y-auto scrollbar-visible">
            <p className="whitespace-pre-wrap text-sm break-words">{item.prompt}</p>
          </Card>
        </div>

        {/* Result or Error */}
        {item.status === 'SUCCESS' && item.result && (
          <div>
            <p className="text-sm text-muted-foreground mb-2">
              {item.source === 'mr_review' ? 'Review Content' : 'Result'}
            </p>
            <Card className="p-3 max-h-64 overflow-y-auto">
              <pre className="whitespace-pre-wrap text-sm font-mono">{item.result}</pre>
            </Card>
          </div>
        )}

        {item.status === 'ERROR' && item.error && (
          <div>
            <p className="text-sm text-red-500 mb-2">Error</p>
            <Card className="p-3 bg-red-500/10 border-red-500/30">
              <p className="text-sm text-red-500">{item.error}</p>
            </Card>
          </div>
        )}

        {/* User Feedback Details */}
        {item.feedbacks && item.feedbacks.length > 0 && (
          <div>
            <p className="text-sm text-muted-foreground mb-2">User Feedback</p>
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

      {/* Admin Feedback Panel (평가 모드일 때만) */}
      {adminMode && (
        <div className="border-t pt-4 mt-4">
          <AdminFeedbackPanel
            executionId={item.id}
            onClose={onFeedbackComplete}
          />
        </div>
      )}
    </div>
  )
}
