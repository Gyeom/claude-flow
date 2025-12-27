import { createContext, useContext, useState, useCallback, useRef, ReactNode } from 'react'
import { toast } from 'sonner'
import type { ClarificationRequest, ClarificationOption } from '../types'

interface ToolCall {
  toolId: string
  toolName: string
  input?: Record<string, unknown>
  result?: string
  success?: boolean
  status: 'running' | 'completed' | 'error'
}

/**
 * 진행 상황 정보
 */
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
  clarification?: ClarificationRequest  // 프로젝트 선택 등 Clarification UI
  executionId?: string  // 피드백 제출용 ID
  metadata?: {
    agentId?: string
    agentName?: string
    confidence?: number
    routingMethod?: string
  }
}

// 피드백 상태 (메시지별)
type FeedbackState = Record<string, 'thumbsup' | 'thumbsdown' | null>

interface ChatContextType {
  messages: Message[]
  isStreaming: boolean
  streamingContent: string
  currentToolCalls: ToolCall[]
  currentMetadata: Message['metadata']
  progressStatus: ProgressStatus | null
  currentClarification: ClarificationRequest | null
  isPanelOpen: boolean
  selectedProject: string | null
  feedbackState: FeedbackState
  setSelectedProject: (project: string | null) => void
  openPanel: () => void
  closePanel: () => void
  togglePanel: () => void
  sendMessage: (content: string) => Promise<void>
  sendClarificationResponse: (option: ClarificationOption, originalContext?: Record<string, unknown>) => Promise<void>
  submitFeedback: (executionId: string, reaction: 'thumbsup' | 'thumbsdown') => Promise<void>
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

// 세션 컨텍스트 타입 (후속 질문 시 에이전트 유지용)
interface SessionContext {
  lastAgentId?: string
  lastTopic?: string  // 'mr-review', 'bug-fix' 등
  mrNumber?: number
  gitlabPath?: string
  projectId?: string
}

export function ChatProvider({ children }: ChatProviderProps) {
  const [messages, setMessages] = useState<Message[]>([])
  const [isStreaming, setIsStreaming] = useState(false)
  const [streamingContent, setStreamingContent] = useState('')
  const [currentToolCalls, setCurrentToolCalls] = useState<ToolCall[]>([])
  const [currentMetadata, setCurrentMetadata] = useState<Message['metadata']>()
  const [progressStatus, setProgressStatus] = useState<ProgressStatus | null>(null)
  const [currentClarification, setCurrentClarification] = useState<ClarificationRequest | null>(null)
  const [isPanelOpen, setIsPanelOpen] = useState(false)
  const [selectedProject, setSelectedProject] = useState<string | null>(null)
  const [feedbackState, setFeedbackState] = useState<FeedbackState>({})
  // 세션 컨텍스트 (후속 질문 시 동일 에이전트 유지)
  const [sessionContext, setSessionContext] = useState<SessionContext>({})

  const abortControllerRef = useRef<AbortController | null>(null)

  const openPanel = useCallback(() => setIsPanelOpen(true), [])
  const closePanel = useCallback(() => setIsPanelOpen(false), [])
  const togglePanel = useCallback(() => setIsPanelOpen(prev => !prev), [])

  const clearMessages = useCallback(() => {
    setMessages([])
    setStreamingContent('')
    setCurrentToolCalls([])
    setCurrentMetadata(undefined)
    setProgressStatus(null)
    setCurrentClarification(null)
    setFeedbackState({})
    // 세션 컨텍스트도 초기화
    setSessionContext({})
  }, [])

  const stopStreaming = useCallback(() => {
    abortControllerRef.current?.abort()
  }, [])

  // 에이전트와 메시지로부터 토픽 감지
  const detectTopic = (agentId: string, message: string): string | undefined => {
    if (agentId === 'code-reviewer') {
      if (/mr|merge\s*request|리뷰/i.test(message)) return 'mr-review'
      if (/pr|pull\s*request/i.test(message)) return 'pr-review'
      return 'code-review'
    }
    if (agentId === 'bug-fixer') return 'bug-fix'
    if (agentId === 'refactor') return 'refactor'
    if (agentId === 'test-writer') return 'test-writing'
    if (agentId === 'security-reviewer') return 'security-review'
    return undefined
  }

  // 메시지에서 MR 번호 추출
  const extractMrNumber = (message: string): number | undefined => {
    // MR/MR!/!123 형식
    const mrMatch = message.match(/(?:MR|mr|!)\s*#?(\d+)/i)
    if (mrMatch) return parseInt(mrMatch[1], 10)
    // "merge request 123" 형식
    const fullMatch = message.match(/merge\s*request\s*#?(\d+)/i)
    if (fullMatch) return parseInt(fullMatch[1], 10)
    return undefined
  }

  const sendMessage = useCallback(async (content: string) => {
    const userMessage = content.trim()
    if (!userMessage || isStreaming) return

    // Clarification 초기화
    setCurrentClarification(null)

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
          projectId: selectedProject,
          // 세션 컨텍스트 전송 (후속 질문 시 에이전트 유지)
          sessionContext: Object.keys(sessionContext).length > 0 ? sessionContext : undefined,
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
      let executionId: string | undefined = undefined  // 피드백 제출용 ID
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
          executionId,  // 피드백 제출용 ID
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

              // 세션 컨텍스트 업데이트 (후속 질문 시 동일 에이전트 유지)
              if (data.agentId) {
                const topic = detectTopic(data.agentId, userMessage)
                const mrNum = extractMrNumber(userMessage)
                setSessionContext(prev => ({
                  ...prev,
                  lastAgentId: data.agentId,
                  lastTopic: topic,
                  mrNumber: mrNum ?? prev.mrNumber,
                  projectId: selectedProject ?? prev.projectId,
                }))
              }
              return false

            case 'progress':
              // 진행 상황 업데이트
              setProgressStatus({
                step: data.step,
                message: data.message,
                timestamp: data.timestamp || Date.now(),
                detail: data.detail,
              })
              return false

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
              return true  // 스트림 종료 - 사용자 선택 대기

            case 'done':
              // 스트림 완료 - executionId 캡처 후 루프 종료
              executionId = data.executionId
              setProgressStatus(null)
              return true

            case 'error':
              toast.error(data.message || 'An error occurred')
              setProgressStatus(null)
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
      setProgressStatus(null)
      abortControllerRef.current = null
    }
  }, [isStreaming, messages, selectedProject, sessionContext])

  /**
   * Clarification 응답 전송
   * 프로젝트 선택 등의 버튼 클릭 시 호출
   */
  const sendClarificationResponse = useCallback(async (
    option: ClarificationOption,
    originalContext?: Record<string, unknown>
  ) => {
    // 현재 clarification 초기화
    setCurrentClarification(null)

    // 원본 요청 + 선택된 옵션을 결합하여 새 메시지 생성
    const originalPrompt = originalContext?.originalPrompt as string || ''
    const enhancedMessage = originalPrompt
      ? `${originalPrompt} [프로젝트: ${option.id}]`
      : `선택: ${option.label} (${option.id})`

    // 새 메시지로 전송
    await sendMessage(enhancedMessage)
  }, [sendMessage])

  /**
   * 피드백 제출
   * 어시스턴트 메시지에 대한 thumbsup/thumbsdown 피드백
   */
  const submitFeedback = useCallback(async (
    executionId: string,
    reaction: 'thumbsup' | 'thumbsdown'
  ) => {
    try {
      // 이미 동일한 피드백이 있으면 삭제 (토글)
      const currentFeedback = feedbackState[executionId]
      const action = currentFeedback === reaction ? 'delete' : 'upsert'

      const response = await fetch('/api/v1/feedback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          executionId,
          userId: 'chat-user',  // TODO: 실제 사용자 ID로 대체
          reaction,
          action,
          source: 'chat'  // Chat에서 온 피드백임을 표시
        })
      })

      if (!response.ok) {
        throw new Error('Failed to submit feedback')
      }

      // 피드백 상태 업데이트
      setFeedbackState(prev => ({
        ...prev,
        [executionId]: action === 'delete' ? null : reaction
      }))

      toast.success(action === 'delete' ? '피드백이 취소되었습니다' : '피드백이 제출되었습니다')
    } catch (error) {
      console.error('Feedback error:', error)
      toast.error('피드백 제출에 실패했습니다')
    }
  }, [feedbackState])

  const value: ChatContextType = {
    messages,
    isStreaming,
    streamingContent,
    currentToolCalls,
    currentMetadata,
    progressStatus,
    currentClarification,
    isPanelOpen,
    selectedProject,
    feedbackState,
    setSelectedProject,
    openPanel,
    closePanel,
    togglePanel,
    sendMessage,
    sendClarificationResponse,
    submitFeedback,
    stopStreaming,
    clearMessages,
  }

  return (
    <ChatContext.Provider value={value}>
      {children}
    </ChatContext.Provider>
  )
}
