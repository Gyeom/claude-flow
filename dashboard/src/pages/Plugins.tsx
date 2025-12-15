import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Puzzle,
  Power,
  Settings,
  ExternalLink,
  CheckCircle,
  Terminal,
  Search,
  GitBranch,
  MessageSquare,
  RefreshCw,
  AlertCircle,
} from 'lucide-react'
import { toast } from 'sonner'
import { Card, StatCard } from '@/components/Card'
import { pluginsApi } from '@/lib/api'
import { cn } from '@/lib/utils'

// Icon mapping for known plugins
const PLUGIN_ICONS: Record<string, React.ReactNode> = {
  'slack': <MessageSquare className="h-6 w-6" />,
  'slack-cli': <MessageSquare className="h-6 w-6" />,
  'gitlab': <GitBranch className="h-6 w-6" />,
  'glab': <GitBranch className="h-6 w-6" />,
  'jira': <Terminal className="h-6 w-6" />,
  'ssearch': <Search className="h-6 w-6" />,
  'semantic-search': <Search className="h-6 w-6" />,
}

export function Plugins() {
  const [selectedPlugin, setSelectedPlugin] = useState<string | null>(null)
  const queryClient = useQueryClient()

  // Fetch plugins from API
  const { data: plugins, isLoading, error } = useQuery({
    queryKey: ['plugins'],
    queryFn: pluginsApi.getAll,
  })

  // Fetch selected plugin details
  const { data: pluginDetail } = useQuery({
    queryKey: ['plugin', selectedPlugin],
    queryFn: () => selectedPlugin ? pluginsApi.getById(selectedPlugin) : null,
    enabled: !!selectedPlugin,
  })

  // Toggle plugin enabled/disabled
  const toggleEnabledMutation = useMutation({
    mutationFn: ({ pluginId, enabled }: { pluginId: string; enabled: boolean }) =>
      pluginsApi.setEnabled(pluginId, enabled),
    onSuccess: (_, { enabled }) => {
      queryClient.invalidateQueries({ queryKey: ['plugins'] })
      toast.success(`Plugin ${enabled ? 'enabled' : 'disabled'}`)
    },
    onError: () => {
      toast.error('Failed to update plugin status')
    },
  })

  const activePlugins = plugins?.filter(p => p.enabled).length ?? 0
  const totalPlugins = plugins?.length ?? 0

  const getPluginIcon = (pluginId: string) => {
    return PLUGIN_ICONS[pluginId] || <Puzzle className="h-6 w-6" />
  }

  const getStatusBadge = (enabled: boolean) => {
    if (enabled) {
      return (
        <span className="flex items-center gap-1 text-xs text-green-500 bg-green-500/10 px-2 py-1 rounded-full">
          <CheckCircle className="h-3 w-3" /> Active
        </span>
      )
    }
    return (
      <span className="flex items-center gap-1 text-xs text-gray-500 bg-gray-500/10 px-2 py-1 rounded-full">
        <Power className="h-3 w-3" /> Disabled
      </span>
    )
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-4">
        <AlertCircle className="h-12 w-12 text-destructive" />
        <p className="text-muted-foreground">Failed to load plugins</p>
        <button
          onClick={() => queryClient.invalidateQueries({ queryKey: ['plugins'] })}
          className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground"
        >
          <RefreshCw className="h-4 w-4" />
          Retry
        </button>
      </div>
    )
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
          value={totalPlugins}
          icon={<Puzzle className="h-6 w-6" />}
        />
        <StatCard
          title="Active"
          value={activePlugins}
          icon={<CheckCircle className="h-6 w-6 text-green-500" />}
          className="border-green-500/30"
        />
        <StatCard
          title="Disabled"
          value={totalPlugins - activePlugins}
          icon={<Power className="h-6 w-6 text-gray-500" />}
        />
        <StatCard
          title="Commands"
          value={plugins?.reduce((sum, p) => sum + p.commands.length, 0) ?? 0}
          icon={<Terminal className="h-6 w-6" />}
        />
      </div>

      {/* Plugin Grid */}
      {isLoading ? (
        <div className="flex items-center justify-center h-64">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
        </div>
      ) : plugins && plugins.length > 0 ? (
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
                  {getPluginIcon(plugin.id)}
                </div>
                <div className="flex-1">
                  <div className="flex items-center justify-between">
                    <h3 className="font-semibold">{plugin.name}</h3>
                    {getStatusBadge(plugin.enabled)}
                  </div>
                  <p className="text-sm text-muted-foreground mt-1">{plugin.description}</p>
                  <div className="flex items-center gap-4 mt-3 text-xs text-muted-foreground">
                    <span>{plugin.commands.length} commands</span>
                    <span className={cn(
                      "w-2 h-2 rounded-full",
                      plugin.enabled ? "bg-green-500" : "bg-gray-500"
                    )} />
                  </div>
                </div>
              </div>

              {/* Expanded Details */}
              {selectedPlugin === plugin.id && pluginDetail && (
                <div className="mt-6 pt-6 border-t border-border space-y-4">
                  {/* Commands */}
                  <div>
                    <h4 className="text-sm font-medium mb-2 flex items-center gap-2">
                      <Terminal className="h-4 w-4" />
                      Available Commands
                    </h4>
                    <div className="space-y-2">
                      {pluginDetail.commands.map((cmd) => (
                        <div
                          key={cmd.name}
                          className="p-3 rounded-lg bg-muted/50 space-y-1"
                        >
                          <div className="flex items-center justify-between">
                            <code className="font-mono text-sm font-medium">{cmd.name}</code>
                          </div>
                          <p className="text-xs text-muted-foreground">{cmd.description}</p>
                          {cmd.usage && (
                            <p className="text-xs font-mono text-muted-foreground mt-1">
                              Usage: {cmd.usage}
                            </p>
                          )}
                        </div>
                      ))}
                    </div>
                  </div>

                  {/* Actions */}
                  <div className="flex items-center gap-2">
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        toggleEnabledMutation.mutate({
                          pluginId: plugin.id,
                          enabled: !plugin.enabled
                        })
                      }}
                      disabled={toggleEnabledMutation.isPending}
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
      ) : (
        <Card className="border-dashed">
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <div className="p-4 rounded-full bg-muted mb-4">
              <Puzzle className="h-8 w-8 text-muted-foreground" />
            </div>
            <h3 className="font-semibold mb-1">No Plugins Found</h3>
            <p className="text-sm text-muted-foreground mb-4">
              No plugins are currently registered
            </p>
          </div>
        </Card>
      )}

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
