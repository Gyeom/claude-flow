// Dashboard Types

export interface DashboardStats {
  totalExecutions: number
  successRate: number
  totalTokens: number
  avgDurationMs: number
  thumbsUp: number
  thumbsDown: number
  topUsers: UserStat[]
  topAgents: AgentStat[]
  hourlyTrend: HourlyTrend[]
  satisfactionScore: number
}

export interface UserStat {
  userId: string
  displayName: string | null
  totalInteractions: number
  lastSeen: string
}

export interface AgentStat {
  agentId: string
  agentName: string
  totalExecutions: number
  successRate: number
  avgDurationMs: number
  avgTokens: number
  priority: number
}

export interface HourlyTrend {
  hour: number
  count: number
}

export interface ExecutionRecord {
  id: string
  prompt: string
  result: string | null
  status: 'SUCCESS' | 'ERROR' | 'RUNNING' | 'PENDING'
  agentId: string
  projectId: string | null
  userId: string | null
  channel: string | null
  threadTs: string | null
  replyTs: string | null
  durationMs: number
  inputTokens: number
  outputTokens: number
  cost: number | null
  error: string | null
  createdAt: string
}

export interface Agent {
  id: string
  name: string
  description: string
  keywords: string[]
  systemPrompt: string
  model: string
  maxTokens: number
  allowedTools: string[]
  workingDirectory: string | null
  enabled: boolean
  priority: number
  examples: string[]
  projectId: string | null
}

export interface FeedbackAnalysis {
  totalFeedback: number
  positiveCount: number
  negativeCount: number
  positiveRate: number
  negativeRate: number
  satisfactionScore: number
}

export interface TokenUsage {
  totalTokens: number
  inputTokens: number
  outputTokens: number
  estimatedCost: number
  avgTokensPerRequest: number
}

export interface RoutingEfficiency {
  keywordMatchRate: number
  semanticMatchRate: number
  llmFallbackRate: number
  defaultFallbackRate: number
  avgRoutingTimeMs: number
}

export interface ProjectStat {
  projectId: string
  agentCount: number
  totalExecutions: number
  avgDurationMs: number
}
