import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Workflow,
  Play,
  Pause,
  Clock,
  CheckCircle,
  XCircle,
  ExternalLink,
  RefreshCw,
  Zap,
  Loader2,
  AlertCircle,
} from 'lucide-react'
import { Card, StatCard } from '@/components/Card'
import { cn } from '@/lib/utils'
import { n8nApi, type N8nWorkflow, type N8nExecution, DEFAULT_N8N_URL } from '@/lib/api'

interface WorkflowWithStats extends N8nWorkflow {
  executions: N8nExecution[]
  successCount: number
  errorCount: number
}

// 워크플로우 이름 → 설명 매핑
const WORKFLOW_DESCRIPTIONS: Record<string, string> = {
  'Slack Mention Handler': 'Slack에서 @멘션 메시지를 처리합니다. 일반 질문, 명령어(/health 등), MR 리뷰 요청을 분류하여 적절한 핸들러로 라우팅합니다.',
  'Slack Action Handler': 'Slack 버튼 액션을 처리합니다. Jira 티켓 생성, 대화 요약, MR 선택 등의 인터랙티브 작업을 수행합니다.',
  'Slack Feedback Handler': 'Slack 이모지 리액션(👍/👎)을 수집하여 피드백으로 저장합니다. AI 응답 품질 개선에 활용됩니다.',
  'Scheduled MR Auto Review': '5분마다 GitLab에서 새 MR을 확인하고 자동으로 AI 코드 리뷰를 수행합니다. ai-review 라벨이 있는 MR을 대상으로 합니다.',
  'GitLab Feedback Poller': '5분마다 GitLab MR 코멘트의 이모지 리액션을 수집합니다. AI 리뷰에 대한 개발자 피드백을 추적합니다.',
  'Alert Channel Monitor': '장애 알람 채널을 모니터링하여 Sentry/DataDog 알림을 분석하고, Jira 이슈 생성 및 MR 파이프라인을 트리거합니다.',
  'Alert to MR Pipeline': 'Jira 이슈 기반으로 자동 수정 파이프라인을 실행합니다. 브랜치 생성 → Claude Code 수정 → MR 생성까지 자동화합니다.',
}

export function Workflows() {
  const [selectedWorkflow, setSelectedWorkflow] = useState<string | null>(null)
  const queryClient = useQueryClient()

  // Fetch workflows from n8n API
  const { data: workflows = [], isLoading, error, refetch } = useQuery({
    queryKey: ['n8n-workflows'],
    queryFn: n8nApi.getWorkflows,
    refetchInterval: 30000, // Refresh every 30 seconds
  })

  // Fetch recent executions
  const { data: executions = [] } = useQuery({
    queryKey: ['n8n-executions'],
    queryFn: () => n8nApi.getExecutions(100),
    refetchInterval: 30000,
  })

  // Toggle workflow active state
  const toggleActiveMutation = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) =>
      n8nApi.setActive(id, active),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['n8n-workflows'] })
    },
  })

  // Execute workflow manually
  const executeMutation = useMutation({
    mutationFn: (id: string) => n8nApi.executeWorkflow(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['n8n-executions'] })
    },
  })

  // Calculate stats for each workflow
  const workflowsWithStats: WorkflowWithStats[] = workflows.map(workflow => {
    const workflowExecutions = executions.filter(e => e.workflowId === workflow.id)
    const successCount = workflowExecutions.filter(e => e.status === 'success').length
    const errorCount = workflowExecutions.filter(e => e.status === 'error' || e.status === 'failed').length
    return {
      ...workflow,
      executions: workflowExecutions,
      successCount,
      errorCount,
    }
  })

  const activeWorkflows = workflows.filter(w => w.active).length
  const totalExecutions = executions.length
  const successfulExecutions = executions.filter(e => e.status === 'success').length

  const formatTimeAgo = (isoString: string) => {
    const diff = Date.now() - new Date(isoString).getTime()
    const minutes = Math.floor(diff / 60000)
    const hours = Math.floor(diff / 3600000)
    const days = Math.floor(diff / 86400000)
    if (days > 0) return `${days}d ago`
    if (hours > 0) return `${hours}h ago`
    return `${minutes}m ago`
  }

  const getStatusIcon = (workflow: WorkflowWithStats) => {
    if (!workflow.active) return <Pause className="h-4 w-4 text-gray-500" />
    if (workflow.errorCount > workflow.successCount) return <XCircle className="h-4 w-4 text-red-500" />
    return <CheckCircle className="h-4 w-4 text-green-500" />
  }

  const hasWebhookTrigger = (workflow: N8nWorkflow) => {
    const nodes = workflow.nodes as Array<{ type?: string }> | undefined
    return nodes?.some((node) =>
      node.type === 'n8n-nodes-base.webhook' || node.type?.includes('webhook')
    )
  }

  const hasScheduleTrigger = (workflow: N8nWorkflow) => {
    const nodes = workflow.nodes as Array<{ type?: string }> | undefined
    return nodes?.some((node) =>
      node.type === 'n8n-nodes-base.scheduleTrigger' || node.type === 'n8n-nodes-base.cron'
    )
  }

  const getTriggerBadge = (workflow: N8nWorkflow) => {
    if (hasWebhookTrigger(workflow)) {
      return (
        <span className="flex items-center gap-1 text-xs bg-blue-500/10 text-blue-500 px-2 py-1 rounded-full">
          <Zap className="h-3 w-3" /> Webhook
        </span>
      )
    }
    if (hasScheduleTrigger(workflow)) {
      return (
        <span className="flex items-center gap-1 text-xs bg-purple-500/10 text-purple-500 px-2 py-1 rounded-full">
          <Clock className="h-3 w-3" /> Schedule
        </span>
      )
    }
    return (
      <span className="flex items-center gap-1 text-xs bg-gray-500/10 text-gray-500 px-2 py-1 rounded-full">
        <Play className="h-3 w-3" /> Manual
      </span>
    )
  }

  const getLastExecution = (workflow: WorkflowWithStats) => {
    const sorted = [...workflow.executions].sort(
      (a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime()
    )
    return sorted[0]
  }

  // Loading state
  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  // Error state with fallback message
  if (error) {
    return (
      <div className="space-y-8">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold">Workflows</h1>
            <p className="text-muted-foreground mt-1">
              Manage n8n workflow automations
            </p>
          </div>
          <a
            href={import.meta.env.VITE_N8N_URL || DEFAULT_N8N_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            <ExternalLink className="h-4 w-4" />
            Open n8n
          </a>
        </div>
        <Card className="border-yellow-500/50">
          <div className="flex items-center gap-3 text-yellow-600">
            <AlertCircle className="h-5 w-5" />
            <div>
              <p className="font-medium">n8n API not available</p>
              <p className="text-sm text-muted-foreground">
                Make sure n8n is running and accessible. You can manage workflows directly in the n8n interface.
              </p>
            </div>
          </div>
        </Card>
      </div>
    )
  }

  const n8nDirectUrl = import.meta.env.VITE_N8N_URL || DEFAULT_N8N_URL

  // Open n8n directly
  const openN8nWithAuth = (path = '') => {
    window.open(`${n8nDirectUrl}${path}`, '_blank')
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
        <div className="flex items-center gap-2">
          <button
            onClick={() => refetch()}
            className="flex items-center gap-2 px-3 py-2 rounded-lg bg-muted hover:bg-muted/80 transition-colors"
          >
            <RefreshCw className="h-4 w-4" />
          </button>
          <button
            onClick={() => openN8nWithAuth()}
            className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            <ExternalLink className="h-4 w-4" />
            Open n8n
          </button>
        </div>
      </div>

      {/* n8n Login Info */}
      <Card className="border-blue-500/30 bg-blue-500/5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-blue-500/10">
              <Zap className="h-5 w-5 text-blue-500" />
            </div>
            <div>
              <p className="font-medium">n8n Login</p>
              <p className="text-sm text-muted-foreground">
                See .env for credentials (N8N_DEFAULT_EMAIL / N8N_DEFAULT_PASSWORD)
              </p>
            </div>
          </div>
          <button
            onClick={() => openN8nWithAuth()}
            className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm bg-blue-500 text-white hover:bg-blue-600 transition-colors"
          >
            <ExternalLink className="h-4 w-4" />
            Open n8n
          </button>
        </div>
      </Card>

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
          title="Executions"
          value={totalExecutions}
          icon={<Zap className="h-6 w-6 text-blue-500" />}
        />
        <StatCard
          title="Success Rate"
          value={totalExecutions > 0 ? `${Math.round((successfulExecutions / totalExecutions) * 100)}%` : '-'}
          icon={<CheckCircle className="h-6 w-6 text-green-500" />}
        />
      </div>

      {/* Workflow List */}
      <div className="space-y-4">
        {workflowsWithStats.length === 0 ? (
          <Card>
            <div className="text-center py-8 text-muted-foreground">
              <Workflow className="h-12 w-12 mx-auto mb-4 opacity-50" />
              <p>No workflows found</p>
              <p className="text-sm">Create workflows in n8n to see them here</p>
            </div>
          </Card>
        ) : (
          workflowsWithStats.map((workflow) => {
            const lastExecution = getLastExecution(workflow)
            const successRate = workflow.executions.length > 0
              ? (workflow.successCount / workflow.executions.length)
              : 0

            return (
              <Card
                key={workflow.id}
                className={cn(
                  "cursor-pointer transition-all hover:shadow-md",
                  !workflow.active && "opacity-60"
                )}
                onClick={() => setSelectedWorkflow(selectedWorkflow === workflow.id ? null : workflow.id)}
              >
                <div className="flex items-center gap-4">
                  <div className={cn(
                    "p-3 rounded-lg",
                    workflow.active ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground"
                  )}>
                    <Workflow className="h-5 w-5" />
                  </div>

                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <h3 className="font-semibold">{workflow.name}</h3>
                      {getStatusIcon(workflow)}
                    </div>
                    {WORKFLOW_DESCRIPTIONS[workflow.name] && (
                      <p className="text-sm text-muted-foreground mt-1 line-clamp-1">
                        {WORKFLOW_DESCRIPTIONS[workflow.name]}
                      </p>
                    )}
                    <p className="text-xs text-muted-foreground/70 mt-1">
                      {workflow.nodes?.length || 0} nodes • Updated {formatTimeAgo(workflow.updatedAt)}
                    </p>
                  </div>

                  <div className="flex items-center gap-4">
                    {getTriggerBadge(workflow)}
                    {lastExecution && (
                      <div className="text-right text-sm">
                        <p className="text-muted-foreground">Last run</p>
                        <p className="font-medium">{formatTimeAgo(lastExecution.startedAt)}</p>
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
                        <p className="text-2xl font-bold">{workflow.executions.length.toLocaleString()}</p>
                        <p className="text-xs text-muted-foreground">Executions</p>
                      </div>
                      <div className="text-center p-3 rounded-lg bg-muted/50">
                        <p className={cn(
                          "text-2xl font-bold",
                          successRate >= 0.95 ? "text-green-500" : successRate >= 0.8 ? "text-yellow-500" : "text-red-500"
                        )}>
                          {workflow.executions.length > 0 ? `${Math.round(successRate * 100)}%` : '-'}
                        </p>
                        <p className="text-xs text-muted-foreground">Success Rate</p>
                      </div>
                      <div className="text-center p-3 rounded-lg bg-muted/50">
                        <p className="text-2xl font-bold text-green-500">{workflow.successCount}</p>
                        <p className="text-xs text-muted-foreground">Successful</p>
                      </div>
                      <div className="text-center p-3 rounded-lg bg-muted/50">
                        <p className="text-2xl font-bold text-red-500">{workflow.errorCount}</p>
                        <p className="text-xs text-muted-foreground">Failed</p>
                      </div>
                    </div>

                    {/* Actions */}
                    <div className="flex items-center gap-2">
                      <button
                        onClick={(e) => {
                          e.stopPropagation()
                          toggleActiveMutation.mutate({ id: workflow.id, active: !workflow.active })
                        }}
                        disabled={toggleActiveMutation.isPending}
                        className={cn(
                          "flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors",
                          workflow.active
                            ? "bg-yellow-500/10 text-yellow-500 hover:bg-yellow-500/20"
                            : "bg-green-500/10 text-green-500 hover:bg-green-500/20"
                        )}
                      >
                        {toggleActiveMutation.isPending ? (
                          <Loader2 className="h-4 w-4 animate-spin" />
                        ) : workflow.active ? (
                          <Pause className="h-4 w-4" />
                        ) : (
                          <Play className="h-4 w-4" />
                        )}
                        {workflow.active ? 'Deactivate' : 'Activate'}
                      </button>
                      <button
                        onClick={(e) => {
                          e.stopPropagation()
                          executeMutation.mutate(workflow.id)
                        }}
                        disabled={executeMutation.isPending || !workflow.active}
                        className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium bg-muted hover:bg-muted/80 transition-colors disabled:opacity-50"
                      >
                        {executeMutation.isPending ? (
                          <Loader2 className="h-4 w-4 animate-spin" />
                        ) : (
                          <RefreshCw className="h-4 w-4" />
                        )}
                        Run Now
                      </button>
                      <button
                        onClick={(e) => {
                          e.stopPropagation()
                          openN8nWithAuth(`/workflow/${workflow.id}`)
                        }}
                        className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium bg-muted hover:bg-muted/80 transition-colors"
                      >
                        <ExternalLink className="h-4 w-4" />
                        Edit in n8n
                      </button>
                    </div>
                  </div>
                )}
              </Card>
            )
          })
        )}
      </div>
    </div>
  )
}
