import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Bot,
  Plus,
  Edit,
  Trash2,
  Power,
  PowerOff,
  ChevronDown,
  ChevronUp,
  AlertCircle,
  RefreshCw,
} from 'lucide-react'
import { toast } from 'sonner'
import { Card, CardHeader } from '@/components/Card'
import { PriorityBadge, KeywordBadge } from '@/components/DataTable'
import { agentsApi } from '@/lib/api'
import { cn } from '@/lib/utils'

export function Agents() {
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [showCreateModal, setShowCreateModal] = useState(false)
  const queryClient = useQueryClient()

  const { data: agents, isLoading, error } = useQuery({
    queryKey: ['agents'],
    queryFn: agentsApi.getAll,
  })

  const toggleEnabledMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      agentsApi.setEnabled(id, enabled),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agents'] })
      toast.success('Agent status updated')
    },
    onError: () => {
      toast.error('Failed to update agent status')
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => agentsApi.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agents'] })
      toast.success('Agent deleted')
    },
    onError: () => {
      toast.error('Failed to delete agent')
    },
  })

  // Use API data directly
  const agentList = agents || []

  const isBuiltIn = (id: string) => ['general', 'code-reviewer', 'bug-fixer'].includes(id)

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-4">
        <AlertCircle className="h-12 w-12 text-destructive" />
        <p className="text-muted-foreground">Failed to load agents</p>
        <button
          onClick={() => queryClient.invalidateQueries({ queryKey: ['agents'] })}
          className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground"
        >
          <RefreshCw className="h-4 w-4" />
          Retry
        </button>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Agents</h1>
          <p className="text-muted-foreground mt-1">
            Manage AI agents and their configurations
          </p>
        </div>
        <button
          onClick={() => setShowCreateModal(true)}
          className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
        >
          <Plus className="h-4 w-4" />
          Create Agent
        </button>
      </div>

      {/* Agent List */}
      {isLoading ? (
        <div className="flex items-center justify-center h-64">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
        </div>
      ) : agentList.length === 0 ? (
        <Card className="border-dashed">
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <div className="p-4 rounded-full bg-muted mb-4">
              <Bot className="h-8 w-8 text-muted-foreground" />
            </div>
            <h3 className="font-semibold mb-1">No Agents Found</h3>
            <p className="text-sm text-muted-foreground mb-4">
              Create your first agent to get started
            </p>
            <button
              onClick={() => setShowCreateModal(true)}
              className="px-4 py-2 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            >
              Create Agent
            </button>
          </div>
        </Card>
      ) : (
        <div className="grid gap-4">
          {agentList.map((agent) => (
            <Card key={agent.id} className="p-0 overflow-hidden">
              {/* Agent Header */}
              <div
                className={cn(
                  'p-4 flex items-center justify-between cursor-pointer hover:bg-muted/50 transition-colors',
                  !agent.enabled && 'opacity-50'
                )}
                onClick={() => setExpandedId(expandedId === agent.id ? null : agent.id)}
              >
                <div className="flex items-center gap-4">
                  <div className={cn(
                    'p-2 rounded-lg',
                    agent.enabled ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'
                  )}>
                    <Bot className="h-6 w-6" />
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <h3 className="font-semibold">{agent.name}</h3>
                      <PriorityBadge priority={agent.priority} />
                      {isBuiltIn(agent.id) && (
                        <span className="text-xs px-2 py-0.5 rounded bg-blue-500/10 text-blue-500">
                          Built-in
                        </span>
                      )}
                    </div>
                    <p className="text-sm text-muted-foreground">{agent.description}</p>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  {/* Toggle Enable */}
                  <button
                    onClick={(e) => {
                      e.stopPropagation()
                      toggleEnabledMutation.mutate({ id: agent.id, enabled: !agent.enabled })
                    }}
                    className={cn(
                      'p-2 rounded-lg transition-colors',
                      agent.enabled
                        ? 'hover:bg-green-500/10 text-green-500'
                        : 'hover:bg-muted text-muted-foreground'
                    )}
                    title={agent.enabled ? 'Disable' : 'Enable'}
                  >
                    {agent.enabled ? <Power className="h-5 w-5" /> : <PowerOff className="h-5 w-5" />}
                  </button>

                  {/* Edit */}
                  <button
                    onClick={(e) => {
                      e.stopPropagation()
                      toast.info('Edit modal coming soon')
                    }}
                    className="p-2 rounded-lg hover:bg-muted text-muted-foreground"
                    title="Edit"
                  >
                    <Edit className="h-5 w-5" />
                  </button>

                  {/* Delete */}
                  {!isBuiltIn(agent.id) && (
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        if (confirm('Are you sure you want to delete this agent?')) {
                          deleteMutation.mutate(agent.id)
                        }
                      }}
                      className="p-2 rounded-lg hover:bg-destructive/10 text-destructive"
                      title="Delete"
                    >
                      <Trash2 className="h-5 w-5" />
                    </button>
                  )}

                  {/* Expand */}
                  {expandedId === agent.id ? (
                    <ChevronUp className="h-5 w-5 text-muted-foreground" />
                  ) : (
                    <ChevronDown className="h-5 w-5 text-muted-foreground" />
                  )}
                </div>
              </div>

              {/* Expanded Details */}
              {expandedId === agent.id && (
                <div className="border-t border-border p-4 bg-muted/30 space-y-4">
                  {/* Keywords */}
                  <div>
                    <h4 className="text-sm font-medium mb-2">Keywords</h4>
                    <div className="flex flex-wrap">
                      {agent.keywords.map((kw) => (
                        <KeywordBadge key={kw} keyword={kw} />
                      ))}
                    </div>
                  </div>

                  {/* Examples */}
                  {agent.examples.length > 0 && (
                    <div>
                      <h4 className="text-sm font-medium mb-2">Semantic Routing Examples</h4>
                      <div className="flex flex-wrap gap-2">
                        {agent.examples.map((ex, i) => (
                          <span
                            key={i}
                            className="text-xs px-2 py-1 rounded-md bg-secondary text-secondary-foreground"
                          >
                            "{ex}"
                          </span>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Model & Tools */}
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <h4 className="text-sm font-medium mb-2">Model</h4>
                      <p className="text-sm text-muted-foreground">{agent.model}</p>
                    </div>
                    <div>
                      <h4 className="text-sm font-medium mb-2">Max Tokens</h4>
                      <p className="text-sm text-muted-foreground">{agent.maxTokens}</p>
                    </div>
                  </div>

                  {/* Allowed Tools */}
                  {agent.allowedTools.length > 0 && (
                    <div>
                      <h4 className="text-sm font-medium mb-2">Allowed Tools</h4>
                      <div className="flex flex-wrap gap-2">
                        {agent.allowedTools.map((tool) => (
                          <span
                            key={tool}
                            className="text-xs px-2 py-1 rounded-md bg-accent text-accent-foreground border"
                          >
                            {tool}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* System Prompt Preview */}
                  <div>
                    <h4 className="text-sm font-medium mb-2">System Prompt</h4>
                    <pre className="text-xs bg-background p-3 rounded-lg border overflow-auto max-h-40">
                      {agent.systemPrompt.slice(0, 500)}
                      {agent.systemPrompt.length > 500 && '...'}
                    </pre>
                  </div>
                </div>
              )}
            </Card>
          ))}
        </div>
      )}

      {/* Create Agent Modal Placeholder */}
      {showCreateModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <Card className="w-full max-w-lg">
            <CardHeader title="Create New Agent" />
            <div className="p-4">
              <div className="flex items-center gap-3 p-4 rounded-lg bg-yellow-500/10 text-yellow-600 border border-yellow-500/20">
                <AlertCircle className="h-5 w-5 flex-shrink-0" />
                <p className="text-sm">
                  Agent creation modal is coming soon. For now, you can configure agents
                  via the API or configuration files.
                </p>
              </div>
              <div className="flex justify-end mt-4">
                <button
                  onClick={() => setShowCreateModal(false)}
                  className="px-4 py-2 rounded-lg border border-border hover:bg-muted transition-colors"
                >
                  Close
                </button>
              </div>
            </div>
          </Card>
        </div>
      )}
    </div>
  )
}
