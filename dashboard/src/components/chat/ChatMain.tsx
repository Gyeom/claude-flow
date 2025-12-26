import { useState, useCallback, useRef } from 'react'
import { ChatMessages } from './ChatMessages'
import { ChatInput } from './ChatInput'
import { toast } from 'sonner'
import type { ClarificationRequest, ClarificationOption } from '../../types'

interface ToolCall {
  toolId: string
  toolName: string
  input?: Record<string, unknown>
  result?: string
  success?: boolean
  status: 'running' | 'completed' | 'error'
}

interface ProgressStatus {
  step: string
  message: string
  timestamp: number
  detail?: Record<string, unknown>
}

interface Message {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  toolCalls?: ToolCall[]
  clarification?: ClarificationRequest
  metadata?: {
    agentId?: string
    agentName?: string
    confidence?: number
    routingMethod?: string
  }
}

interface ChatMainProps {
  projectId: string | null
  agentId: string | null
}

export function ChatMain({ projectId, agentId }: ChatMainProps) {
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [isStreaming, setIsStreaming] = useState(false)
  const [streamingContent, setStreamingContent] = useState('')
  const [currentToolCalls, setCurrentToolCalls] = useState<ToolCall[]>([])
  const [currentMetadata, setCurrentMetadata] = useState<Message['metadata']>()
  const [progressStatus, setProgressStatus] = useState<ProgressStatus | null>(null)
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [_currentClarification, setCurrentClarification] = useState<ClarificationRequest | null>(null)

  const abortControllerRef = useRef<AbortController | null>(null)

  const handleInputChange = useCallback((e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(e.target.value)
  }, [])

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault()

    const userMessage = input.trim()
    if (!userMessage || isStreaming) return

    // 사용자 메시지 추가
    const userMessageObj: Message = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: userMessage,
    }
    setMessages(prev => [...prev, userMessageObj])
    setInput('')
    setIsStreaming(true)
    setStreamingContent('')
    setCurrentToolCalls([])
    setCurrentMetadata(undefined)
    setProgressStatus(null)

    // 대화 히스토리 구성
    const chatMessages = [...messages, userMessageObj].map(m => ({
      role: m.role,
      content: m.content,
    }))

    try {
      abortControllerRef.current = new AbortController()

      const response = await fetch('/api/v1/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
        },
        body: JSON.stringify({
          messages: chatMessages,
          projectId,
          agentId,
        }),
        signal: abortControllerRef.current.signal,
      })

      if (!response.ok) {
        throw new Error(`HTTP error: ${response.status}`)
      }

      const reader = response.body?.getReader()
      if (!reader) {
        throw new Error('No response body')
      }

      const decoder = new TextDecoder()
      let buffer = ''
      let accumulatedContent = ''
      let toolCalls: ToolCall[] = []
      let metadata: Message['metadata'] = undefined

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // SSE 이벤트 파싱
        const lines = buffer.split('\n')
        buffer = lines.pop() || '' // 마지막 불완전한 줄은 버퍼에 유지

        let eventType = ''
        let eventData = ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventType = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            eventData = line.slice(5).trim()
          } else if (line === '' && eventType && eventData) {
            // 이벤트 완료
            try {
              await processEvent(eventType, eventData)
            } catch (err) {
              console.error('Event processing error:', err)
            }
            eventType = ''
            eventData = ''
          }
        }
      }

      // 스트리밍 완료 - 어시스턴트 메시지 추가
      if (accumulatedContent || toolCalls.length > 0) {
        const assistantMessage: Message = {
          id: `assistant-${Date.now()}`,
          role: 'assistant',
          content: accumulatedContent,
          toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
          metadata,
        }
        setMessages(prev => [...prev, assistantMessage])
      }

      async function processEvent(type: string, dataStr: string) {
        if (type === 'heartbeat') return

        try {
          const data = JSON.parse(dataStr)

          switch (type) {
            case 'text':
              accumulatedContent += data.content || ''
              setStreamingContent(accumulatedContent)
              break

            case 'tool_start':
              const newTool: ToolCall = {
                toolId: data.toolId,
                toolName: data.toolName,
                input: data.input,
                status: 'running',
              }
              toolCalls = [...toolCalls, newTool]
              setCurrentToolCalls([...toolCalls])
              break

            case 'tool_end':
              toolCalls = toolCalls.map(t =>
                t.toolId === data.toolId
                  ? { ...t, result: data.result, success: data.success, status: data.success ? 'completed' : 'error' as const }
                  : t
              )
              setCurrentToolCalls([...toolCalls])
              break

            case 'metadata':
              metadata = {
                agentId: data.agentId,
                agentName: data.agentName,
                confidence: data.confidence,
                routingMethod: data.routingMethod,
              }
              setCurrentMetadata(metadata)
              break

            case 'progress':
              // 진행 상황 업데이트
              setProgressStatus({
                step: data.step,
                message: data.message,
                timestamp: data.timestamp || Date.now(),
                detail: data.detail,
              })
              break

            case 'clarification':
              // Clarification 요청 (프로젝트 선택 등)
              const clarificationRequest: ClarificationRequest = {
                type: data.type || 'options',
                question: data.question,
                options: data.options || [],
                context: data.context,
              }
              setCurrentClarification(clarificationRequest)
              // Clarification을 assistant 메시지로 추가
              const clarificationMessage: Message = {
                id: `clarification-${Date.now()}`,
                role: 'assistant',
                content: data.question,
                clarification: clarificationRequest,
              }
              setMessages(prev => [...prev, clarificationMessage])
              break

            case 'done':
              // 완료 처리는 루프 종료 후
              setProgressStatus(null)
              break

            case 'error':
              toast.error(data.message || 'An error occurred')
              setProgressStatus(null)
              break
          }
        } catch (err) {
          console.error('Failed to parse event data:', dataStr, err)
        }
      }

    } catch (error) {
      if ((error as Error).name === 'AbortError') {
        toast.info('Request cancelled')
      } else {
        console.error('Chat error:', error)
        toast.error((error as Error).message || 'Failed to send message')
      }
    } finally {
      setIsStreaming(false)
      setStreamingContent('')
      setCurrentToolCalls([])
      setProgressStatus(null)
      abortControllerRef.current = null
    }
  }, [input, isStreaming, messages, projectId, agentId])

  const handleStop = useCallback(() => {
    abortControllerRef.current?.abort()
  }, [])

  /**
   * Clarification 선택 핸들러
   * 프로젝트 선택 버튼 클릭 시 호출
   */
  const handleClarificationSelect = useCallback((option: ClarificationOption, context?: Record<string, unknown>) => {
    // 현재 clarification 초기화
    setCurrentClarification(null)

    // 원본 요청 + 선택된 옵션을 결합하여 새 메시지 생성
    const originalPrompt = context?.originalPrompt as string || ''
    const enhancedMessage = originalPrompt
      ? `${originalPrompt} [프로젝트: ${option.id}]`
      : `선택: ${option.label} (${option.id})`

    // 새 입력으로 설정하고 submit 트리거
    setInput(enhancedMessage)
    // 다음 프레임에서 submit (상태 업데이트 반영)
    setTimeout(() => {
      const form = document.querySelector('form')
      if (form) {
        form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
      }
    }, 0)
  }, [])

  return (
    <div className="flex flex-col flex-1 min-w-0">
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

          {/* 메타데이터 표시 */}
          {currentMetadata && !progressStatus && (
            <div className="px-4 py-2 bg-muted/30 border-t border-border flex items-center gap-2 text-sm">
              <span className="text-muted-foreground">Agent:</span>
              <span className="font-medium text-primary">{currentMetadata.agentName}</span>
              {currentMetadata.confidence !== undefined && (
                <span className="text-muted-foreground">
                  ({Math.round(currentMetadata.confidence * 100)}%)
                </span>
              )}
              {currentMetadata.routingMethod && (
                <span className="text-xs px-2 py-0.5 rounded-full bg-muted">
                  {currentMetadata.routingMethod}
                </span>
              )}
            </div>
          )}
        </div>
      )}

      <ChatMessages
        messages={messages}
        isStreaming={isStreaming}
        currentToolCalls={currentToolCalls}
        streamingContent={streamingContent}
        onClarificationSelect={handleClarificationSelect}
      />

      <ChatInput
        input={input}
        onChange={handleInputChange}
        onSubmit={handleSubmit}
        isLoading={isStreaming}
      />

      {/* 스트리밍 중 Stop 버튼 */}
      {isStreaming && (
        <div className="absolute bottom-24 left-1/2 -translate-x-1/2">
          <button
            onClick={handleStop}
            className="px-4 py-2 rounded-full bg-destructive text-destructive-foreground text-sm font-medium shadow-lg hover:bg-destructive/90 transition-colors"
          >
            Stop generating
          </button>
        </div>
      )}
    </div>
  )
}
