import type {
  DashboardStats,
  ExecutionRecord,
  Agent,
  FeedbackAnalysis,
  TokenUsage,
  RoutingEfficiency,
  ProjectStat,
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

// Dashboard
export const dashboardApi = {
  getStats: (days = 30) =>
    fetchApi<DashboardStats>(`/analytics/dashboard?days=${days}`),
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
  getFeedback: (days = 30) =>
    fetchApi<FeedbackAnalysis>(`/analytics/feedback?days=${days}`),

  getTokenUsage: (days = 30) =>
    fetchApi<TokenUsage>(`/analytics/tokens?days=${days}`),

  getRoutingEfficiency: () =>
    fetchApi<RoutingEfficiency>('/analytics/routing'),

  getProjectStats: () =>
    fetchApi<ProjectStat[]>('/analytics/projects'),
}

// Health
export const healthApi = {
  check: () =>
    fetchApi<{ status: string; version: string }>('/health'),
}
