import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import {
  Send,
  Bot,
  Target,
  Zap,
  Clock,
  CheckCircle,
  AlertCircle,
} from 'lucide-react'
import { Card, CardHeader } from '@/components/Card'
import { routingApi } from '@/lib/api'
import { cn } from '@/lib/utils'

interface RoutingResult {
  agentId: string
  agentName: string
  confidence: number
  method: 'keyword' | 'semantic' | 'llm' | 'default'
  durationMs: number
  alternatives?: Array<{
    agentId: string
    agentName: string
    confidence: number
  }>
}

export function Classify() {
  const [prompt, setPrompt] = useState('')
  const [projectId, setProjectId] = useState('')
  const [results, setResults] = useState<RoutingResult[]>([])
  const [latestResult, setLatestResult] = useState<RoutingResult | null>(null)

  const classifyMutation = useMutation({
    mutationFn: async (data: { prompt: string; projectId?: string }) => {
      const startTime = Date.now()
      const result = await routingApi.classify(data.prompt, data.projectId)
      const durationMs = Date.now() - startTime
      return {
        ...result,
        durationMs,
      }
    },
    onSuccess: (data) => {
      const result: RoutingResult = {
        agentId: data.agentId,
        agentName: data.agentName,
        confidence: data.confidence,
        method: data.method as RoutingResult['method'],
        durationMs: data.durationMs,
        alternatives: data.alternatives,
      }
      setLatestResult(result)
      setResults(prev => [result, ...prev].slice(0, 10))
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!prompt.trim()) return
    classifyMutation.mutate({ prompt, projectId: projectId || undefined })
  }

  const getMethodColor = (method: string) => {
    switch (method) {
      case 'keyword': return 'bg-green-500/10 text-green-500 border-green-500/30'
      case 'semantic': return 'bg-blue-500/10 text-blue-500 border-blue-500/30'
      case 'llm': return 'bg-purple-500/10 text-purple-500 border-purple-500/30'
      default: return 'bg-gray-500/10 text-gray-500 border-gray-500/30'
    }
  }

  const getConfidenceColor = (confidence: number) => {
    if (confidence >= 0.8) return 'text-green-500'
    if (confidence >= 0.5) return 'text-yellow-500'
    return 'text-red-500'
  }

  // Display result - either from API or show placeholder
  const displayResult = latestResult

  return (
    <div className="space-y-8">
      {/* Header */}
      <div>
        <h1 className="text-3xl font-bold">Classification Test</h1>
        <p className="text-muted-foreground mt-1">
          Test agent routing and classification logic
        </p>
      </div>

      {/* Input Form */}
      <Card>
        <CardHeader
          title="Test Prompt"
          description="Enter a prompt to see which agent would be selected"
        />
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm font-medium mb-2">Project ID (optional)</label>
            <input
              type="text"
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              placeholder="e.g., my-project"
              className="w-full px-4 py-2 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50 focus:border-primary"
            />
          </div>
          <div>
            <label className="block text-sm font-medium mb-2">Prompt</label>
            <textarea
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              placeholder="Enter your test prompt here..."
              rows={4}
              className="w-full px-4 py-2 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50 focus:border-primary resize-none"
            />
          </div>
          <button
            type="submit"
            disabled={classifyMutation.isPending || !prompt.trim()}
            className={cn(
              "flex items-center gap-2 px-6 py-2.5 rounded-lg font-medium transition-colors",
              "bg-primary text-primary-foreground hover:bg-primary/90",
              "disabled:opacity-50 disabled:cursor-not-allowed"
            )}
          >
            {classifyMutation.isPending ? (
              <div className="animate-spin rounded-full h-4 w-4 border-2 border-current border-t-transparent" />
            ) : (
              <Send className="h-4 w-4" />
            )}
            Classify
          </button>
        </form>

        {classifyMutation.isError && (
          <div className="mt-4 p-4 rounded-lg bg-destructive/10 text-destructive border border-destructive/20">
            <div className="flex items-center gap-2">
              <AlertCircle className="h-5 w-5" />
              <p className="text-sm">Classification failed. Please try again.</p>
            </div>
          </div>
        )}
      </Card>

      {/* Result Display */}
      {displayResult ? (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Selected Agent */}
          <Card>
            <CardHeader
              title="Selected Agent"
              description="The agent that would handle this request"
            />
            <div className="space-y-4">
              <div className="flex items-center gap-4 p-4 rounded-lg bg-primary/5 border border-primary/20">
                <div className="p-3 rounded-lg bg-primary/10">
                  <Bot className="h-6 w-6 text-primary" />
                </div>
                <div className="flex-1">
                  <h3 className="font-semibold text-lg">{displayResult.agentName}</h3>
                  <p className="text-sm text-muted-foreground">{displayResult.agentId}</p>
                </div>
                <div className="text-right">
                  <p className={cn("text-2xl font-bold", getConfidenceColor(displayResult.confidence))}>
                    {(displayResult.confidence * 100).toFixed(0)}%
                  </p>
                  <p className="text-xs text-muted-foreground">confidence</p>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div className="p-3 rounded-lg bg-muted/50">
                  <div className="flex items-center gap-2 text-sm text-muted-foreground mb-1">
                    <Target className="h-4 w-4" />
                    Method
                  </div>
                  <span className={cn(
                    "inline-flex px-2 py-1 rounded text-xs font-medium border",
                    getMethodColor(displayResult.method)
                  )}>
                    {displayResult.method.toUpperCase()}
                  </span>
                </div>
                <div className="p-3 rounded-lg bg-muted/50">
                  <div className="flex items-center gap-2 text-sm text-muted-foreground mb-1">
                    <Clock className="h-4 w-4" />
                    Duration
                  </div>
                  <p className="font-semibold">{displayResult.durationMs}ms</p>
                </div>
              </div>
            </div>
          </Card>

          {/* Alternatives */}
          <Card>
            <CardHeader
              title="Alternative Matches"
              description="Other agents that could handle this request"
            />
            <div className="space-y-3">
              {displayResult.alternatives?.map((alt, index) => (
                <div
                  key={alt.agentId}
                  className="flex items-center gap-3 p-3 rounded-lg border border-border hover:bg-muted/50 transition-colors"
                >
                  <span className="text-sm text-muted-foreground w-6">#{index + 2}</span>
                  <div className="flex-1">
                    <p className="font-medium">{alt.agentName}</p>
                    <p className="text-xs text-muted-foreground">{alt.agentId}</p>
                  </div>
                  <div className="text-right">
                    <p className={cn("font-semibold", getConfidenceColor(alt.confidence))}>
                      {(alt.confidence * 100).toFixed(0)}%
                    </p>
                  </div>
                </div>
              ))}
              {(!displayResult.alternatives || displayResult.alternatives.length === 0) && (
                <p className="text-center text-muted-foreground py-4">
                  No alternative matches found
                </p>
              )}
            </div>
          </Card>
        </div>
      ) : (
        <Card className="border-dashed">
          <div className="flex flex-col items-center justify-center py-12 text-center">
            <div className="p-4 rounded-full bg-muted mb-4">
              <Target className="h-8 w-8 text-muted-foreground" />
            </div>
            <h3 className="font-semibold mb-1">No Classification Result</h3>
            <p className="text-sm text-muted-foreground">
              Enter a prompt above and click "Classify" to see routing results
            </p>
          </div>
        </Card>
      )}

      {/* Routing Method Guide */}
      <Card>
        <CardHeader
          title="Routing Methods"
          description="How agent selection works"
        />
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <div className="p-4 rounded-lg border border-green-500/30 bg-green-500/5">
            <div className="flex items-center gap-2 mb-2">
              <Zap className="h-5 w-5 text-green-500" />
              <h4 className="font-semibold text-green-500">Keyword</h4>
            </div>
            <p className="text-sm text-muted-foreground">
              Exact keyword matching. Fastest and most precise for known patterns.
            </p>
          </div>
          <div className="p-4 rounded-lg border border-blue-500/30 bg-blue-500/5">
            <div className="flex items-center gap-2 mb-2">
              <Target className="h-5 w-5 text-blue-500" />
              <h4 className="font-semibold text-blue-500">Semantic</h4>
            </div>
            <p className="text-sm text-muted-foreground">
              Embedding-based similarity. Understands meaning and context.
            </p>
          </div>
          <div className="p-4 rounded-lg border border-purple-500/30 bg-purple-500/5">
            <div className="flex items-center gap-2 mb-2">
              <Bot className="h-5 w-5 text-purple-500" />
              <h4 className="font-semibold text-purple-500">LLM</h4>
            </div>
            <p className="text-sm text-muted-foreground">
              AI-powered classification. Most flexible but slowest.
            </p>
          </div>
          <div className="p-4 rounded-lg border border-gray-500/30 bg-gray-500/5">
            <div className="flex items-center gap-2 mb-2">
              <AlertCircle className="h-5 w-5 text-gray-500" />
              <h4 className="font-semibold text-gray-500">Default</h4>
            </div>
            <p className="text-sm text-muted-foreground">
              Fallback agent when no confident match is found.
            </p>
          </div>
        </div>
      </Card>

      {/* History */}
      {results.length > 0 && (
        <Card>
          <CardHeader
            title="Classification History"
            description="Recent classification results"
          />
          <div className="space-y-2">
            {results.map((result, index) => (
              <div
                key={index}
                className="flex items-center gap-4 p-3 rounded-lg border border-border"
              >
                <CheckCircle className="h-5 w-5 text-green-500" />
                <div className="flex-1">
                  <p className="font-medium">{result.agentName}</p>
                </div>
                <span className={cn(
                  "px-2 py-1 rounded text-xs font-medium border",
                  getMethodColor(result.method)
                )}>
                  {result.method}
                </span>
                <span className={cn("font-semibold", getConfidenceColor(result.confidence))}>
                  {(result.confidence * 100).toFixed(0)}%
                </span>
                <span className="text-sm text-muted-foreground">
                  {result.durationMs}ms
                </span>
              </div>
            ))}
          </div>
        </Card>
      )}
    </div>
  )
}
