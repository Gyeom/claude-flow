import { useState, useEffect, useRef } from 'react'
import { ChevronRight, ChevronDown, Copy, Check } from 'lucide-react'
import { cn } from '@/lib/utils'

interface LogEvent {
  executionId: string
  timestamp: string
  level: string
  message: string
  details: Record<string, unknown>
}

// 로그 행 컴포넌트
function LogRow({
  log,
  isExpanded,
  onToggle,
  onExecutionClick,
  selectedExecution
}: {
  log: LogEvent
  isExpanded: boolean
  onToggle: () => void
  onExecutionClick: (id: string) => void
  selectedExecution: string | null
}) {
  const [copied, setCopied] = useState(false)
  const hasDetails = Object.keys(log.details).length > 0

  const formatTime = (timestamp: string) => {
    const date = new Date(timestamp)
    const time = date.toLocaleTimeString('ko-KR', { hour12: false })
    const ms = date.getMilliseconds().toString().padStart(3, '0')
    return `${time}.${ms}`
  }

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="border-b border-border/30 last:border-0">
      <div
        className={cn(
          "flex items-start gap-2 py-2 px-2 cursor-pointer hover:bg-white/5 transition-colors",
          isExpanded && "bg-white/5"
        )}
        onClick={onToggle}
      >
        {/* 확장 아이콘 */}
        <button className="mt-0.5 text-muted-foreground hover:text-foreground shrink-0">
          {isExpanded ? (
            <ChevronDown className="w-4 h-4" />
          ) : (
            <ChevronRight className="w-4 h-4" />
          )}
        </button>

        {/* 시간 */}
        <span className="text-muted-foreground shrink-0 w-24 text-xs font-mono">
          {formatTime(log.timestamp)}
        </span>

        {/* 레벨 배지 */}
        <span className={cn(
          "px-2 py-0.5 rounded text-xs font-medium border shrink-0 w-24 text-center",
          levelBadgeColors[log.level] || levelBadgeColors.INFO
        )}>
          {log.level.replace('_', ' ')}
        </span>

        {/* Execution ID */}
        <button
          onClick={(e) => {
            e.stopPropagation()
            onExecutionClick(selectedExecution === log.executionId ? '' : log.executionId)
          }}
          className={cn(
            "text-xs font-mono shrink-0 w-20 truncate text-left",
            selectedExecution === log.executionId
              ? "text-primary underline"
              : "text-muted-foreground hover:text-primary"
          )}
          title={log.executionId}
        >
          {log.executionId.slice(0, 8)}
        </button>

        {/* 메시지 */}
        <span className={cn(
          "flex-1 text-sm truncate",
          levelColors[log.level] || 'text-foreground'
        )}>
          {log.message}
        </span>

        {/* 상세 정보 표시 */}
        {hasDetails && !isExpanded && (
          <span className="text-muted-foreground text-xs shrink-0">
            +{Object.keys(log.details).length} fields
          </span>
        )}
      </div>

      {/* 확장된 상세 정보 */}
      {isExpanded && (
        <div className="pl-8 pr-4 pb-3 space-y-2">
          {/* 전체 메시지 */}
          <div className="bg-black/30 rounded-md p-3">
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs text-muted-foreground">Message</span>
              <button
                onClick={() => copyToClipboard(log.message)}
                className="text-muted-foreground hover:text-foreground"
              >
                {copied ? <Check className="w-3 h-3" /> : <Copy className="w-3 h-3" />}
              </button>
            </div>
            <pre className={cn(
              "text-sm whitespace-pre-wrap break-all",
              levelColors[log.level]
            )}>
              {log.message}
            </pre>
          </div>

          {/* 상세 필드 */}
          {hasDetails && (
            <div className="bg-black/30 rounded-md p-3">
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs text-muted-foreground">Details</span>
                <button
                  onClick={() => copyToClipboard(JSON.stringify(log.details, null, 2))}
                  className="text-muted-foreground hover:text-foreground"
                >
                  <Copy className="w-3 h-3" />
                </button>
              </div>
              <div className="space-y-2">
                {Object.entries(log.details).map(([key, value]) => {
                  const stringValue = typeof value === 'object'
                    ? JSON.stringify(value, null, 2)
                    : String(value)
                  const isLongText = stringValue.length > 80

                  return (
                    <div key={key} className={cn(
                      "text-sm",
                      isLongText ? "flex flex-col gap-1" : "flex gap-2"
                    )}>
                      <span className="text-purple-400 shrink-0">{key}:</span>
                      <pre className={cn(
                        "text-foreground whitespace-pre-wrap break-all",
                        isLongText && "bg-black/20 rounded p-2 text-xs"
                      )}>
                        {stringValue}
                      </pre>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* 메타 정보 */}
          <div className="flex gap-4 text-xs text-muted-foreground">
            <span>Execution: <code className="text-primary">{log.executionId}</code></span>
            <span>Time: {new Date(log.timestamp).toLocaleString('ko-KR')}</span>
          </div>
        </div>
      )}
    </div>
  )
}

const levelColors: Record<string, string> = {
  DEBUG: 'text-gray-400',
  INFO: 'text-blue-400',
  WARN: 'text-yellow-400',
  ERROR: 'text-red-400',
  TOOL_START: 'text-purple-400',
  TOOL_END: 'text-purple-300',
  AGENT_START: 'text-green-400',
  AGENT_END: 'text-green-300',
}

const levelBadgeColors: Record<string, string> = {
  DEBUG: 'bg-gray-500/20 text-gray-400 border-gray-500/30',
  INFO: 'bg-blue-500/20 text-blue-400 border-blue-500/30',
  WARN: 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30',
  ERROR: 'bg-red-500/20 text-red-400 border-red-500/30',
  TOOL_START: 'bg-purple-500/20 text-purple-400 border-purple-500/30',
  TOOL_END: 'bg-purple-500/20 text-purple-300 border-purple-500/30',
  AGENT_START: 'bg-green-500/20 text-green-400 border-green-500/30',
  AGENT_END: 'bg-green-500/20 text-green-300 border-green-500/30',
}

export default function Logs() {
  const [logs, setLogs] = useState<LogEvent[]>([])
  const [connected, setConnected] = useState(false)
  const [filter, setFilter] = useState<string>('')
  const [levelFilter, setLevelFilter] = useState<string>('ALL')
  const [autoScroll, setAutoScroll] = useState(true)
  const [selectedExecution, setSelectedExecution] = useState<string | null>(null)
  const [expandedLogs, setExpandedLogs] = useState<Set<string>>(new Set())
  const logsEndRef = useRef<HTMLDivElement>(null)
  const eventSourceRef = useRef<EventSource | null>(null)

  const toggleExpand = (logKey: string) => {
    setExpandedLogs(prev => {
      const next = new Set(prev)
      if (next.has(logKey)) {
        next.delete(logKey)
      } else {
        next.add(logKey)
      }
      return next
    })
  }

  // SSE 연결
  useEffect(() => {
    const url = selectedExecution
      ? `/api/v1/logs/stream/${selectedExecution}`
      : '/api/v1/logs/stream'

    const eventSource = new EventSource(url)
    eventSourceRef.current = eventSource

    eventSource.onopen = () => {
      setConnected(true)
    }

    eventSource.onerror = (e) => {
      console.error('SSE error:', e)
      setConnected(false)
    }

    // 하트비트 핸들러 (연결 유지 확인)
    eventSource.addEventListener('heartbeat', () => {
      setConnected(true)
    })

    // 각 레벨별 이벤트 핸들러
    const levels = ['debug', 'info', 'warn', 'error', 'tool_start', 'tool_end', 'agent_start', 'agent_end']
    levels.forEach(level => {
      eventSource.addEventListener(level, (event) => {
        setConnected(true)
        try {
          const logEvent: LogEvent = JSON.parse(event.data)
          setLogs(prev => [...prev.slice(-500), logEvent])  // 최대 500개 유지
        } catch (e) {
          console.error('Failed to parse log event:', e)
        }
      })
    })

    return () => {
      eventSource.close()
    }
  }, [selectedExecution])

  // 자동 스크롤
  useEffect(() => {
    if (autoScroll && logsEndRef.current) {
      logsEndRef.current.scrollIntoView({ behavior: 'smooth' })
    }
  }, [logs, autoScroll])

  // 필터링된 로그
  const filteredLogs = logs.filter(log => {
    if (levelFilter !== 'ALL' && log.level !== levelFilter) return false
    if (filter && !log.message.toLowerCase().includes(filter.toLowerCase()) &&
        !log.executionId.toLowerCase().includes(filter.toLowerCase())) return false
    return true
  })

  return (
    <div className="flex flex-col h-[calc(100vh-4rem)]">
      {/* 헤더 */}
      <div className="flex items-center justify-between p-4 border-b border-border">
        <div className="flex items-center gap-4">
          <h1 className="text-xl font-bold">Live Logs</h1>
          <div className={cn(
            "flex items-center gap-2 px-3 py-1 rounded-full text-xs font-medium",
            connected ? "bg-green-500/20 text-green-400" : "bg-red-500/20 text-red-400"
          )}>
            <span className={cn(
              "w-2 h-2 rounded-full",
              connected ? "bg-green-400 animate-pulse" : "bg-red-400"
            )} />
            {connected ? 'Connected' : 'Disconnected'}
          </div>
        </div>
        <div className="flex items-center gap-4">
          {/* 레벨 필터 */}
          <select
            value={levelFilter}
            onChange={(e) => setLevelFilter(e.target.value)}
            className="px-3 py-1.5 rounded-md bg-muted border border-border text-sm"
          >
            <option value="ALL">All Levels</option>
            <option value="DEBUG">Debug</option>
            <option value="INFO">Info</option>
            <option value="WARN">Warn</option>
            <option value="ERROR">Error</option>
            <option value="AGENT_START">Agent Start</option>
            <option value="AGENT_END">Agent End</option>
            <option value="TOOL_START">Tool Start</option>
            <option value="TOOL_END">Tool End</option>
          </select>

          {/* 검색 */}
          <input
            type="text"
            placeholder="Filter logs..."
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            className="px-3 py-1.5 rounded-md bg-muted border border-border text-sm w-48"
          />

          {/* 자동 스크롤 */}
          <button
            onClick={() => setAutoScroll(!autoScroll)}
            className={cn(
              "px-3 py-1.5 rounded-md text-sm font-medium transition-colors",
              autoScroll
                ? "bg-primary text-primary-foreground"
                : "bg-muted text-muted-foreground"
            )}
          >
            Auto-scroll {autoScroll ? 'ON' : 'OFF'}
          </button>

          {/* 클리어 */}
          <button
            onClick={() => setLogs([])}
            className="px-3 py-1.5 rounded-md bg-muted text-sm font-medium hover:bg-muted/80"
          >
            Clear
          </button>
        </div>
      </div>

      {/* 로그 영역 */}
      <div className="flex-1 overflow-auto p-4 font-mono text-sm bg-black/20">
        {filteredLogs.length === 0 ? (
          <div className="flex items-center justify-center h-full text-muted-foreground">
            {connected ? 'Waiting for logs...' : 'Connecting...'}
          </div>
        ) : (
          <div className="space-y-0">
            {filteredLogs.map((log, index) => {
              const logKey = `${log.timestamp}-${index}`
              return (
                <LogRow
                  key={logKey}
                  log={log}
                  isExpanded={expandedLogs.has(logKey)}
                  onToggle={() => toggleExpand(logKey)}
                  onExecutionClick={(id) => setSelectedExecution(id || null)}
                  selectedExecution={selectedExecution}
                />
              )
            })}
            <div ref={logsEndRef} />
          </div>
        )}
      </div>

      {/* 선택된 실행 정보 */}
      {selectedExecution && (
        <div className="border-t border-border p-3 bg-muted/30">
          <div className="flex items-center justify-between">
            <span className="text-sm">
              Filtering by execution: <code className="text-primary">{selectedExecution}</code>
            </span>
            <button
              onClick={() => setSelectedExecution(null)}
              className="text-sm text-muted-foreground hover:text-foreground"
            >
              Clear filter
            </button>
          </div>
        </div>
      )}

      {/* 통계 바 */}
      <div className="border-t border-border p-3 bg-muted/30 flex items-center gap-6 text-sm">
        <span className="text-muted-foreground">
          Total: <span className="text-foreground font-medium">{logs.length}</span>
        </span>
        <span className="text-muted-foreground">
          Filtered: <span className="text-foreground font-medium">{filteredLogs.length}</span>
        </span>
        <span className="text-muted-foreground">
          Errors: <span className="text-red-400 font-medium">
            {logs.filter(l => l.level === 'ERROR').length}
          </span>
        </span>
        <span className="text-muted-foreground">
          Agents: <span className="text-green-400 font-medium">
            {logs.filter(l => l.level === 'AGENT_START').length}
          </span>
        </span>
      </div>
    </div>
  )
}
