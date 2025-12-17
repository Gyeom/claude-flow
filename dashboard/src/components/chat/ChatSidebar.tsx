import { useQuery } from '@tanstack/react-query'
import { Bot, FolderOpen, Settings2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { agentsApi, projectsApi } from '@/lib/api'

interface ChatSidebarProps {
  selectedProject: string | null
  onProjectChange: (projectId: string | null) => void
  selectedAgent: string | null
  onAgentChange: (agentId: string | null) => void
}

export function ChatSidebar({
  selectedProject,
  onProjectChange,
  selectedAgent,
  onAgentChange
}: ChatSidebarProps) {
  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: projectsApi.getAll,
  })

  const { data: agents } = useQuery({
    queryKey: ['agents'],
    queryFn: agentsApi.getAll,
  })

  const enabledAgents = agents?.filter(a => a.enabled) ?? []

  return (
    <aside className="w-64 border-r border-border bg-muted/30 flex flex-col">
      {/* 헤더 */}
      <div className="p-4 border-b border-border">
        <h2 className="font-semibold text-lg flex items-center gap-2">
          <Bot className="h-5 w-5" />
          Chat Settings
        </h2>
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-6">
        {/* 프로젝트 선택 */}
        <div className="space-y-2">
          <label className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
            <FolderOpen className="h-4 w-4" />
            Project
          </label>
          <select
            value={selectedProject || ''}
            onChange={(e) => onProjectChange(e.target.value || null)}
            className={cn(
              'w-full rounded-lg border border-border bg-background px-3 py-2',
              'text-sm focus:outline-none focus:ring-2 focus:ring-primary/50'
            )}
          >
            <option value="">Auto-detect</option>
            {projects?.map((project) => (
              <option key={project.id} value={project.id}>
                {project.name}
              </option>
            ))}
          </select>
          <p className="text-xs text-muted-foreground">
            프로젝트 컨텍스트와 작업 디렉토리를 설정합니다.
          </p>
        </div>

        {/* 에이전트 선택 */}
        <div className="space-y-2">
          <label className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
            <Settings2 className="h-4 w-4" />
            Agent
          </label>
          <select
            value={selectedAgent || ''}
            onChange={(e) => onAgentChange(e.target.value || null)}
            className={cn(
              'w-full rounded-lg border border-border bg-background px-3 py-2',
              'text-sm focus:outline-none focus:ring-2 focus:ring-primary/50'
            )}
          >
            <option value="">Auto-route (권장)</option>
            {enabledAgents.map((agent) => (
              <option key={agent.id} value={agent.id}>
                {agent.name}
              </option>
            ))}
          </select>
          <p className="text-xs text-muted-foreground">
            메시지에 따라 자동으로 적합한 에이전트가 선택됩니다.
          </p>
        </div>

        {/* 에이전트 목록 */}
        <div className="space-y-2">
          <h3 className="text-sm font-medium text-muted-foreground">Available Agents</h3>
          <div className="space-y-1">
            {enabledAgents.map((agent) => (
              <button
                key={agent.id}
                onClick={() => onAgentChange(agent.id)}
                className={cn(
                  'w-full text-left px-3 py-2 rounded-lg text-sm transition-colors',
                  selectedAgent === agent.id
                    ? 'bg-primary/10 text-primary'
                    : 'hover:bg-muted'
                )}
              >
                <div className="font-medium">{agent.name}</div>
                <div className="text-xs text-muted-foreground truncate">
                  {agent.description}
                </div>
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* 푸터 */}
      <div className="p-4 border-t border-border">
        <div className="text-xs text-muted-foreground">
          <p>Slack 없이 Claude Flow의 모든 기능을 사용할 수 있습니다.</p>
        </div>
      </div>
    </aside>
  )
}
