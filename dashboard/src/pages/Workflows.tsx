import React, { useState } from 'react'
import {
  Workflow,
  Play,
  Pause,
  Clock,
  CheckCircle,
  XCircle,
  AlertTriangle,
  ExternalLink,
  RefreshCw,
  Calendar,
  Zap,
  GitBranch,
  MessageSquare,
  FileText,
} from 'lucide-react'
import { Card, StatCard } from '@/components/Card'
import { cn } from '@/lib/utils'

interface WorkflowConfig {
  id: string
  name: string
  description: string
  trigger: 'webhook' | 'schedule' | 'manual'
  schedule?: string
  enabled: boolean
  status: 'active' | 'error' | 'paused'
  icon: React.ReactNode
  lastRun?: string
  nextRun?: string
  stats: {
    executions: number
    successRate: number
    avgDurationMs: number
  }
}

export function Workflows() {
  const [selectedWorkflow, setSelectedWorkflow] = useState<string | null>(null)

  // Mock workflows based on actual n8n workflows
  const workflows: WorkflowConfig[] = [
    {
      id: 'slack-mention-handler',
      name: 'Slack Mention Handler',
      description: 'Handle @claude mentions in Slack channels',
      trigger: 'webhook',
      enabled: true,
      status: 'active',
      icon: <MessageSquare className="h-5 w-5" />,
      lastRun: new Date(Date.now() - 300000).toISOString(),
      stats: {
        executions: 1250,
        successRate: 0.98,
        avgDurationMs: 3200,
      },
    },
    {
      id: 'gitlab-mr-review',
      name: 'GitLab MR Review',
      description: 'Webhook-triggered MR code review',
      trigger: 'webhook',
      enabled: true,
      status: 'active',
      icon: <GitBranch className="h-5 w-5" />,
      lastRun: new Date(Date.now() - 1800000).toISOString(),
      stats: {
        executions: 89,
        successRate: 0.95,
        avgDurationMs: 45000,
      },
    },
    {
      id: 'gitlab-mr-auto-review',
      name: 'GitLab MR Auto Review',
      description: 'Scheduled auto-review for MRs with ai:review label',
      trigger: 'schedule',
      schedule: '*/5 * * * *',
      enabled: true,
      status: 'active',
      icon: <GitBranch className="h-5 w-5" />,
      lastRun: new Date(Date.now() - 180000).toISOString(),
      nextRun: new Date(Date.now() + 120000).toISOString(),
      stats: {
        executions: 456,
        successRate: 0.92,
        avgDurationMs: 55000,
      },
    },
    {
      id: 'jira-auto-fix',
      name: 'JIRA Auto Fix Scheduler',
      description: 'Auto-analyze JIRA issues with ai:auto-fix label',
      trigger: 'schedule',
      schedule: '*/10 * * * *',
      enabled: true,
      status: 'active',
      icon: <AlertTriangle className="h-5 w-5" />,
      lastRun: new Date(Date.now() - 420000).toISOString(),
      nextRun: new Date(Date.now() + 180000).toISOString(),
      stats: {
        executions: 78,
        successRate: 0.88,
        avgDurationMs: 120000,
      },
    },
    {
      id: 'stale-cleanup',
      name: 'Stale Cleanup Scheduler',
      description: 'Daily report generation and old data cleanup',
      trigger: 'schedule',
      schedule: '0 3 * * *',
      enabled: true,
      status: 'active',
      icon: <FileText className="h-5 w-5" />,
      lastRun: new Date(Date.now() - 43200000).toISOString(),
      nextRun: new Date(Date.now() + 43200000).toISOString(),
      stats: {
        executions: 30,
        successRate: 1.0,
        avgDurationMs: 5000,
      },
    },
    {
      id: 'slack-feedback-handler',
      name: 'Slack Feedback Handler',
      description: 'Collect thumbsup/thumbsdown reactions',
      trigger: 'webhook',
      enabled: true,
      status: 'active',
      icon: <MessageSquare className="h-5 w-5" />,
      lastRun: new Date(Date.now() - 600000).toISOString(),
      stats: {
        executions: 890,
        successRate: 0.99,
        avgDurationMs: 500,
      },
    },
    {
      id: 'user-context-handler',
      name: 'User Context Handler',
      description: 'Manage user rules and context updates',
      trigger: 'webhook',
      enabled: true,
      status: 'active',
      icon: <Zap className="h-5 w-5" />,
      lastRun: new Date(Date.now() - 900000).toISOString(),
      stats: {
        executions: 234,
        successRate: 0.97,
        avgDurationMs: 800,
      },
    },
    {
      id: 'daily-report',
      name: 'Daily Report',
      description: 'Generate daily scrum report',
      trigger: 'schedule',
      schedule: '0 9 * * 1-5',
      enabled: false,
      status: 'paused',
      icon: <Calendar className="h-5 w-5" />,
      lastRun: new Date(Date.now() - 86400000).toISOString(),
      stats: {
        executions: 20,
        successRate: 0.95,
        avgDurationMs: 15000,
      },
    },
  ]

  const activeWorkflows = workflows.filter(w => w.enabled).length
  const webhookWorkflows = workflows.filter(w => w.trigger === 'webhook').length
  const scheduledWorkflows = workflows.filter(w => w.trigger === 'schedule').length

  const formatTimeAgo = (isoString: string) => {
    const diff = Date.now() - new Date(isoString).getTime()
    const minutes = Math.floor(diff / 60000)
    const hours = Math.floor(diff / 3600000)
    if (hours > 0) return `${hours}h ago`
    return `${minutes}m ago`
  }

  const formatTimeUntil = (isoString: string) => {
    const diff = new Date(isoString).getTime() - Date.now()
    const minutes = Math.floor(diff / 60000)
    if (minutes < 0) return 'overdue'
    return `in ${minutes}m`
  }

  const getStatusIcon = (status: WorkflowConfig['status']) => {
    switch (status) {
      case 'active': return <CheckCircle className="h-4 w-4 text-green-500" />
      case 'error': return <XCircle className="h-4 w-4 text-red-500" />
      default: return <Pause className="h-4 w-4 text-gray-500" />
    }
  }

  const getTriggerBadge = (trigger: WorkflowConfig['trigger'], schedule?: string) => {
    switch (trigger) {
      case 'webhook':
        return (
          <span className="flex items-center gap-1 text-xs bg-blue-500/10 text-blue-500 px-2 py-1 rounded-full">
            <Zap className="h-3 w-3" /> Webhook
          </span>
        )
      case 'schedule':
        return (
          <span className="flex items-center gap-1 text-xs bg-purple-500/10 text-purple-500 px-2 py-1 rounded-full">
            <Clock className="h-3 w-3" /> {schedule}
          </span>
        )
      default:
        return (
          <span className="flex items-center gap-1 text-xs bg-gray-500/10 text-gray-500 px-2 py-1 rounded-full">
            <Play className="h-3 w-3" /> Manual
          </span>
        )
    }
  }

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Workflows</h1>
          <p className="text-muted-foreground mt-1">
            Manage n8n workflow automations
          </p>
        </div>
        <a
          href="http://localhost:5678"
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
        >
          <ExternalLink className="h-4 w-4" />
          Open n8n
        </a>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <StatCard
          title="Total Workflows"
          value={workflows.length}
          icon={<Workflow className="h-6 w-6" />}
        />
        <StatCard
          title="Active"
          value={activeWorkflows}
          icon={<Play className="h-6 w-6 text-green-500" />}
          className="border-green-500/30"
        />
        <StatCard
          title="Webhook Triggers"
          value={webhookWorkflows}
          icon={<Zap className="h-6 w-6 text-blue-500" />}
        />
        <StatCard
          title="Scheduled"
          value={scheduledWorkflows}
          icon={<Clock className="h-6 w-6 text-purple-500" />}
        />
      </div>

      {/* Workflow List */}
      <div className="space-y-4">
        {workflows.map((workflow) => (
          <Card
            key={workflow.id}
            className={cn(
              "cursor-pointer transition-all hover:shadow-md",
              !workflow.enabled && "opacity-60"
            )}
            onClick={() => setSelectedWorkflow(selectedWorkflow === workflow.id ? null : workflow.id)}
          >
            <div className="flex items-center gap-4">
              <div className={cn(
                "p-3 rounded-lg",
                workflow.enabled ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground"
              )}>
                {workflow.icon}
              </div>

              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <h3 className="font-semibold">{workflow.name}</h3>
                  {getStatusIcon(workflow.status)}
                </div>
                <p className="text-sm text-muted-foreground mt-1">{workflow.description}</p>
              </div>

              <div className="flex items-center gap-4">
                {getTriggerBadge(workflow.trigger, workflow.schedule)}
                {workflow.lastRun && (
                  <div className="text-right text-sm">
                    <p className="text-muted-foreground">Last run</p>
                    <p className="font-medium">{formatTimeAgo(workflow.lastRun)}</p>
                  </div>
                )}
              </div>
            </div>

            {/* Expanded Details */}
            {selectedWorkflow === workflow.id && (
              <div className="mt-6 pt-6 border-t border-border space-y-4">
                {/* Stats */}
                <div className="grid grid-cols-4 gap-4">
                  <div className="text-center p-3 rounded-lg bg-muted/50">
                    <p className="text-2xl font-bold">{workflow.stats.executions.toLocaleString()}</p>
                    <p className="text-xs text-muted-foreground">Executions</p>
                  </div>
                  <div className="text-center p-3 rounded-lg bg-muted/50">
                    <p className={cn(
                      "text-2xl font-bold",
                      workflow.stats.successRate >= 0.95 ? "text-green-500" : "text-yellow-500"
                    )}>
                      {(workflow.stats.successRate * 100).toFixed(0)}%
                    </p>
                    <p className="text-xs text-muted-foreground">Success Rate</p>
                  </div>
                  <div className="text-center p-3 rounded-lg bg-muted/50">
                    <p className="text-2xl font-bold">{(workflow.stats.avgDurationMs / 1000).toFixed(1)}s</p>
                    <p className="text-xs text-muted-foreground">Avg Duration</p>
                  </div>
                  {workflow.nextRun && (
                    <div className="text-center p-3 rounded-lg bg-muted/50">
                      <p className="text-2xl font-bold">{formatTimeUntil(workflow.nextRun)}</p>
                      <p className="text-xs text-muted-foreground">Next Run</p>
                    </div>
                  )}
                </div>

                {/* Actions */}
                <div className="flex items-center gap-2">
                  <button
                    className={cn(
                      "flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors",
                      workflow.enabled
                        ? "bg-yellow-500/10 text-yellow-500 hover:bg-yellow-500/20"
                        : "bg-green-500/10 text-green-500 hover:bg-green-500/20"
                    )}
                  >
                    {workflow.enabled ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4" />}
                    {workflow.enabled ? 'Pause' : 'Enable'}
                  </button>
                  <button className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium bg-muted hover:bg-muted/80 transition-colors">
                    <RefreshCw className="h-4 w-4" />
                    Run Now
                  </button>
                  <button className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium bg-muted hover:bg-muted/80 transition-colors">
                    <ExternalLink className="h-4 w-4" />
                    Edit in n8n
                  </button>
                </div>
              </div>
            )}
          </Card>
        ))}
      </div>
    </div>
  )
}
