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
  RequesterStats,
  ErrorStats,
  Project,
  ProjectInput,
  ProjectStats,
  VerifiedFeedbackStats,
  FeedbackByCategory,
  RoutingEfficiency,
} from '@/types'

const API_BASE_V1 = '/api/v1'
const API_BASE_V2 = '/api/v2'

/**
 * Period를 API 파라미터로 변환
 * - 1h → hours=1
 * - 24h → hours=24
 * - 7d → days=7
 * - 30d → days=30
 */
function periodToParams(period: string): { days?: number; hours?: number } {
  if (period.endsWith('h')) {
    const hours = parseInt(period.replace('h', ''), 10)
    return { hours }
  }
  const days = parseInt(period.replace('d', ''), 10) || 7
  return { days }
}

function buildPeriodQuery(period: string): string {
  const params = periodToParams(period)
  if (params.hours) {
    return `hours=${params.hours}`
  }
  return `days=${params.days}`
}

// ============================================================================
// ESTIMATION CONSTANTS (Used when actual data is unavailable from API)
// These values are approximations and should be replaced with real data
// from the backend when available.
// ============================================================================

// Percentile estimation multipliers (relative to avgDurationMs)
// TODO: Backend should provide actual percentile data via /analytics/overview
const PERCENTILE_ESTIMATES = {
  P50_MULTIPLIER: 0.8,  // Estimate: 80% of average
  P90_MULTIPLIER: 1.2,  // Estimate: 120% of average
  P95_MULTIPLIER: 1.5,  // Estimate: 150% of average
  P99_MULTIPLIER: 2.0,  // Estimate: 200% of average
} as const

// Token cost estimation (USD per token)
// Based on Claude Sonnet 4 API pricing:
// - Input: $3/1M tokens = $0.000003/token
// - Output: $15/1M tokens = $0.000015/token
// Using weighted average based on typical 40:60 input:output ratio
const TOKEN_COST = {
  INPUT_PER_TOKEN: 0.000003,   // $3/1M tokens
  OUTPUT_PER_TOKEN: 0.000015,  // $15/1M tokens
  // Weighted average: (0.4 * 0.000003) + (0.6 * 0.000015) = 0.0000102
  AVERAGE_PER_TOKEN: 0.0000102,
} as const

// Token distribution estimation (when only totalTokens is available)
// TODO: Backend should provide actual input/output token counts
const TOKEN_DISTRIBUTION = {
  INPUT_RATIO: 0.4,   // Estimate: 40% input tokens
  OUTPUT_RATIO: 0.6,  // Estimate: 60% output tokens
} as const

// Default confidence score (when routing confidence is unavailable)
// TODO: Backend should include confidence in routing response
const DEFAULT_ROUTING_CONFIDENCE = 0.85

// Default n8n URL (when environment variable is not set)
export const DEFAULT_N8N_URL = 'http://localhost:5678'

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

// Source stats type from backend
interface SourceStatsApiResponse {
  source: string
  requests: number
  successRate: number
}

// Transform backend response to frontend expected format
function transformDashboardStats(
  data: DashboardApiResponse,
  overview: OverviewApiResponse | null,
  sources: SourceStatsApiResponse[] | null
): DashboardStats {
  // 실제 백분위수 사용 (없으면 avgDurationMs 기반 추정)
  const percentiles = overview?.percentiles || {
    p50: data.avgDurationMs * PERCENTILE_ESTIMATES.P50_MULTIPLIER,
    p90: data.avgDurationMs * PERCENTILE_ESTIMATES.P90_MULTIPLIER,
    p95: data.avgDurationMs * PERCENTILE_ESTIMATES.P95_MULTIPLIER,
    p99: data.avgDurationMs * PERCENTILE_ESTIMATES.P99_MULTIPLIER,
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
      totalCostUsd: overview?.totalCostUsd ?? data.totalTokens * TOKEN_COST.AVERAGE_PER_TOKEN,
      totalInputTokens: overview?.totalInputTokens ?? Math.round(data.totalTokens * TOKEN_DISTRIBUTION.INPUT_RATIO),
      totalOutputTokens: overview?.totalOutputTokens ?? Math.round(data.totalTokens * TOKEN_DISTRIBUTION.OUTPUT_RATIO),
    },
    timeseries: (() => {
      // Calculate average tokens per request for distribution
      const totalRequests = data.hourlyTrend.reduce((sum, t) => sum + t.count, 0)
      const avgTokensPerRequest = totalRequests > 0 ? Math.round(data.totalTokens / totalRequests) : 0

      return data.hourlyTrend.map((t, idx) => ({
        timestamp: new Date(Date.now() - (23 - idx) * 3600000).toISOString(),
        requests: t.count,
        successful: Math.round(t.count * data.successRate),
        failed: Math.round(t.count * (1 - data.successRate)),
        avgDurationMs: data.avgDurationMs,
        totalTokens: t.count * avgTokensPerRequest,  // Estimated tokens per hour
      }))
    })(),
    models: data.topAgents.map(a => ({
      model: a.agentName,
      requests: a.totalExecutions,
      successRate: a.successRate,
      avgDurationMs: a.avgDurationMs,
      totalTokens: a.avgTokens * a.totalExecutions,
      costUsd: a.avgTokens * a.totalExecutions * TOKEN_COST.AVERAGE_PER_TOKEN,  // Estimated total cost per agent
    })),
    sources: (sources || []).map(s => ({
      source: s.source,
      requests: s.requests,
      successRate: s.successRate,
    })),
    routing: data.topAgents.map(a => ({
      method: a.agentId,
      requests: a.totalExecutions,
      avgConfidence: DEFAULT_ROUTING_CONFIDENCE,  // TODO: Get from routing API
      successRate: a.successRate,
    })),
    topRequesters: (() => {
      // Calculate average tokens per request for user token estimation
      const totalRequests = data.totalExecutions || 1
      const avgTokensPerRequest = data.totalTokens / totalRequests

      return data.topUsers.map(u => ({
        userId: u.userId,
        displayName: u.displayName ?? null,
        requests: u.totalInteractions ?? 0,
        successRate: u.successRate ?? 1.0,
        totalTokens: Math.round((u.totalInteractions ?? 0) * avgTokensPerRequest),  // Estimated tokens
      }))
    })(),
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
    const query = buildPeriodQuery(period)
    // Dashboard API, Overview API, Sources API 병렬 호출
    const [dashboardData, overviewData, sourcesData] = await Promise.all([
      fetchApi<DashboardApiResponse>(`/analytics/dashboard?${query}`),
      fetchApi<OverviewApiResponse>(`/analytics/overview?${query}`).catch(() => null),
      fetchApi<SourceStatsApiResponse[]>(`/analytics/sources?${query}`).catch(() => null),
    ])
    return transformDashboardStats(dashboardData, overviewData, sourcesData)
  },

  getOverview: (period = '7d') => {
    const query = buildPeriodQuery(period)
    return fetchApi<OverviewApiResponse>(`/analytics/overview?${query}`)
  },

  getTimeseries: (granularity = 'daily', days = 7) =>
    fetchApi<{ granularity: string; days: number; data: TimeSeriesData[] }>(
      `/analytics/timeseries?granularity=${granularity}&days=${days}`
    ),
}

// Executions
// Backend ExecutionDto response type
interface ExecutionApiResponse {
  executionId: string
  prompt: string
  result: string | null
  status: string
  agentId: string
  durationMs: number
  createdAt: string
  inputTokens?: number
  outputTokens?: number
}

// Transform backend response to frontend type
function transformExecution(data: ExecutionApiResponse): ExecutionRecord {
  return {
    id: data.executionId,
    prompt: data.prompt,
    result: data.result,
    status: data.status as ExecutionRecord['status'],
    agentId: data.agentId,
    projectId: null,
    userId: null,
    channel: null,
    threadTs: null,
    replyTs: null,
    durationMs: data.durationMs,
    inputTokens: data.inputTokens ?? 0,
    outputTokens: data.outputTokens ?? 0,
    cost: null,
    error: null,
    createdAt: data.createdAt,
  }
}

export const executionsApi = {
  getRecent: async (limit = 50): Promise<ExecutionRecord[]> => {
    const data = await fetchApi<ExecutionApiResponse[]>(`/executions/recent?limit=${limit}`)
    return data.map(transformExecution)
  },

  getById: async (id: string): Promise<ExecutionRecord> => {
    const data = await fetchApi<ExecutionApiResponse>(`/executions/${id}`)
    return transformExecution(data)
  },

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
  getFeedback: (period = '7d') => {
    const query = buildPeriodQuery(period)
    return fetchApi<FeedbackAnalysis>(`/analytics/feedback?${query}`)
  },

  getTokenUsage: (period = '7d') => {
    const query = buildPeriodQuery(period)
    return fetchApi<TokenUsage>(`/analytics/tokens?${query}`)
  },

  getRoutingEfficiency: (_period = '7d') =>
    fetchApi<RoutingEfficiency>(`/analytics/routing`),

  getProjectStats: () =>
    fetchApi<ProjectStat[]>('/analytics/projects'),

  getModels: async (period = '7d') => {
    const query = buildPeriodQuery(period)
    const models = await fetchApi<ModelStats[]>(`/analytics/models?${query}`)
    return { period, models }
  },

  getErrors: async (period = '7d') => {
    const query = buildPeriodQuery(period)
    const errors = await fetchApi<ErrorStats[]>(`/analytics/errors?${query}`)
    return { period, errors }
  },

  getSources: async (period = '7d') => {
    const query = buildPeriodQuery(period)
    const sources = await fetchApi<SourceStats[]>(`/analytics/sources?${query}`)
    return { period, sources }
  },

  getRequesters: async (period = '7d') => {
    const query = buildPeriodQuery(period)
    const requesters = await fetchApi<RequesterStats[]>(`/analytics/requesters?${query}`)
    return { period, requesters }
  },

  // Time Series APIs
  getTokensTrend: async (period = '7d') => {
    const query = buildPeriodQuery(period)
    return fetchApi<TokenTrendPoint[]>(`/analytics/tokens/trend?${query}`)
  },

  getErrorsTrend: async (period = '7d') => {
    const query = buildPeriodQuery(period)
    return fetchApi<ErrorTrendPoint[]>(`/analytics/errors/trend?${query}`)
  },

  getFeedbackTrend: async (period = '7d') => {
    const query = buildPeriodQuery(period)
    return fetchApi<FeedbackTrendPoint[]>(`/analytics/feedback/trend?${query}`)
  },
}

// Time Series Types
export interface TokenTrendPoint {
  date: string
  inputTokens: number
  outputTokens: number
}

export interface ErrorTrendPoint {
  date: string
  errorCount: number
}

export interface FeedbackTrendPoint {
  date: string
  positive: number
  negative: number
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

// System / Environment Configuration API
export interface EnvVariable {
  key: string
  value: string
}

export interface EnvVarSchema {
  key: string
  label: string
  description: string
  group: string
  required: boolean
  sensitive: boolean
  placeholder: string
}

export interface EnvGroup {
  id: string
  name: string
  description: string
  required: boolean
}

export interface EnvConfigResponse {
  success: boolean
  path: string | null
  exists: boolean
  variables: EnvVariable[]
  message: string | null
}

export interface EnvSchemaResponse {
  schema: EnvVarSchema[]
  groups: EnvGroup[]
}

export const systemApi = {
  // Get environment config
  getEnvConfig: () =>
    fetchApi<EnvConfigResponse>('/system/env'),

  // Save environment config
  saveEnvConfig: (variables: EnvVariable[]) =>
    fetchApi<{ success: boolean; message: string; path: string | null }>('/system/env', {
      method: 'PUT',
      body: JSON.stringify({ variables }),
    }),

  // Get environment schema (for UI rendering)
  getEnvSchema: () =>
    fetchApi<EnvSchemaResponse>('/system/env/schema'),

  // Get system health
  getHealth: () =>
    fetchApi<{ status: string; components: Record<string, { status: string; details: Record<string, unknown> }> }>('/system/health'),

  // Slack status
  getSlackStatus: () =>
    fetchApi<Record<string, unknown>>('/system/slack/status'),

  // Force Slack reconnect
  reconnectSlack: () =>
    fetchApi<{ success: boolean; message?: string; error?: string }>('/system/slack/reconnect', {
      method: 'POST',
    }),
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

  // Get auth cookie for n8n iframe embed
  getAuth: async (): Promise<{ authCookie: string | null; n8nUrl: string; success: boolean }> => {
    try {
      return await fetchApi<{ authCookie: string | null; n8nUrl: string; success: boolean }>('/n8n/auth')
    } catch {
      return { authCookie: null, n8nUrl: DEFAULT_N8N_URL, success: false }
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

// Projects API (멀티테넌시)
export const projectsApi = {
  // Get all projects
  getAll: () =>
    fetchApi<Project[]>('/projects'),

  // Get project by ID
  getById: (projectId: string) =>
    fetchApi<Project>(`/projects/${projectId}`),

  // Create project
  create: (project: ProjectInput) =>
    fetchApi<Project>('/projects', {
      method: 'POST',
      body: JSON.stringify(project),
    }),

  // Update project
  update: (projectId: string, data: Partial<Project>) =>
    fetchApi<Project>(`/projects/${projectId}`, {
      method: 'PATCH',
      body: JSON.stringify(data),
    }),

  // Delete project
  delete: (projectId: string) =>
    fetchApi<{ success: boolean }>(`/projects/${projectId}`, {
      method: 'DELETE',
    }),

  // Set as default project
  setDefault: (projectId: string) =>
    fetchApi<{ success: boolean }>(`/projects/${projectId}/default`, {
      method: 'POST',
    }),

  // Get project stats
  getStats: (projectId: string) =>
    fetchApi<ProjectStats>(`/projects/${projectId}/stats`),

  // Get project agents
  getAgents: (projectId: string) =>
    fetchApi<Agent[]>(`/projects/${projectId}/agents`),

  // Create agent for project
  createAgent: (projectId: string, agent: Omit<Agent, 'id' | 'projectId'>) =>
    fetchApi<Agent>(`/projects/${projectId}/agents`, {
      method: 'POST',
      body: JSON.stringify(agent),
    }),

  // Delete project agent
  deleteAgent: (projectId: string, agentId: string) =>
    fetchApi<{ success: boolean }>(`/projects/${projectId}/agents/${agentId}`, {
      method: 'DELETE',
    }),

  // Get project channels
  getChannels: (projectId: string) =>
    fetchApi<string[]>(`/projects/${projectId}/channels`),

  // Map channel to project
  mapChannel: (projectId: string, channel: string) =>
    fetchApi<{ success: boolean }>(`/projects/${projectId}/channels`, {
      method: 'POST',
      body: JSON.stringify({ channel }),
    }),

  // Unmap channel
  unmapChannel: (projectId: string, channel: string) =>
    fetchApi<{ success: boolean }>(`/projects/${projectId}/channels/${channel}`, {
      method: 'DELETE',
    }),

  // Update rate limit
  updateRateLimit: (projectId: string, rpm: number) =>
    fetchApi<{ success: boolean }>(`/projects/${projectId}/rate-limit`, {
      method: 'PATCH',
      body: JSON.stringify({ rpm }),
    }),
}

// Jira API
export interface JiraIssue {
  key: string
  summary: string
  description: string | null
  status: string
  priority: string | null
  assignee: string | null
  reporter: string | null
  issuetype: string
  created: string
  updated: string
  url: string
  labels?: string[]
}

export interface JiraIssueListItem {
  key: string
  summary: string
  status: string
  assignee: string | null
  priority: string | null
  type: string
  url: string
}

export interface JiraProject {
  key: string
  name: string
  projectTypeKey: string
  style: string
  url: string
  avatarUrl?: string
}

export interface JiraBoard {
  id: number
  name: string
  type: string
  projectKey: string | null
  projectName: string | null
}

export interface JiraComment {
  id: string
  author: string
  body: string | null
  created: string
  updated: string
}

export interface JiraSprint {
  id: number
  name: string
  state: 'active' | 'closed' | 'future'
  startDate: string | null
  endDate: string | null
  completeDate: string | null
  goal: string | null
}

export const jiraApi = {
  // Get issue details
  getIssue: (issueKey: string) =>
    fetchApi<{ success: boolean; data: JiraIssue; error?: string }>(`/plugins/jira/issues/${issueKey}`),

  // Get my issues
  getMyIssues: () =>
    fetchApi<{ success: boolean; data: JiraIssueListItem[]; message?: string; error?: string }>('/plugins/jira/my-issues'),

  // Search issues by JQL
  searchIssues: (jql: string) =>
    fetchApi<{ success: boolean; data: JiraIssueListItem[]; message?: string; error?: string }>(
      `/plugins/jira/search?jql=${encodeURIComponent(jql)}`
    ),

  // Create issue
  createIssue: (
    project: string,
    summary: string,
    description?: string,
    issueType = 'Task',
    options?: {
      priority?: string
      parentIssue?: string
      epicLink?: string
      assignee?: string
      reporter?: string
      labels?: string[]
      components?: string[]
      storyPoints?: number
      originalEstimate?: string
      startDate?: string
      dueDate?: string
      sprintId?: number
    }
  ) =>
    fetchApi<{ success: boolean; data: { key: string; id: string; url: string }; message?: string; error?: string }>(
      '/plugins/jira/issues',
      {
        method: 'POST',
        body: JSON.stringify({
          project,
          summary,
          description,
          issueType,
          ...options,
        }),
      }
    ),

  // Get available transitions for an issue
  getTransitions: (issueKey: string) =>
    fetchApi<{
      success: boolean
      data?: {
        transitions: Array<{
          id: string
          name: string
          to: { id: string; name: string }
        }>
      }
      error?: string
    }>(`/plugins/jira/issues/${issueKey}/transitions`),

  // Transition issue
  transitionIssue: (
    issueKey: string,
    status: string,
    options?: { dueDate?: string; startDate?: string }
  ) =>
    fetchApi<{ success: boolean; message?: string; error?: string }>(
      `/plugins/jira/issues/${issueKey}/transition`,
      {
        method: 'POST',
        body: JSON.stringify({
          status,
          dueDate: options?.dueDate,
          startDate: options?.startDate,
        }),
      }
    ),

  // Add comment
  addComment: (issueKey: string, comment: string) =>
    fetchApi<{ success: boolean; data?: { id: string; created: string }; message?: string; error?: string }>(
      `/plugins/jira/issues/${issueKey}/comments`,
      {
        method: 'POST',
        body: JSON.stringify({ comment }),
      }
    ),

  // Get comments
  getComments: (issueKey: string) =>
    fetchApi<{ success: boolean; data: JiraComment[]; message?: string; error?: string }>(
      `/plugins/jira/issues/${issueKey}/comments`
    ),

  // Assign issue
  assignIssue: (issueKey: string, assignee: string) =>
    fetchApi<{ success: boolean; message?: string; error?: string }>(
      `/plugins/jira/issues/${issueKey}/assignee`,
      {
        method: 'PUT',
        body: JSON.stringify({ assignee }),
      }
    ),

  // Manage labels
  manageLabels: (issueKey: string, action: 'add' | 'remove', label: string) =>
    fetchApi<{ success: boolean; message?: string; error?: string }>(
      `/plugins/jira/issues/${issueKey}/labels`,
      {
        method: 'POST',
        body: JSON.stringify({ action, label }),
      }
    ),

  // Link issues
  linkIssues: (issueKey: string, linkType: string, targetIssue: string) =>
    fetchApi<{ success: boolean; message?: string; error?: string }>(
      `/plugins/jira/issues/${issueKey}/links`,
      {
        method: 'POST',
        body: JSON.stringify({ linkType, targetIssue }),
      }
    ),

  // Get projects
  getProjects: () =>
    fetchApi<{ success: boolean; data: JiraProject[]; message?: string; error?: string }>('/plugins/jira/projects'),

  // Get boards
  getBoards: (projectKey?: string) => {
    const params = projectKey ? `?projectKey=${projectKey}` : ''
    return fetchApi<{ success: boolean; data: JiraBoard[]; message?: string; error?: string }>(`/plugins/jira/boards${params}`)
  },

  // Get sprints for a board
  getSprints: (boardId: number) =>
    fetchApi<{ success: boolean; data: JiraSprint[]; message?: string; error?: string }>(`/plugins/jira/boards/${boardId}/sprints`),

  // Get sprint issues
  getSprintIssues: (boardId?: number, sprintId?: number) => {
    const params = new URLSearchParams()
    if (boardId) params.set('boardId', boardId.toString())
    if (sprintId) params.set('sprintId', sprintId.toString())
    const queryString = params.toString()
    return fetchApi<{ success: boolean; data: JiraIssueListItem[]; message?: string; error?: string }>(
      `/plugins/jira/sprint${queryString ? `?${queryString}` : ''}`
    )
  },

  // AI Analysis - Analyze issue with Claude
  analyzeIssue: (issueKey: string, options?: { context?: string; projectPath?: string; addComment?: boolean }) =>
    fetchApi<{
      success: boolean
      analysis?: string
      issueKey?: string
      issueSummary?: string
      tokensUsed?: number
      error?: string
    }>(`/jira/analyze/${issueKey}`, {
      method: 'POST',
      body: JSON.stringify(options || {}),
    }),

  // AI Analysis - Code context
  analyzeCodeContext: (issueKey: string, projectPath: string) =>
    fetchApi<{
      success: boolean
      issueKey?: string
      analysis?: string
      projectPath?: string
      error?: string
    }>(`/jira/analyze/${issueKey}/code-context`, {
      method: 'POST',
      body: JSON.stringify({ projectPath }),
    }),

  // AI Analysis - Sprint report
  generateSprintReport: (boardId?: number) =>
    fetchApi<{
      success: boolean
      report?: string
      totalIssues?: number
      byStatus?: Record<string, number>
      error?: string
    }>('/jira/sprint-report', {
      method: 'POST',
      body: JSON.stringify({ boardId }),
    }),

  // AI Analysis - Auto-label issue
  autoLabelIssue: (issueKey: string) =>
    fetchApi<{
      success: boolean
      issueKey?: string
      suggestedLabels?: string[]
      analysis?: string
      error?: string
    }>(`/jira/auto-label/${issueKey}`, {
      method: 'POST',
    }),

  // AI - Natural Language to JQL conversion (Claude 기반)
  nlToJql: (query: string, includeProjects = true) =>
    fetchApi<{
      success: boolean
      jql?: string
      explanation?: string
      confidence?: number
      warnings?: string[]
      error?: string
    }>('/jira/nl-to-jql', {
      method: 'POST',
      body: JSON.stringify({ query, includeProjects }),
    }),

  // AI - Natural Language Issue Analysis (자연어를 분석하여 이슈 필드 제안)
  analyzeIssueFromText: (text: string) =>
    fetchApi<{
      success: boolean
      data?: {
        summary: string
        description: string
        issueType: string
        priority: string
        labels?: string[]
      }
      error?: string
    }>('/jira/analyze-text', {
      method: 'POST',
      body: JSON.stringify({ text }),
    }),

  // Search users (for assignee/reporter fields)
  searchUsers: (query: string, projectKey?: string) =>
    fetchApi<{
      success: boolean
      data?: Array<{
        accountId: string
        displayName: string
        emailAddress?: string
        avatarUrl?: string
      }>
      error?: string
    }>(`/plugins/jira/users/search?query=${encodeURIComponent(query)}${projectKey ? `&projectKey=${projectKey}` : ''}`),

  // Get assignable users for a project
  getAssignableUsers: (projectKey: string) =>
    fetchApi<{
      success: boolean
      data?: Array<{
        accountId: string
        displayName: string
        emailAddress?: string
        avatarUrl?: string
      }>
      error?: string
    }>(`/plugins/jira/projects/${projectKey}/users`),
}

// Knowledge Base API
export interface KnowledgeDocument {
  id: string
  title: string
  source: string
  sourceUrl: string | null
  mimeType: string | null
  status: 'PENDING' | 'PROCESSING' | 'INDEXED' | 'OUTDATED' | 'ERROR'
  chunkCount: number
  errorMessage: string | null
  projectId: string | null
  createdAt: string
  updatedAt: string
  lastIndexedAt: string | null
  lastSyncedAt: string | null
}

export interface KnowledgeStats {
  totalDocuments: number
  totalChunks: number
  bySource: Record<string, number>
  byStatus: Record<string, number>
  recentQueries: number
  lastUpdated: string | null
}

export interface KnowledgeSearchResult {
  documentId: string
  documentTitle: string
  content: string
  score: number
  metadata: Record<string, unknown>
}

// Vector Store Types
export interface VectorItem {
  type: string
  docId: string
  content: string
  name: string | null
  description: string | null
  updatedAt: string | null
  metadata: Record<string, unknown>
}

export interface VectorStats {
  system: Record<string, number>
  user: Record<string, number>
  total: number
}

export interface VectorDataResponse {
  system: VectorItem[]
  user: VectorItem[]
  stats: VectorStats
}

export const knowledgeApi = {
  // Get all documents
  getDocuments: (projectId?: string) => {
    const params = projectId ? `?projectId=${projectId}` : ''
    return fetchApi<KnowledgeDocument[]>(`/knowledge/documents${params}`)
  },

  // Get document by ID
  getDocument: (id: string) =>
    fetchApi<KnowledgeDocument>(`/knowledge/documents/${id}`),

  // Upload file
  uploadFile: async (file: File, title?: string, projectId?: string): Promise<KnowledgeDocument> => {
    const formData = new FormData()
    formData.append('file', file)
    if (title) formData.append('title', title)
    if (projectId) formData.append('projectId', projectId)

    const response = await fetch(`${API_BASE_V1}/knowledge/upload`, {
      method: 'POST',
      body: formData,
    })

    if (!response.ok) {
      throw new Error(`Upload failed: ${response.status}`)
    }

    return response.json()
  },

  // Fetch URL (자동으로 Figma URL 감지)
  fetchUrl: (url: string, title?: string, projectId?: string, autoSync = false) => {
    // Figma URL 자동 감지
    const sourceType = url.includes('figma.com') ? 'FIGMA' : undefined
    return fetchApi<KnowledgeDocument>('/knowledge/url', {
      method: 'POST',
      body: JSON.stringify({ url, title, projectId, autoSync, sourceType }),
    })
  },

  // Re-index document
  reindexDocument: (id: string) =>
    fetchApi<{ success: boolean; message: string }>(`/knowledge/documents/${id}/reindex`, {
      method: 'POST',
    }),

  // Delete document
  deleteDocument: (id: string) =>
    fetchApi<void>(`/knowledge/documents/${id}`, { method: 'DELETE' }),

  // Search knowledge
  search: (query: string, projectId?: string, topK = 5) => {
    const params = new URLSearchParams({ query, topK: topK.toString() })
    if (projectId) params.set('projectId', projectId)
    return fetchApi<KnowledgeSearchResult[]>(`/knowledge/search?${params}`)
  },

  // Get stats
  getStats: (projectId?: string) => {
    const params = projectId ? `?projectId=${projectId}` : ''
    return fetchApi<KnowledgeStats>(`/knowledge/stats${params}`)
  },

  // Trigger sync
  triggerSync: () =>
    fetchApi<{ success: boolean; syncedCount: number }>('/knowledge/sync', { method: 'POST' }),

  // Analyze image
  analyzeImage: async (file: File): Promise<{
    description: string
    extractedText: string | null
    uiComponents: Array<{ name: string; type: string; description: string | null; properties: Record<string, string> }>
    designSpecs: { colors: string[]; fonts: string[]; spacing: string[]; layout: string | null } | null
    functionalSpecs: string[]
  }> => {
    const formData = new FormData()
    formData.append('file', file)

    const response = await fetch(`${API_BASE_V1}/knowledge/analyze-image`, {
      method: 'POST',
      body: formData,
    })

    if (!response.ok) {
      throw new Error(`Image analysis failed: ${response.status}`)
    }

    return response.json()
  },

  // Vector Store APIs
  getVectorData: () =>
    fetchApi<VectorDataResponse>('/knowledge/vectors'),

  getVectorStats: () =>
    fetchApi<VectorStats>('/knowledge/vectors/stats'),

  // ==================== Figma API Spec (Design-Aware Code Review) ====================

  /**
   * Figma 분석 Job 시작 (비동기 배치 처리)
   * 전체 프레임을 백그라운드에서 분석하고 Job ID를 반환합니다.
   */
  startFigmaAnalysisJob: (
    figmaUrl: string,
    options?: {
      projectId?: string
      indexToKnowledgeBase?: boolean
    }
  ) =>
    fetchApi<FigmaAnalysisJob>('/knowledge/figma/extract-api-specs', {
      method: 'POST',
      body: JSON.stringify({
        figmaUrl,
        projectId: options?.projectId,
        indexToKnowledgeBase: options?.indexToKnowledgeBase ?? true,
      }),
    }),

  /**
   * Figma 분석 Job 상태 조회
   */
  getFigmaJob: (jobId: string) =>
    fetchApi<FigmaAnalysisJob>(`/knowledge/figma/jobs/${jobId}`),

  /**
   * Figma 분석 Job 목록 조회
   */
  listFigmaJobs: (limit = 20) =>
    fetchApi<FigmaAnalysisJob[]>(`/knowledge/figma/jobs?limit=${limit}`),

  /**
   * Figma 분석 Job 삭제
   */
  deleteFigmaJob: (jobId: string) =>
    fetchApi<void>(`/knowledge/figma/jobs/${jobId}`, { method: 'DELETE' }),

  /**
   * API 스펙 검색 (MR 리뷰용)
   */
  searchApiSpecs: (query: string, projectId?: string, topK = 5) => {
    const params = new URLSearchParams({ query, topK: topK.toString() })
    if (projectId) params.set('projectId', projectId)
    return fetchApi<ScreenApiSpec[]>(`/knowledge/figma/search-api-specs?${params}`)
  },
}

// ==================== Figma API Spec Types ====================

export type FigmaJobStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'

export interface FigmaAnalysisJob {
  id: string
  figmaUrl: string
  figmaFileKey: string
  fileName: string
  projectId: string | null
  status: FigmaJobStatus
  progress: {
    totalFrames: number
    analyzedFrames: number
    currentFrame: string | null
    percentage: number
  }
  result: FigmaApiSpecResult | null
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  completedAt: string | null
}

export interface FigmaApiSpecResult {
  fileName: string
  fileKey: string
  lastModified: string
  totalFrames: number
  screenSpecs: ScreenApiSpec[]
  comments: string[]
  stats: {
    totalApis: number
    totalValidations: number
    totalBusinessRules: number
    analyzedFrames: number
    skippedFrames: number
  }
  processingTimeMs: number
}

export interface ScreenApiSpec {
  screenId: string
  screenName: string
  imageUrl: string | null
  figmaFileKey: string
  projectId: string | null
  apis: ApiEndpointSpec[]
  businessRules: string[]
  validations: ValidationRule[]
  uiStates: string[]
  relatedScreens: string[]
  analyzedAt: string
}

export interface ApiEndpointSpec {
  method: string
  path: string
  description: string
  requestFields: FieldSpec[]
  responseFields: FieldSpec[]
  errorCases: ErrorCase[]
  authRequired: boolean
  notes: string[]
}

export interface FieldSpec {
  name: string
  type: string
  required: boolean
  description: string | null
  validations: string[]
  example: string | null
}

export interface ValidationRule {
  field: string
  rules: string[]
  errorMessage: string | null
}

export interface ErrorCase {
  code: number
  errorCode: string | null
  condition: string
  message: string | null
}

// Extended Analytics API (Verified Feedback)
export const verifiedFeedbackApi = {
  // Get verified feedback stats
  getStats: (days = 7) =>
    fetchApi<VerifiedFeedbackStats>(`/analytics/feedback/verified?days=${days}`),

  // Get feedback by category
  getByCategory: (days = 7) =>
    fetchApi<FeedbackByCategory[]>(`/analytics/feedback/categories?days=${days}`),

  // Get extended feedback stats (combines basic + verified)
  getExtended: async (days = 7) => {
    const [basic, verified, byCategory] = await Promise.all([
      analyticsApi.getFeedback(`${days}d`),
      verifiedFeedbackApi.getStats(days).catch(() => ({
        totalFeedback: 0,
        verifiedFeedback: 0,
        verifiedPositive: 0,
        verifiedNegative: 0,
        verificationRate: 0,
        satisfactionRate: 0,
      })),
      verifiedFeedbackApi.getByCategory(days).catch(() => []),
    ])
    return { basic, verified, byCategory }
  },
}
