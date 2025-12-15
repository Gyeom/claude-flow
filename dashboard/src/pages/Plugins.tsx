import { useState } from 'react'
import {
  Puzzle,
  Power,
  Settings,
  ExternalLink,
  CheckCircle,
  XCircle,
  AlertTriangle,
  Terminal,
  Search,
  GitBranch,
  MessageSquare,
} from 'lucide-react'
import { Card, StatCard } from '@/components/Card'
import { cn } from '@/lib/utils'

interface Plugin {
  id: string
  name: string
  description: string
  version: string
  enabled: boolean
  status: 'active' | 'error' | 'disabled'
  icon: React.ReactNode
  config: Record<string, string>
  stats?: {
    invocations: number
    successRate: number
    avgLatencyMs: number
  }
}

export function Plugins() {
  const [selectedPlugin, setSelectedPlugin] = useState<string | null>(null)

  // Mock plugins for demo
  const plugins: Plugin[] = [
    {
      id: 'slack-cli',
      name: 'Slack CLI',
      description: 'Execute slash commands and interact with Slack API',
      version: '1.2.0',
      enabled: true,
      status: 'active',
      icon: <MessageSquare className="h-6 w-6" />,
      config: {
        webhook_url: 'https://hooks.slack.com/...',
        bot_token: 'xoxb-***',
        signing_secret: '***',
      },
      stats: {
        invocations: 1250,
        successRate: 0.98,
        avgLatencyMs: 120,
      },
    },
    {
      id: 'ssearch',
      name: 'Semantic Search',
      description: 'Embedding-based semantic search for agent routing',
      version: '2.0.1',
      enabled: true,
      status: 'active',
      icon: <Search className="h-6 w-6" />,
      config: {
        embedding_model: 'text-embedding-3-small',
        min_score: '0.7',
        top_k: '5',
      },
      stats: {
        invocations: 3400,
        successRate: 0.95,
        avgLatencyMs: 45,
      },
    },
    {
      id: 'glab',
      name: 'GitLab Integration',
      description: 'GitLab MR review and repository management',
      version: '1.0.0',
      enabled: true,
      status: 'active',
      icon: <GitBranch className="h-6 w-6" />,
      config: {
        gitlab_url: 'https://gitlab.com',
        private_token: 'glpat-***',
      },
      stats: {
        invocations: 89,
        successRate: 0.92,
        avgLatencyMs: 2500,
      },
    },
    {
      id: 'jira',
      name: 'JIRA Integration',
      description: 'JIRA issue management and auto-fix',
      version: '0.9.0',
      enabled: false,
      status: 'disabled',
      icon: <AlertTriangle className="h-6 w-6" />,
      config: {
        jira_url: '',
        auth_token: '',
      },
    },
  ]

  const activePlugins = plugins.filter(p => p.status === 'active').length
  const totalInvocations = plugins.reduce((sum, p) => sum + (p.stats?.invocations || 0), 0)

  const getStatusColor = (status: Plugin['status']) => {
    switch (status) {
      case 'active': return 'bg-green-500'
      case 'error': return 'bg-red-500'
      default: return 'bg-gray-500'
    }
  }

  const getStatusBadge = (status: Plugin['status']) => {
    switch (status) {
      case 'active':
        return (
          <span className="flex items-center gap-1 text-xs text-green-500 bg-green-500/10 px-2 py-1 rounded-full">
            <CheckCircle className="h-3 w-3" /> Active
          </span>
        )
      case 'error':
        return (
          <span className="flex items-center gap-1 text-xs text-red-500 bg-red-500/10 px-2 py-1 rounded-full">
            <XCircle className="h-3 w-3" /> Error
          </span>
        )
      default:
        return (
          <span className="flex items-center gap-1 text-xs text-gray-500 bg-gray-500/10 px-2 py-1 rounded-full">
            <Power className="h-3 w-3" /> Disabled
          </span>
        )
    }
  }

  return (
    <div className="space-y-8">
      {/* Header */}
      <div>
        <h1 className="text-3xl font-bold">Plugins</h1>
        <p className="text-muted-foreground mt-1">
          Manage integrations and extensions
        </p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <StatCard
          title="Total Plugins"
          value={plugins.length}
          icon={<Puzzle className="h-6 w-6" />}
        />
        <StatCard
          title="Active"
          value={activePlugins}
          icon={<CheckCircle className="h-6 w-6 text-green-500" />}
          className="border-green-500/30"
        />
        <StatCard
          title="Total Invocations"
          value={totalInvocations.toLocaleString()}
          icon={<Terminal className="h-6 w-6" />}
        />
        <StatCard
          title="Disabled"
          value={plugins.filter(p => !p.enabled).length}
          icon={<Power className="h-6 w-6 text-gray-500" />}
        />
      </div>

      {/* Plugin Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {plugins.map((plugin) => (
          <Card
            key={plugin.id}
            className={cn(
              "cursor-pointer transition-all hover:shadow-md",
              selectedPlugin === plugin.id && "ring-2 ring-primary"
            )}
            onClick={() => setSelectedPlugin(selectedPlugin === plugin.id ? null : plugin.id)}
          >
            <div className="flex items-start gap-4">
              <div className={cn(
                "p-3 rounded-lg",
                plugin.enabled ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground"
              )}>
                {plugin.icon}
              </div>
              <div className="flex-1">
                <div className="flex items-center justify-between">
                  <h3 className="font-semibold">{plugin.name}</h3>
                  {getStatusBadge(plugin.status)}
                </div>
                <p className="text-sm text-muted-foreground mt-1">{plugin.description}</p>
                <div className="flex items-center gap-4 mt-3 text-xs text-muted-foreground">
                  <span>v{plugin.version}</span>
                  <span className={cn("w-2 h-2 rounded-full", getStatusColor(plugin.status))} />
                </div>
              </div>
            </div>

            {/* Expanded Details */}
            {selectedPlugin === plugin.id && (
              <div className="mt-6 pt-6 border-t border-border space-y-4">
                {/* Stats */}
                {plugin.stats && (
                  <div className="grid grid-cols-3 gap-4">
                    <div className="text-center p-3 rounded-lg bg-muted/50">
                      <p className="text-2xl font-bold">{plugin.stats.invocations.toLocaleString()}</p>
                      <p className="text-xs text-muted-foreground">Invocations</p>
                    </div>
                    <div className="text-center p-3 rounded-lg bg-muted/50">
                      <p className="text-2xl font-bold text-green-500">
                        {(plugin.stats.successRate * 100).toFixed(0)}%
                      </p>
                      <p className="text-xs text-muted-foreground">Success Rate</p>
                    </div>
                    <div className="text-center p-3 rounded-lg bg-muted/50">
                      <p className="text-2xl font-bold">{plugin.stats.avgLatencyMs}ms</p>
                      <p className="text-xs text-muted-foreground">Avg Latency</p>
                    </div>
                  </div>
                )}

                {/* Configuration */}
                <div>
                  <h4 className="text-sm font-medium mb-2 flex items-center gap-2">
                    <Settings className="h-4 w-4" />
                    Configuration
                  </h4>
                  <div className="space-y-2">
                    {Object.entries(plugin.config).map(([key, value]) => (
                      <div
                        key={key}
                        className="flex items-center justify-between p-2 rounded bg-muted/50 text-sm"
                      >
                        <span className="text-muted-foreground">{key}</span>
                        <code className="font-mono text-xs">
                          {value.includes('***') ? value : value.slice(0, 20) + (value.length > 20 ? '...' : '')}
                        </code>
                      </div>
                    ))}
                  </div>
                </div>

                {/* Actions */}
                <div className="flex items-center gap-2">
                  <button
                    className={cn(
                      "flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors",
                      plugin.enabled
                        ? "bg-red-500/10 text-red-500 hover:bg-red-500/20"
                        : "bg-green-500/10 text-green-500 hover:bg-green-500/20"
                    )}
                  >
                    <Power className="h-4 w-4" />
                    {plugin.enabled ? 'Disable' : 'Enable'}
                  </button>
                  <button className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium bg-muted hover:bg-muted/80 transition-colors">
                    <Settings className="h-4 w-4" />
                    Configure
                  </button>
                  <button className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium bg-muted hover:bg-muted/80 transition-colors">
                    <ExternalLink className="h-4 w-4" />
                    Docs
                  </button>
                </div>
              </div>
            )}
          </Card>
        ))}
      </div>

      {/* Add Plugin */}
      <Card className="border-dashed">
        <div className="flex flex-col items-center justify-center py-8 text-center">
          <div className="p-4 rounded-full bg-muted mb-4">
            <Puzzle className="h-8 w-8 text-muted-foreground" />
          </div>
          <h3 className="font-semibold mb-1">Add New Plugin</h3>
          <p className="text-sm text-muted-foreground mb-4">
            Extend Claude Flow with custom integrations
          </p>
          <button className="px-4 py-2 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors">
            Browse Plugins
          </button>
        </div>
      </Card>
    </div>
  )
}
