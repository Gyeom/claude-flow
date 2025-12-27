import { useState, useCallback, useRef, useEffect } from 'react'
import { MessageSquare, X, Minimize2, Maximize2, Trash2, Settings2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useChatContext } from '@/contexts/ChatContext'
import { ChatMessages } from './ChatMessages'
import { ChatInput } from './ChatInput'
import { useQuery } from '@tanstack/react-query'
import { projectsApi } from '@/lib/api'

// 최소/최대 크기 제한
const MIN_WIDTH = 320
const MIN_HEIGHT = 400
const MAX_WIDTH = 800
const MAX_HEIGHT = window.innerHeight - 50

// 기본 크기
const DEFAULT_WIDTH = 400
const DEFAULT_HEIGHT = 500

export function FloatingChat() {
  const {
    messages,
    isStreaming,
    streamingContent,
    currentToolCalls,
    currentMetadata,
    progressStatus,
    isPanelOpen,
    selectedProject,
    feedbackState,
    setSelectedProject,
    openPanel,
    closePanel,
    sendMessage,
    sendClarificationResponse,
    submitFeedback,
    stopStreaming,
    clearMessages,
  } = useChatContext()

  const [input, setInput] = useState('')
  const [showSettings, setShowSettings] = useState(false)

  // 리사이즈 상태
  const [size, setSize] = useState({ width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT })
  const [isResizing, setIsResizing] = useState(false)
  const resizeRef = useRef<{ startX: number; startY: number; startWidth: number; startHeight: number } | null>(null)
  const panelRef = useRef<HTMLDivElement>(null)

  // 프로젝트 목록 조회
  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: projectsApi.getAll,
  })

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!input.trim() || isStreaming) return

    const message = input
    setInput('')
    await sendMessage(message)
  }

  const handleInputChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(e.target.value)
  }

  // 리사이즈 시작 (좌상단 코너)
  const handleResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    setIsResizing(true)
    resizeRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      startWidth: size.width,
      startHeight: size.height,
    }
  }, [size])

  // 리사이즈 중
  useEffect(() => {
    if (!isResizing) return

    const handleMouseMove = (e: MouseEvent) => {
      if (!resizeRef.current) return

      // 좌상단에서 드래그하므로 방향 반전
      const deltaX = resizeRef.current.startX - e.clientX
      const deltaY = resizeRef.current.startY - e.clientY

      const newWidth = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, resizeRef.current.startWidth + deltaX))
      const newHeight = Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, resizeRef.current.startHeight + deltaY))

      setSize({ width: newWidth, height: newHeight })
    }

    const handleMouseUp = () => {
      setIsResizing(false)
      resizeRef.current = null
    }

    document.addEventListener('mousemove', handleMouseMove)
    document.addEventListener('mouseup', handleMouseUp)

    return () => {
      document.removeEventListener('mousemove', handleMouseMove)
      document.removeEventListener('mouseup', handleMouseUp)
    }
  }, [isResizing])

  // 최대화/최소화 토글
  const toggleMaximize = useCallback(() => {
    if (size.width > DEFAULT_WIDTH || size.height > DEFAULT_HEIGHT) {
      // 현재 확장된 상태면 기본 크기로
      setSize({ width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT })
    } else {
      // 기본 크기면 최대화
      setSize({ width: 600, height: Math.min(window.innerHeight - 100, 700) })
    }
  }, [size])

  // 플로팅 버튼 (패널이 닫혀있을 때)
  if (!isPanelOpen) {
    return (
      <button
        onClick={openPanel}
        className={cn(
          "fixed bottom-6 right-6 z-50",
          "w-14 h-14 rounded-full",
          "bg-primary text-primary-foreground",
          "shadow-lg hover:shadow-xl",
          "flex items-center justify-center",
          "transition-all duration-200",
          "hover:scale-105 active:scale-95"
        )}
      >
        <MessageSquare className="w-6 h-6" />
        {messages.length > 0 && (
          <span className="absolute -top-1 -right-1 w-5 h-5 bg-destructive text-destructive-foreground text-xs rounded-full flex items-center justify-center">
            {messages.length}
          </span>
        )}
      </button>
    )
  }

  const isMaximized = size.width > DEFAULT_WIDTH || size.height > DEFAULT_HEIGHT

  // 채팅 패널
  return (
    <div
      ref={panelRef}
      className={cn(
        "fixed z-50 bg-card border border-border rounded-lg shadow-2xl",
        "flex flex-col overflow-hidden",
        !isResizing && "transition-all duration-200 ease-out"
      )}
      style={{
        bottom: 24,
        right: 24,
        width: size.width,
        height: size.height,
      }}
    >
      {/* 리사이즈 핸들 - 좌상단 코너 */}
      <div
        onMouseDown={handleResizeStart}
        className={cn(
          "absolute -top-1 -left-1 w-4 h-4 cursor-nwse-resize z-10",
          "group"
        )}
      >
        {/* 시각적 인디케이터 */}
        <div className={cn(
          "absolute top-1 left-1 w-2 h-2",
          "border-t-2 border-l-2 border-border",
          "group-hover:border-primary transition-colors",
          isResizing && "border-primary"
        )} />
      </div>

      {/* 리사이즈 핸들 - 좌측 */}
      <div
        onMouseDown={handleResizeStart}
        className="absolute left-0 top-4 bottom-4 w-1 cursor-ew-resize hover:bg-primary/20 transition-colors"
      />

      {/* 리사이즈 핸들 - 상단 */}
      <div
        onMouseDown={handleResizeStart}
        className="absolute top-0 left-4 right-4 h-1 cursor-ns-resize hover:bg-primary/20 transition-colors"
      />

      {/* 헤더 */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-border bg-muted/50">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center">
            <MessageSquare className="w-4 h-4 text-primary" />
          </div>
          <div>
            <h3 className="text-sm font-semibold">Claude Flow Chat</h3>
            {currentMetadata && isStreaming && (
              <p className="text-xs text-muted-foreground">
                {currentMetadata.agentName} ({Math.round((currentMetadata.confidence || 0) * 100)}%)
              </p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-1">
          {/* 크기 표시 (리사이즈 중) */}
          {isResizing && (
            <span className="text-xs text-muted-foreground mr-2">
              {size.width} × {size.height}
            </span>
          )}
          <button
            onClick={() => setShowSettings(!showSettings)}
            className={cn(
              "p-2 rounded-md transition-colors",
              showSettings ? "bg-primary/10 text-primary" : "hover:bg-muted text-muted-foreground"
            )}
            title="Settings"
          >
            <Settings2 className="w-4 h-4" />
          </button>
          <button
            onClick={clearMessages}
            className="p-2 rounded-md hover:bg-muted text-muted-foreground transition-colors"
            title="Clear chat"
          >
            <Trash2 className="w-4 h-4" />
          </button>
          <button
            onClick={toggleMaximize}
            className="p-2 rounded-md hover:bg-muted text-muted-foreground transition-colors"
            title={isMaximized ? "Restore" : "Maximize"}
          >
            {isMaximized ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}
          </button>
          <button
            onClick={closePanel}
            className="p-2 rounded-md hover:bg-muted text-muted-foreground transition-colors"
            title="Close"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* 설정 패널 */}
      {showSettings && (
        <div className="px-4 py-3 border-b border-border bg-muted/30">
          <div>
            <label className="text-xs font-medium text-muted-foreground mb-1 block">
              Project
            </label>
            <select
              value={selectedProject || ''}
              onChange={(e) => setSelectedProject(e.target.value || null)}
              className="w-full text-sm px-2 py-1.5 rounded-md border border-border bg-background"
            >
              <option value="">Auto-detect</option>
              {projects?.map((p) => (
                <option key={p.id} value={p.id}>{p.name}</option>
              ))}
            </select>
            <p className="text-xs text-muted-foreground mt-1">
              에이전트는 메시지에 따라 자동으로 선택됩니다.
            </p>
          </div>
        </div>
      )}

      {/* 진행 상황 표시 - 개선된 스텝퍼 UI */}
      {isStreaming && (
        <div className="border-b border-border bg-gradient-to-r from-blue-500/5 via-purple-500/5 to-blue-500/5">
          {/* 스텝 인디케이터 */}
          <div className="px-4 py-3">
            <div className="flex items-center justify-between gap-1 mb-2">
              {[
                { step: 'rate_limit_check', icon: '🔐', label: '권한' },
                { step: 'agent_routing', icon: '🔀', label: '라우팅' },
                { step: 'context_enrichment', icon: '📚', label: '컨텍스트' },
                { step: 'execution_start', icon: '🚀', label: '실행' },
                { step: 'processing', icon: '✨', label: '생성' },
              ].map((s, idx, arr) => {
                const currentStepIdx = progressStatus ? arr.findIndex(x => x.step === progressStatus.step) : -1
                const isCompleted = idx < currentStepIdx
                const isCurrent = progressStatus?.step === s.step
                const isPending = idx > currentStepIdx && currentStepIdx >= 0

                return (
                  <div key={s.step} className="flex items-center gap-1 flex-1">
                    <div className={`
                      w-6 h-6 rounded-full flex items-center justify-center text-xs
                      transition-all duration-300
                      ${isCompleted ? 'bg-green-500/20 text-green-600' : ''}
                      ${isCurrent ? 'bg-blue-500/30 text-blue-600 ring-2 ring-blue-500/50 animate-pulse' : ''}
                      ${isPending ? 'bg-muted text-muted-foreground' : ''}
                      ${!progressStatus && idx === 0 ? 'bg-blue-500/30 text-blue-600 animate-pulse' : ''}
                    `}>
                      {isCompleted ? '✓' : s.icon}
                    </div>
                    {idx < arr.length - 1 && (
                      <div className={`
                        h-0.5 flex-1 rounded transition-all duration-300
                        ${isCompleted ? 'bg-green-500/50' : 'bg-muted'}
                      `} />
                    )}
                  </div>
                )
              })}
            </div>
            {/* 현재 상태 메시지 */}
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-blue-500 animate-ping" />
              <span className="text-sm text-blue-600 dark:text-blue-400 font-medium">
                {progressStatus?.message || '요청 처리 중...'}
              </span>
              {progressStatus?.detail && Object.keys(progressStatus.detail).length > 0 && (
                <span className="text-xs text-muted-foreground ml-auto">
                  {Object.entries(progressStatus.detail)
                    .filter(([, v]) => v != null)
                    .slice(0, 2)
                    .map(([k, v]) => `${k}: ${v}`)
                    .join(' • ')}
                </span>
              )}
            </div>
          </div>

          {/* 도구 호출 진행 표시 */}
          {currentToolCalls.length > 0 && (
            <div className="px-4 py-2 bg-amber-500/10 border-t border-amber-500/20">
              <div className="flex items-center gap-2 text-sm">
                <span className="text-amber-600 dark:text-amber-400">🔧</span>
                <span className="font-medium text-amber-700 dark:text-amber-300">
                  {currentToolCalls.filter(t => t.status === 'running').length > 0
                    ? `도구 실행 중: ${currentToolCalls.filter(t => t.status === 'running').map(t => t.toolName).join(', ')}`
                    : `도구 ${currentToolCalls.length}개 완료`
                  }
                </span>
                <span className="text-xs text-muted-foreground ml-auto">
                  {currentToolCalls.filter(t => t.status === 'completed').length}/{currentToolCalls.length}
                </span>
              </div>
            </div>
          )}
        </div>
      )}

      {/* 메시지 영역 */}
      <div className="flex-1 min-h-0 overflow-y-auto">
        <ChatMessages
          messages={messages}
          isStreaming={isStreaming}
          currentToolCalls={currentToolCalls}
          streamingContent={streamingContent}
          onClarificationSelect={(option, context) => sendClarificationResponse(option, context)}
          feedbackState={feedbackState}
          onFeedback={submitFeedback}
        />
      </div>

      {/* 스트리밍 중 Stop 버튼 */}
      {isStreaming && (
        <div className="px-4 py-2 border-t border-border bg-muted/30">
          <button
            onClick={stopStreaming}
            className="w-full py-1.5 rounded-md bg-destructive/10 text-destructive text-sm font-medium hover:bg-destructive/20 transition-colors"
          >
            Stop generating
          </button>
        </div>
      )}

      {/* 입력 영역 */}
      <ChatInput
        input={input}
        onChange={handleInputChange}
        onSubmit={handleSubmit}
        isLoading={isStreaming}
      />
    </div>
  )
}
