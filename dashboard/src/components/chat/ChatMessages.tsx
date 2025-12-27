import { useRef, useEffect } from 'react'
import { User, Bot, Sparkles, Clock, Zap, ThumbsUp, ThumbsDown } from 'lucide-react'
import { cn } from '@/lib/utils'
import { ToolCallsList } from './ToolCallDisplay'
import { MarkdownRenderer } from './MarkdownRenderer'
import { ClarificationButtons } from './ClarificationButtons'
import type { ClarificationRequest, ClarificationOption } from '../../types'

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
  clarification?: ClarificationRequest  // 프로젝트 선택 등 Clarification UI
  executionId?: string  // 피드백 제출용 ID
  metadata?: {
    agentId?: string
    agentName?: string
    confidence?: number
    routingMethod?: string
  }
  timestamp?: string
}

// 피드백 상태 타입
type FeedbackState = Record<string, 'thumbsup' | 'thumbsdown' | null>

interface ChatMessagesProps {
  messages: Message[]
  isStreaming: boolean
  currentToolCalls?: ToolCall[]
  streamingContent?: string
  onClarificationSelect?: (option: ClarificationOption, context?: Record<string, unknown>) => void
  feedbackState?: FeedbackState
  onFeedback?: (executionId: string, reaction: 'thumbsup' | 'thumbsdown') => void
}

// 타이핑 인디케이터
function TypingIndicator() {
  return (
    <div className="flex items-center gap-1.5 px-4 py-3">
      <div className="flex gap-1">
        {[0, 1, 2].map((i) => (
          <span
            key={i}
            className="w-2 h-2 rounded-full bg-primary/60 animate-bounce"
            style={{ animationDelay: `${i * 0.15}s` }}
          />
        ))}
      </div>
      <span className="text-sm text-muted-foreground ml-2">응답 생성 중...</span>
    </div>
  )
}

// 에이전트 배지
function AgentBadge({
  agentName,
  confidence,
  routingMethod
}: {
  agentName: string
  confidence?: number
  routingMethod?: string
}) {
  return (
    <div className="flex items-center gap-2 mb-2">
      <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-primary/10 border border-primary/20">
        <Zap className="h-3 w-3 text-primary" />
        <span className="text-xs font-medium text-primary">{agentName}</span>
      </div>
      {confidence !== undefined && (
        <span className="text-xs text-muted-foreground">
          {Math.round(confidence * 100)}% 신뢰도
        </span>
      )}
      {routingMethod && (
        <span className="text-xs px-2 py-0.5 rounded bg-muted text-muted-foreground">
          {routingMethod}
        </span>
      )}
    </div>
  )
}

// 빈 상태 화면
function EmptyState() {
  const suggestions = [
    { icon: '💻', text: '이 코드를 리뷰해줘' },
    { icon: '🐛', text: '버그를 찾아서 수정해줘' },
    { icon: '📝', text: '이 함수를 리팩토링해줘' },
    { icon: '📖', text: '이 프로젝트 구조를 설명해줘' },
  ]

  return (
    <div className="flex-1 flex items-center justify-center p-8">
      <div className="text-center max-w-lg">
        <div className="relative inline-block mb-6">
          <div className="absolute inset-0 bg-primary/20 blur-2xl rounded-full" />
          <Bot className="h-20 w-20 mx-auto text-primary relative" />
        </div>

        <h2 className="text-2xl font-bold mb-3">Claude Flow에 오신 것을 환영합니다</h2>
        <p className="text-muted-foreground mb-8">
          메시지를 입력하면 가장 적합한 에이전트가 자동으로 선택됩니다.
          <br />
          코드 리뷰, 버그 수정, 리팩토링 등 다양한 작업을 요청해보세요.
        </p>

        <div className="grid grid-cols-2 gap-3">
          {suggestions.map((suggestion, index) => (
            <button
              key={index}
              className="flex items-center gap-3 p-4 rounded-xl bg-muted/50 hover:bg-muted border border-border/50 hover:border-primary/30 transition-all text-left group"
            >
              <span className="text-2xl">{suggestion.icon}</span>
              <span className="text-sm text-muted-foreground group-hover:text-foreground transition-colors">
                {suggestion.text}
              </span>
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}

// 피드백 버튼 컴포넌트
function FeedbackButtons({
  executionId,
  currentFeedback,
  onFeedback
}: {
  executionId: string
  currentFeedback?: 'thumbsup' | 'thumbsdown' | null
  onFeedback: (executionId: string, reaction: 'thumbsup' | 'thumbsdown') => void
}) {
  return (
    <div className="flex items-center gap-1 mt-3 pt-3 border-t border-border/30">
      <span className="text-xs text-muted-foreground mr-2">이 응답이 도움이 되었나요?</span>
      <button
        onClick={() => onFeedback(executionId, 'thumbsup')}
        className={cn(
          'p-1.5 rounded-md transition-all',
          currentFeedback === 'thumbsup'
            ? 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400'
            : 'hover:bg-muted text-muted-foreground hover:text-foreground'
        )}
        title="도움이 됐어요"
      >
        <ThumbsUp className="h-4 w-4" />
      </button>
      <button
        onClick={() => onFeedback(executionId, 'thumbsdown')}
        className={cn(
          'p-1.5 rounded-md transition-all',
          currentFeedback === 'thumbsdown'
            ? 'bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-400'
            : 'hover:bg-muted text-muted-foreground hover:text-foreground'
        )}
        title="개선이 필요해요"
      >
        <ThumbsDown className="h-4 w-4" />
      </button>
    </div>
  )
}

// 메시지 버블
function MessageBubble({
  message,
  onClarificationSelect,
  feedbackState,
  onFeedback,
  isLatest = false
}: {
  message: Message
  onClarificationSelect?: (option: ClarificationOption, context?: Record<string, unknown>) => void
  feedbackState?: FeedbackState
  onFeedback?: (executionId: string, reaction: 'thumbsup' | 'thumbsdown') => void
  isLatest?: boolean
}) {
  const isUser = message.role === 'user'

  return (
    <div
      className={cn(
        'flex gap-4 px-4 py-6 transition-colors',
        isUser ? 'bg-transparent' : 'bg-muted/30'
      )}
    >
      {/* 아바타 */}
      <div
        className={cn(
          'flex-shrink-0 w-10 h-10 rounded-xl flex items-center justify-center shadow-sm',
          isUser
            ? 'bg-gradient-to-br from-blue-500 to-blue-600'
            : 'bg-gradient-to-br from-violet-500 to-purple-600'
        )}
      >
        {isUser ? (
          <User className="h-5 w-5 text-white" />
        ) : (
          <Sparkles className="h-5 w-5 text-white" />
        )}
      </div>

      {/* 메시지 내용 */}
      <div className="flex-1 min-w-0 space-y-2">
        {/* 헤더 */}
        <div className="flex items-center gap-3">
          <span className="font-semibold text-sm">
            {isUser ? 'You' : 'Claude Flow'}
          </span>
          {message.timestamp && (
            <span className="flex items-center gap-1 text-xs text-muted-foreground">
              <Clock className="h-3 w-3" />
              {new Date(message.timestamp).toLocaleTimeString('ko-KR', {
                hour: '2-digit',
                minute: '2-digit'
              })}
            </span>
          )}
        </div>

        {/* 에이전트 정보 */}
        {!isUser && message.metadata?.agentName && (
          <AgentBadge
            agentName={message.metadata.agentName}
            confidence={message.metadata.confidence}
            routingMethod={message.metadata.routingMethod}
          />
        )}

        {/* 도구 호출 */}
        {message.toolCalls && message.toolCalls.length > 0 && (
          <div className="mb-3">
            <ToolCallsList tools={message.toolCalls} />
          </div>
        )}

        {/* 메시지 텍스트 */}
        <div className={cn(
          'max-w-none',
          isUser ? 'text-foreground' : ''
        )}>
          {isUser ? (
            <p className="whitespace-pre-wrap leading-relaxed">{message.content}</p>
          ) : (
            <MarkdownRenderer content={message.content} />
          )}
        </div>

        {/* Clarification 버튼 UI (프로젝트 선택 등) */}
        {!isUser && message.clarification && onClarificationSelect && isLatest && (
          <ClarificationButtons
            clarification={message.clarification}
            onSelect={(option) => onClarificationSelect(option, message.clarification?.context)}
          />
        )}

        {/* 피드백 버튼 (어시스턴트 메시지 + executionId가 있는 경우만) */}
        {!isUser && message.executionId && onFeedback && (
          <FeedbackButtons
            executionId={message.executionId}
            currentFeedback={feedbackState?.[message.executionId]}
            onFeedback={onFeedback}
          />
        )}
      </div>
    </div>
  )
}

// 스트리밍 메시지
function StreamingMessage({
  content,
  toolCalls,
  metadata
}: {
  content?: string
  toolCalls: ToolCall[]
  metadata?: Message['metadata']
}) {
  return (
    <div className="flex gap-4 px-4 py-6 bg-muted/30">
      {/* 아바타 */}
      <div className="flex-shrink-0 w-10 h-10 rounded-xl flex items-center justify-center shadow-sm bg-gradient-to-br from-violet-500 to-purple-600">
        <Sparkles className="h-5 w-5 text-white animate-pulse" />
      </div>

      {/* 내용 */}
      <div className="flex-1 min-w-0 space-y-2">
        <div className="flex items-center gap-3">
          <span className="font-semibold text-sm">Claude Flow</span>
          <span className="flex items-center gap-1 text-xs text-primary">
            <span className="w-1.5 h-1.5 rounded-full bg-primary animate-pulse" />
            응답 중
          </span>
        </div>

        {/* 에이전트 정보 */}
        {metadata?.agentName && (
          <AgentBadge
            agentName={metadata.agentName}
            confidence={metadata.confidence}
            routingMethod={metadata.routingMethod}
          />
        )}

        {/* 진행 중인 도구 호출 */}
        {toolCalls.length > 0 && (
          <div className="mb-3">
            <ToolCallsList tools={toolCalls} />
          </div>
        )}

        {/* 스트리밍 텍스트 */}
        {content ? (
          <MarkdownRenderer content={content} />
        ) : toolCalls.length === 0 ? (
          <TypingIndicator />
        ) : null}
      </div>
    </div>
  )
}

export function ChatMessages({
  messages,
  isStreaming,
  currentToolCalls = [],
  streamingContent,
  onClarificationSelect,
  feedbackState,
  onFeedback
}: ChatMessagesProps) {
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  // 새 메시지가 추가되면 자동 스크롤
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, streamingContent, currentToolCalls])

  if (messages.length === 0 && !isStreaming) {
    return <EmptyState />
  }

  return (
    <div ref={containerRef} className="flex-1 overflow-y-auto">
      <div className="divide-y divide-border/30">
        {messages.map((message, index) => (
          <MessageBubble
            key={message.id}
            message={message}
            onClarificationSelect={onClarificationSelect}
            feedbackState={feedbackState}
            onFeedback={onFeedback}
            isLatest={index === messages.length - 1 && !isStreaming}
          />
        ))}

        {/* 스트리밍 중인 응답 */}
        {isStreaming && (
          <StreamingMessage
            content={streamingContent}
            toolCalls={currentToolCalls}
          />
        )}
      </div>
      <div ref={messagesEndRef} className="h-4" />
    </div>
  )
}
