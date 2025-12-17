import { createContext, useContext, useState, useCallback, useRef, ReactNode } from 'react'
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

interface ChatContextType {
  messages: Message[]
  isStreaming: boolean
  streamingContent: string
  currentToolCalls: ToolCall[]
  currentMetadata: Message['metadata']
  isPanelOpen: boolean
  selectedProject: string | null
  selectedAgent: string | null
  setSelectedProject: (project: string | null) => void
  setSelectedAgent: (agent: string | null) => void
  openPanel: () => void
  closePanel: () => void
  togglePanel: () => void
  sendMessage: (content: string) => Promise<void>
  stopStreaming: () => void
  clearMessages: () => void
}

const ChatContext = createContext<ChatContextType | null>(null)

export function useChatContext() {
  const context = useContext(ChatContext)
  if (!context) {
    throw new Error('useChatContext must be used within ChatProvider')
  }
  return context
}

interface ChatProviderProps {
  children: ReactNode
}

export function ChatProvider({ children }: ChatProviderProps) {
  const [messages, setMessages] = useState<Message[]>([])
  const [isStreaming, setIsStreaming] = useState(false)
  const [streamingContent, setStreamingContent] = useState('')
  const [currentToolCalls, setCurrentToolCalls] = useState<ToolCall[]>([])
  const [currentMetadata, setCurrentMetadata] = useState<Message['metadata']>()
  const [isPanelOpen, setIsPanelOpen] = useState(false)
  const [selectedProject, setSelectedProject] = useState<string | null>(null)
  const [selectedAgent, setSelectedAgent] = useState<string | null>(null)

  const abortControllerRef = useRef<AbortController | null>(null)

  const openPanel = useCallback(() => setIsPanelOpen(true), [])
  const closePanel = useCallback(() => setIsPanelOpen(false), [])
  const togglePanel = useCallback(() => setIsPanelOpen(prev => !prev), [])

  const clearMessages = useCallback(() => {
    setMessages([])
    setStreamingContent('')
    setCurrentToolCalls([])
    setCurrentMetadata(undefined)
  }, [])

  const stopStreaming = useCallback(() => {
    abortControllerRef.current?.abort()
  }, [])

  const sendMessage = useCallback(async (content: string) => {
    const userMessage = content.trim()
    if (!userMessage || isStreaming) return

    // 사용자 메시지 추가
    const userMessageObj: Message = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: userMessage,
    }
    setMessages(prev => [...prev, userMessageObj])
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
          projectId: selectedProject,
          agentId: selectedAgent,
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
      let streamComplete = false

      while (!streamComplete) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // SSE 이벤트 파싱
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        let eventType = ''
        let eventData = ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventType = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            eventData = line.slice(5).trim()
          } else if (line === '' && eventType && eventData) {
            try {
              const shouldBreak = processEvent(eventType, eventData)
              if (shouldBreak) {
                streamComplete = true
                break
              }
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

      function processEvent(type: string, dataStr: string): boolean {
        if (type === 'heartbeat') return false

        try {
          const data = JSON.parse(dataStr)

          switch (type) {
            case 'text':
              accumulatedContent += data.content || ''
              setStreamingContent(accumulatedContent)
              return false

            case 'tool_start':
              const newTool: ToolCall = {
                toolId: data.toolId,
                toolName: data.toolName,
                input: data.input,
                status: 'running',
              }
              toolCalls = [...toolCalls, newTool]
              setCurrentToolCalls([...toolCalls])
              return false

            case 'tool_end':
              toolCalls = toolCalls.map(t =>
                t.toolId === data.toolId
                  ? { ...t, result: data.result, success: data.success, status: data.success ? 'completed' : 'error' as const }
                  : t
              )
              setCurrentToolCalls([...toolCalls])
              return false

            case 'metadata':
              metadata = {
                agentId: data.agentId,
                agentName: data.agentName,
                confidence: data.confidence,
                routingMethod: data.routingMethod,
              }
              setCurrentMetadata(metadata)
              return false

            case 'done':
              // 스트림 완료 - 루프 종료
              return true

            case 'error':
              toast.error(data.message || 'An error occurred')
              return true  // 에러 시에도 스트림 종료
          }
        } catch (err) {
          console.error('Failed to parse event data:', dataStr, err)
        }
        return false
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
  }, [isStreaming, messages, selectedProject, selectedAgent])

  const value: ChatContextType = {
    messages,
    isStreaming,
    streamingContent,
    currentToolCalls,
    currentMetadata,
    isPanelOpen,
    selectedProject,
    selectedAgent,
    setSelectedProject,
    setSelectedAgent,
    openPanel,
    closePanel,
    togglePanel,
    sendMessage,
    stopStreaming,
    clearMessages,
  }

  return (
    <ChatContext.Provider value={value}>
      {children}
    </ChatContext.Provider>
  )
}
