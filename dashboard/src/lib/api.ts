import type {
  DashboardStats,
  ExecutionRecord,
  Agent,
  FeedbackAnalysis,
  TokenUsage,
  ProjectStat,
  UserContext,
  UserContextResponse,
  TimeSeriesData,
  ModelStats,
  SourceStats,
  RoutingStats,
  RequesterStats,
  ErrorStats,
} from '@/types'

const API_BASE_V1 = '/api/v1'
const API_BASE_V2 = '/api/v2'

async function fetchApi<T>(endpoint: string, options?: RequestInit, base = API_BASE_V1): Promise<T> {
  const response = await fetch(`${base}${endpoint}`, {
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
    ...options,
  })

  if (!response.ok) {
    throw new Error(`API Error: ${response.status} ${response.statusText}`)
  }

  return response.json()
}

// Dashboard API response type (from backend)
interface DashboardApiResponse {
  totalExecutions: number
  successRate: number
  totalTokens: number
  avgDurationMs: number
  thumbsUp: number
  thumbsDown: number
  topUsers: { userId: string; displayName?: string; totalInteractions: number; successRate?: number }[]
  topAgents: { agentId: string; agentName: string; totalExecutions: number; successRate: number; avgDurationMs: number; avgTokens: number; priority: number }[]
  hourlyTrend: { hour: number; count: number }[]
  satisfactionScore: number
}

// Overview API response type (실제 백분위수 포함)
interface OverviewApiResponse {
  totalRequests: number
  successfulRequests: number
  failedRequests: number
  successRate: number
  totalCostUsd: number
  totalInputTokens: number
  totalOutputTokens: number
  cacheHitRate: number
  percentiles: { p50: number; p90: number; p95: number; p99: number }
  feedback: { positive: number; negative: number; satisfactionRate: number; pendingFeedback: number }
  comparison: { requestsChange: number; successRateChange: number } | null
}

// Transform backend response to frontend expected format
function transformDashboardStats(
  data: DashboardApiResponse,
  overview: OverviewApiResponse | null
): DashboardStats {
  // 실제 백분위수 사용 (없으면 avgDurationMs 기반 추정)
  const percentiles = overview?.percentiles || {
    p50: data.avgDurationMs * 0.8,
    p90: data.avgDurationMs * 1.2,
    p95: data.avgDurationMs * 1.5,
    p99: data.avgDurationMs * 2,
  }

  return {
    period: '7d',
    overview: {
      totalRequests: data.totalExecutions,
      successful: overview?.successfulRequests ?? Math.round(data.totalExecutions * data.successRate),
      failed: overview?.failedRequests ?? Math.round(data.totalExecutions * (1 - data.successRate)),
      successRate: data.successRate,
      avgDurationMs: data.avgDurationMs,
      p50DurationMs: percentiles.p50,
      p90DurationMs: percentiles.p90,
      p95DurationMs: percentiles.p95,
      p99DurationMs: percentiles.p99,
      totalCostUsd: overview?.totalCostUsd ?? data.totalTokens * 0.00001,
      totalInputTokens: overview?.totalInputTokens ?? Math.round(data.totalTokens * 0.4),
      totalOutputTokens: overview?.totalOutputTokens ?? Math.round(data.totalTokens * 0.6),
    },
    timeseries: data.hourlyTrend.map((t, idx) => ({
      timestamp: new Date(Date.now() - (23 - idx) * 3600000).toISOString(),
      requests: t.count,
      successful: Math.round(t.count * data.successRate),
      failed: Math.round(t.count * (1 - data.successRate)),
      avgDurationMs: data.avgDurationMs,
      totalTokens: 0,
    })),
    models: data.topAgents.map(a => ({
      model: a.agentName,
      requests: a.totalExecutions,
      successRate: a.successRate,
      avgDurationMs: a.avgDurationMs,
      totalTokens: a.avgTokens * a.totalExecutions,
      costUsd: a.avgTokens * 0.00001,
    })),
    sources: [],
    routing: data.topAgents.map(a => ({
      method: a.agentId,
      requests: a.totalExecutions,
      avgConfidence: 0.85,
      successRate: a.successRate,
    })),
    topRequesters: data.topUsers.map(u => ({
      userId: u.userId,
      displayName: u.displayName ?? null,
      requests: u.totalInteractions ?? 0,
      successRate: u.successRate ?? 1.0,
      totalTokens: 0,
    })),
    feedback: {
      thumbsUp: overview?.feedback.positive ?? data.thumbsUp,
      thumbsDown: overview?.feedback.negative ?? data.thumbsDown,
      satisfactionScore: overview?.feedback.satisfactionRate ?? data.satisfactionScore,
    },
  }
}

// Dashboard
export const dashboardApi = {
  getStats: async (period = '7d'): Promise<DashboardStats> => {
    const days = period.replace('d', '').replace('h', '')
    // Dashboard API와 Overview API 병렬 호출
    const [dashboardData, overviewData] = await Promise.all([
      fetchApi<DashboardApiResponse>(`/analytics/dashboard?days=${days}`),
      fetchApi<OverviewApiResponse>(`/analytics/overview?days=${days}`).catch(() => null),
    ])
    return transformDashboardStats(dashboardData, overviewData)
  },

  getOverview: (period = '7d') => {
    const days = period.replace('d', '').replace('h', '')
    return fetchApi<OverviewApiResponse>(`/analytics/overview?days=${days}`)
  },

  getTimeseries: (granularity = 'daily', days = 7) =>
    fetchApi<{ granularity: string; days: number; data: TimeSeriesData[] }>(
      `/analytics/timeseries?granularity=${granularity}&days=${days}`
    ),
}

// Executions
export const executionsApi = {
  getRecent: (limit = 50) =>
    fetchApi<ExecutionRecord[]>(`/executions/recent?limit=${limit}`),

  getById: (id: string) =>
    fetchApi<ExecutionRecord>(`/executions/${id}`),

  retry: (id: string) =>
    fetchApi<{ success: boolean }>(`/executions/${id}/retry`, { method: 'POST' }),
}

// Agents (v2 API)
export const agentsApi = {
  getAll: () =>
    fetchApi<Agent[]>('/agents', undefined, API_BASE_V2),

  getByProject: (projectId: string) =>
    fetchApi<Agent[]>(`/agents?projectId=${projectId}`, undefined, API_BASE_V2),

  getById: (id: string, projectId?: string) =>
    fetchApi<Agent>(`/agents/${id}${projectId ? `?projectId=${projectId}` : ''}`, undefined, API_BASE_V2),

  create: (agent: Omit<Agent, 'id'>) =>
    fetchApi<Agent>('/agents', {
      method: 'POST',
      body: JSON.stringify(agent),
    }, API_BASE_V2),

  update: (id: string, agent: Partial<Agent>) =>
    fetchApi<Agent>(`/agents/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(agent),
    }, API_BASE_V2),

  delete: (id: string, projectId?: string) =>
    fetchApi<{ success: boolean }>(`/agents/${id}${projectId ? `?projectId=${projectId}` : ''}`, {
      method: 'DELETE',
    }, API_BASE_V2),

  setEnabled: (id: string, enabled: boolean) =>
    fetchApi<Agent>(`/agents/${id}/enabled`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled }),
    }, API_BASE_V2),
}

// Analytics
export const analyticsApi = {
  getFeedback: (period = '7d') =>
    fetchApi<FeedbackAnalysis>(`/analytics/feedback?period=${period}`),

  getTokenUsage: (period = '7d') =>
    fetchApi<TokenUsage>(`/analytics/tokens?period=${period}`),

  getRoutingEfficiency: (period = '7d') =>
    fetchApi<{ period: string; routing: RoutingStats[] }>(`/analytics/routing?period=${period}`),

  getProjectStats: () =>
    fetchApi<ProjectStat[]>('/analytics/projects'),

  getModels: async (period = '7d') => {
    const days = period.replace('d', '')
    const models = await fetchApi<ModelStats[]>(`/analytics/models?days=${days}`)
    return { period, models }
  },

  getErrors: async (period = '7d') => {
    const days = period.replace('d', '')
    const errors = await fetchApi<ErrorStats[]>(`/analytics/errors?days=${days}`)
    return { period, errors }
  },

  getSources: async (period = '7d') => {
    const days = period.replace('d', '')
    const sources = await fetchApi<SourceStats[]>(`/analytics/sources?days=${days}`)
    return { period, sources }
  },

  getRequesters: async (period = '7d') => {
    const days = period.replace('d', '')
    const requesters = await fetchApi<RequesterStats[]>(`/analytics/requesters?days=${days}`)
    return { period, requesters }
  },
}

// Users API response type (from backend)
interface UserSummaryDto {
  userId: string
  displayName: string | null
  totalInteractions: number
  lastSeen: string
  hasSummary: boolean
}

// Transform backend response to frontend expected format
function transformUserSummary(dto: UserSummaryDto): UserContext {
  return {
    userId: dto.userId,
    displayName: dto.displayName,
    preferredLanguage: 'ko',
    domain: null,
    totalInteractions: dto.totalInteractions,
    totalChars: 0,
    lastSeen: dto.lastSeen,
    summary: dto.hasSummary ? '(summary available)' : null,
    summaryUpdatedAt: null,
  }
}

// Users
export const usersApi = {
  getAll: async (): Promise<UserContext[]> => {
    const data = await fetchApi<UserSummaryDto[]>('/users')
    return data.map(transformUserSummary)
  },

  getById: (userId: string) =>
    fetchApi<UserContext>(`/users/${userId}`),

  getContext: (userId: string, acquireLock = false, lockId?: string) => {
    const params = new URLSearchParams()
    params.set('acquire_lock', acquireLock.toString())
    if (lockId) params.set('lock_id', lockId)
    return fetchApi<UserContextResponse>(`/users/${userId}/context?${params}`)
  },

  saveContext: (userId: string, data: { summary?: string; displayName?: string }) =>
    fetchApi<{ success: boolean }>(`/users/${userId}/context`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),

  getFormattedContext: (userId: string) =>
    fetchApi<{ userId: string; formattedContext: string; totalRules: number; totalConversations: number }>(
      `/users/${userId}/context/formatted`
    ),

  getRules: (userId: string) =>
    fetchApi<{ userId: string; rules: string[] }>(`/users/${userId}/rules`),

  addRule: (userId: string, rule: string) =>
    fetchApi<{ success: boolean; rule?: string }>(`/users/${userId}/rules`, {
      method: 'POST',
      body: JSON.stringify({ rule }),
    }),

  deleteRule: (userId: string, rule: string) =>
    fetchApi<{ success: boolean }>(`/users/${userId}/rules`, {
      method: 'DELETE',
      body: JSON.stringify({ rule }),
    }),

  releaseLock: (userId: string, lockId: string) =>
    fetchApi<{ success: boolean }>(`/users/${userId}/context/lock?lock_id=${lockId}`, {
      method: 'DELETE',
    }),
}

// Health
export const healthApi = {
  check: () =>
    fetchApi<{ status: string; version: string }>('/health'),
}

// Plugins
export interface PluginInfo {
  id: string
  name: string
  description: string
  enabled: boolean
  commands: string[]
}

export interface PluginDetail {
  id: string
  name: string
  description: string
  enabled: boolean
  commands: {
    name: string
    description: string
    usage: string
    examples: string[]
  }[]
}

export const pluginsApi = {
  getAll: () =>
    fetchApi<PluginInfo[]>('/plugins'),

  getById: (pluginId: string) =>
    fetchApi<PluginDetail>(`/plugins/${pluginId}`),

  setEnabled: (pluginId: string, enabled: boolean) =>
    fetchApi<{ success: boolean; pluginId: string; enabled: boolean }>(
      `/plugins/${pluginId}/enabled`,
      {
        method: 'PATCH',
        body: JSON.stringify({ enabled }),
      }
    ),

  execute: (pluginId: string, command: string, args: Record<string, unknown> = {}) =>
    fetchApi<{ success: boolean; data?: unknown; message?: string; error?: string }>(
      `/plugins/${pluginId}/execute`,
      {
        method: 'POST',
        body: JSON.stringify({ command, args }),
      }
    ),
}

// Routing / Classification
export interface ClassifyResult {
  agentId: string
  agentName: string
  confidence: number
  method: string
  alternatives: {
    agentId: string
    agentName: string
    confidence: number
  }[]
}

// Backend route response type
interface RouteResponse {
  agentId: string
  agentName: string
  confidence: number
  matchedKeyword: string | null
  systemPrompt: string
  model: string
  allowedTools: string[]
}

export const routingApi = {
  classify: async (message: string, _projectId?: string): Promise<ClassifyResult> => {
    // Use /route endpoint instead of /routing/classify
    const response = await fetchApi<RouteResponse>(
      '/route',
      {
        method: 'POST',
        body: JSON.stringify({ message }),
      }
    )

    return {
      agentId: response.agentId,
      agentName: response.agentName,
      confidence: response.confidence,
      method: response.matchedKeyword ? 'keyword' : 'default',
      alternatives: [],
    }
  },
}

// Feedback
export interface FeedbackRecord {
  id: string
  executionId: string
  userId: string
  reaction: 'thumbs_up' | 'thumbs_down'
  comment: string | null
  createdAt: string
}

export interface FeedbackStats {
  totalCount: number
  thumbsUp: number
  thumbsDown: number
  satisfactionRate: number
  recentFeedback: FeedbackRecord[]
  byAgent: {
    agentId: string
    thumbsUp: number
    thumbsDown: number
  }[]
}

export const feedbackApi = {
  getStats: (days = 7) =>
    fetchApi<FeedbackStats>(`/analytics/feedback/detailed?days=${days}`),

  getRecent: (limit = 50) =>
    fetchApi<FeedbackRecord[]>(`/feedback/recent?limit=${limit}`),
}

// n8n Workflows (via backend proxy to avoid CORS issues)
export interface N8nWorkflow {
  id: string
  name: string
  active: boolean
  createdAt: string
  updatedAt: string
  nodes: unknown[]
  connections: unknown
  settings?: {
    executionOrder?: string
  }
  staticData?: unknown
  tags?: { id: string; name: string }[]
}

export interface N8nExecution {
  id: string
  finished: boolean
  mode: string
  startedAt: string
  stoppedAt?: string
  workflowId: string
  status: string
  workflowName?: string
  data?: unknown
}

export const n8nApi = {
  // Get all workflows (via backend proxy)
  getWorkflows: async (): Promise<N8nWorkflow[]> => {
    try {
      return await fetchApi<N8nWorkflow[]>('/n8n/workflows')
    } catch {
      console.warn('n8n API not available')
      return []
    }
  },

  // Get workflow by ID (via backend proxy)
  getWorkflow: async (id: string): Promise<N8nWorkflow | null> => {
    try {
      return await fetchApi<N8nWorkflow>(`/n8n/workflows/${id}`)
    } catch {
      return null
    }
  },

  // Activate/Deactivate workflow (via backend proxy)
  setActive: async (id: string, active: boolean): Promise<boolean> => {
    try {
      const result = await fetchApi<{ success: boolean }>(`/n8n/workflows/${id}/active`, {
        method: 'PATCH',
        body: JSON.stringify({ active }),
      })
      return result.success
    } catch {
      return false
    }
  },

  // Get recent executions (via backend proxy)
  getExecutions: async (limit = 100): Promise<N8nExecution[]> => {
    try {
      return await fetchApi<N8nExecution[]>(`/n8n/executions?limit=${limit}`)
    } catch {
      return []
    }
  },

  // Execute workflow manually (via backend proxy)
  executeWorkflow: async (id: string): Promise<boolean> => {
    try {
      const result = await fetchApi<{ success: boolean }>(`/n8n/workflows/${id}/run`, {
        method: 'POST',
      })
      return result.success
    } catch {
      return false
    }
  },
}

// Settings API
export interface ProjectAlias {
  patterns: string[]
  description: string
}

export interface ProjectAliasesConfig {
  workspaceRoot: string
  aliases: Record<string, ProjectAlias>
  suffixes: string[]
}

export interface TestAliasResult {
  text: string
  detected: {
    projectId: string
    matchedPattern: string
    description: string | null
  }[]
}

export const settingsApi = {
  // Project Aliases
  getProjectAliases: () =>
    fetchApi<ProjectAliasesConfig>('/settings/project-aliases'),

  saveProjectAliases: (config: ProjectAliasesConfig) =>
    fetchApi<{ success: boolean; message: string }>('/settings/project-aliases', {
      method: 'PUT',
      body: JSON.stringify(config),
    }),

  upsertProjectAlias: (projectId: string, alias: ProjectAlias) =>
    fetchApi<{ success: boolean; message: string }>(`/settings/project-aliases/${projectId}`, {
      method: 'PUT',
      body: JSON.stringify(alias),
    }),

  deleteProjectAlias: (projectId: string) =>
    fetchApi<{ success: boolean; message: string }>(`/settings/project-aliases/${projectId}`, {
      method: 'DELETE',
    }),

  testProjectAliases: (text: string) =>
    fetchApi<TestAliasResult>('/settings/project-aliases/test', {
      method: 'POST',
      body: JSON.stringify({ text }),
    }),
}
