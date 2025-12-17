import { useState } from 'react'
import { ChevronDown, ChevronRight, Terminal, Check, X, Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'

interface ToolCall {
  toolId: string
  toolName: string
  input?: Record<string, unknown>
  result?: string
  success?: boolean
  status: 'running' | 'completed' | 'error'
}

interface ToolCallDisplayProps {
  tool: ToolCall
}

export function ToolCallDisplay({ tool }: ToolCallDisplayProps) {
  const [isExpanded, setIsExpanded] = useState(false)

  const statusIcon = {
    running: <Loader2 className="h-4 w-4 animate-spin text-blue-500" />,
    completed: <Check className="h-4 w-4 text-green-500" />,
    error: <X className="h-4 w-4 text-red-500" />,
  }

  const statusColor = {
    running: 'border-blue-500/30 bg-blue-500/5',
    completed: 'border-green-500/30 bg-green-500/5',
    error: 'border-red-500/30 bg-red-500/5',
  }

  return (
    <div
      className={cn(
        'rounded-lg border p-3 my-2',
        statusColor[tool.status]
      )}
    >
      <button
        onClick={() => setIsExpanded(!isExpanded)}
        className="flex items-center gap-2 w-full text-left"
      >
        {isExpanded ? (
          <ChevronDown className="h-4 w-4 text-muted-foreground" />
        ) : (
          <ChevronRight className="h-4 w-4 text-muted-foreground" />
        )}
        <Terminal className="h-4 w-4 text-muted-foreground" />
        <span className="font-mono text-sm font-medium">{tool.toolName}</span>
        <span className="ml-auto">{statusIcon[tool.status]}</span>
      </button>

      {isExpanded && (
        <div className="mt-3 space-y-2">
          {tool.input && Object.keys(tool.input).length > 0 && (
            <div>
              <span className="text-xs text-muted-foreground font-medium">Input:</span>
              <pre className="mt-1 p-2 rounded bg-muted/50 text-xs font-mono overflow-x-auto max-h-32">
                {JSON.stringify(tool.input, null, 2)}
              </pre>
            </div>
          )}

          {tool.result && (
            <div>
              <span className="text-xs text-muted-foreground font-medium">Result:</span>
              <pre className="mt-1 p-2 rounded bg-muted/50 text-xs font-mono overflow-x-auto max-h-48 whitespace-pre-wrap">
                {tool.result}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

interface ToolCallsListProps {
  tools: ToolCall[]
}

export function ToolCallsList({ tools }: ToolCallsListProps) {
  if (tools.length === 0) return null

  return (
    <div className="space-y-1">
      {tools.map((tool) => (
        <ToolCallDisplay key={tool.toolId} tool={tool} />
      ))}
    </div>
  )
}
