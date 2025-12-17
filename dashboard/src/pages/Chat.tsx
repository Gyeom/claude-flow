import { useState, useCallback } from 'react'
import { useChatContext } from '@/contexts/ChatContext'
import { ChatMessages, ChatInput, ChatSidebar } from '@/components/chat'

export function Chat() {
  const {
    messages,
    isStreaming,
    streamingContent,
    currentToolCalls,
    currentMetadata,
    selectedProject,
    selectedAgent,
    setSelectedProject,
    setSelectedAgent,
    sendMessage,
    stopStreaming,
  } = useChatContext()

  const [input, setInput] = useState('')

  const handleInputChange = useCallback((e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(e.target.value)
  }, [])

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault()
    if (!input.trim() || isStreaming) return

    const message = input
    setInput('')
    await sendMessage(message)
  }, [input, isStreaming, sendMessage])

  return (
    <div className="flex h-[calc(100vh-4rem)] -m-8 relative">
      <ChatSidebar
        selectedProject={selectedProject}
        onProjectChange={setSelectedProject}
        selectedAgent={selectedAgent}
        onAgentChange={setSelectedAgent}
      />
      <div className="flex flex-col flex-1 min-w-0 overflow-hidden">
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
              onClick={stopStreaming}
              className="px-4 py-2 rounded-full bg-destructive text-destructive-foreground text-sm font-medium shadow-lg hover:bg-destructive/90 transition-colors"
            >
              Stop generating
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
