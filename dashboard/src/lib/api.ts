import type {
  DashboardStats,
  ExecutionRecord,
  Agent,
  FeedbackAnalysis,
  TokenUsage,
  RoutingEfficiency,
  ProjectStat,
  UserContext,
  UserContextResponse,
  SummaryStats,
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

  return response.json
}

// Dashboard
export const dashboardApi = {
  getStats: (period = '7d') =>
    fetchApi<DashboardStats>(`/analytics/dashboard?period=${period}`),

  getOverview: (period = '7d') =>
    fetchApi<{ period: string; summary: SummaryStats }>(`/analytics/overview?period=${period}`),

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
  getAll: =>
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

  getProjectStats: =>
    fetchApi<ProjectStat[]>('/analytics/projects'),

  getModels: (period = '7d') =>
    fetchApi<{ period: string; models: ModelStats[] }>(`/analytics/models?period=${period}`),

  getErrors: (period = '7d') =>
    fetchApi<{ period: string; errors: ErrorStats[] }>(`/analytics/errors?period=${period}`),

  getSources: (period = '7d') =>
    fetchApi<{ period: string; sources: SourceStats[] }>(`/analytics/sources?period=${period}`),

  getRequesters: (period = '7d') =>
    fetchApi<{ period: string; requesters: RequesterStats[] }>(`/analytics/requesters?period=${period}`),
}

// Users
export const usersApi = {
  getAll: =>
    fetchApi<UserContext[]>('/users'),

  getById: (userId: string) =>
    fetchApi<UserContext>(`/users/${userId}`),

  getContext: (userId: string, acquireLock = false, lockId?: string) => {
    const params = new URLSearchParams
    params.set('acquire_lock', acquireLock.toString)
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
  check: =>
    fetchApi<{ status: string; version: string }>('/health'),
}
