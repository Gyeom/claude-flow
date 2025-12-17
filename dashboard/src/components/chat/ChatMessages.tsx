import { useRef, useEffect } from 'react'
import { User, Bot, Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { ToolCallsList } from './ToolCallDisplay'
import ReactMarkdown from 'react-markdown'

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

interface ChatMessagesProps {
  messages: Message[]
  isStreaming: boolean
  currentToolCalls?: ToolCall[]
  streamingContent?: string
}

export function ChatMessages({
  messages,
  isStreaming,
  currentToolCalls = [],
  streamingContent
}: ChatMessagesProps) {
  const messagesEndRef = useRef<HTMLDivElement>(null)

  // 새 메시지가 추가되면 자동 스크롤
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, streamingContent, currentToolCalls])

  if (messages.length === 0 && !isStreaming) {
    return (
      <div className="h-full flex items-center justify-center p-8">
        <div className="text-center max-w-md">
          <Bot className="h-16 w-16 mx-auto text-muted-foreground/50 mb-4" />
          <h3 className="text-lg font-medium mb-2">Claude Flow Chat</h3>
          <p className="text-muted-foreground text-sm">
            메시지를 입력하면 적합한 에이전트가 자동으로 선택됩니다.
            코드 리뷰, 버그 수정, 리팩토링 등 다양한 작업을 요청해보세요.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="h-full p-4 space-y-4">
      {messages.map((message) => (
        <MessageBubble key={message.id} message={message} />
      ))}

      {/* 스트리밍 중인 응답 */}
      {isStreaming && (
        <div className="flex gap-3">
          <div className="flex-shrink-0 w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center">
            <Bot className="h-5 w-5 text-primary" />
          </div>
          <div className="flex-1 max-w-[85%]">
            {/* 진행 중인 도구 호출 */}
            {currentToolCalls.length > 0 && (
              <ToolCallsList tools={currentToolCalls} />
            )}

            {/* 스트리밍 텍스트 */}
            {streamingContent && (
              <div className="rounded-lg bg-muted p-4">
                <div className="prose dark:prose-invert prose-sm max-w-none">
                  <ReactMarkdown>{streamingContent}</ReactMarkdown>
                </div>
              </div>
            )}

            {/* 스트리밍 인디케이터 */}
            {!streamingContent && currentToolCalls.length === 0 && (
              <div className="rounded-lg bg-muted p-4 flex items-center gap-2">
                <Loader2 className="h-4 w-4 animate-spin" />
                <span className="text-sm text-muted-foreground">응답 생성 중...</span>
              </div>
            )}
          </div>
        </div>
      )}

      <div ref={messagesEndRef} />
    </div>
  )
}

interface MessageBubbleProps {
  message: Message
}

function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user'

  return (
    <div className={cn('flex gap-3', isUser && 'flex-row-reverse')}>
      {/* 아바타 */}
      <div
        className={cn(
          'flex-shrink-0 w-8 h-8 rounded-full flex items-center justify-center',
          isUser ? 'bg-primary' : 'bg-primary/10'
        )}
      >
        {isUser ? (
          <User className="h-5 w-5 text-primary-foreground" />
        ) : (
          <Bot className="h-5 w-5 text-primary" />
        )}
      </div>

      {/* 메시지 내용 */}
      <div className={cn('flex-1 max-w-[85%]', isUser && 'flex flex-col items-end')}>
        {/* 에이전트 메타데이터 */}
        {!isUser && message.metadata?.agentName && (
          <div className="flex items-center gap-2 mb-1">
            <span className="text-xs font-medium text-primary">
              {message.metadata.agentName}
            </span>
            {message.metadata.confidence !== undefined && (
              <span className="text-xs text-muted-foreground">
                ({Math.round(message.metadata.confidence * 100)}% confidence)
              </span>
            )}
          </div>
        )}

        {/* 도구 호출 */}
        {message.toolCalls && message.toolCalls.length > 0 && (
          <ToolCallsList tools={message.toolCalls} />
        )}

        {/* 메시지 텍스트 */}
        <div
          className={cn(
            'rounded-lg p-4',
            isUser
              ? 'bg-primary text-primary-foreground'
              : 'bg-muted'
          )}
        >
          {isUser ? (
            <p className="whitespace-pre-wrap">{message.content}</p>
          ) : (
            <div className="prose dark:prose-invert prose-sm max-w-none">
              <ReactMarkdown>{message.content}</ReactMarkdown>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
