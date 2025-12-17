import { useState, useCallback, useRef } from 'react'
import { ChatMessages } from './ChatMessages'
import { ChatInput } from './ChatInput'
import { toast } from 'sonner'

interface ToolCall {
  toolId: string
  toolName: string
  input?: Record<string, unknown>
  result?: string
  success?: boolean
  status: 'running' | 'completed' | 'error'
}

interface Message {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  toolCalls?: ToolCall[]
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

            case 'done':
              // 완료 처리는 루프 종료 후
              break

            case 'error':
              toast.error(data.message || 'An error occurred')
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
      abortControllerRef.current = null
    }
  }, [input, isStreaming, messages, projectId, agentId])

  const handleStop = useCallback(() => {
    abortControllerRef.current?.abort()
  }, [])

  return (
    <div className="flex flex-col flex-1 min-w-0">
      {/* 메타데이터 헤더 */}
      {currentMetadata && isStreaming && (
        <div className="px-4 py-2 bg-muted/50 border-b border-border flex items-center gap-2 text-sm">
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

      <ChatMessages
        messages={messages}
        isStreaming={isStreaming}
        currentToolCalls={currentToolCalls}
        streamingContent={streamingContent}
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
