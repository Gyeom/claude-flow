// Dashboard Types

export interface DashboardStats {
  period: string
  overview: SummaryStats
  timeseries: TimeSeriesData[]
  models: ModelStats[]
  sources: SourceStats[]
  routing: RoutingStats[]
  topRequesters: RequesterStats[]
  feedback: FeedbackSummary
}

// : 백분위수 포함 통계
export interface SummaryStats {
  totalRequests: number
  successful: number
  failed: number
  successRate: number
  avgDurationMs: number
  p50DurationMs: number
  p90DurationMs: number
  p95DurationMs: number
  p99DurationMs: number
  totalCostUsd: number
  totalInputTokens: number
  totalOutputTokens: number
}

export interface TimeSeriesData {
  timestamp: string
  requests: number
  successful: number
  failed: number
  avgDurationMs: number
  totalTokens: number
}

export interface ModelStats {
  model: string
  requests: number
  avgDurationMs: number
  totalTokens: number
  successRate: number
  costUsd: number
}

export interface SourceStats {
  source: string
  requests: number
  successRate: number
}

export interface RoutingStats {
  method: string
  requests: number
  avgConfidence: number
  successRate: number
}

export interface RequesterStats {
  userId: string
  displayName: string | null
  requests: number
  successRate: number
  totalTokens: number
}

export interface FeedbackSummary {
  thumbsUp: number
  thumbsDown: number
  satisfactionScore: number
}

// Legacy compatibility (기존 대시보드와 호환)
export interface LegacyDashboardStats {
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

// User Context Types
export interface UserContext {
  userId: string
  displayName: string | null
  preferredLanguage: string
  domain: string | null
  totalInteractions: number
  totalChars: number
  lastSeen: string
  summary: string | null
  summaryUpdatedAt: string | null
}

export interface UserContextResponse {
  rules: string[]
  summary: string | null
  recentConversations: RecentConversation[]
  totalConversationCount: number
  needsSummary: boolean
  summaryLocked: boolean
  lockId: string | null
}

export interface RecentConversation {
  userMessage: string
  response: string | null
  timestamp: string
}

// Error Stats
export interface ErrorStats {
  errorType: string
  count: number
  lastOccurred: string
}

// Clarification Types (프로젝트 선택 등)
export interface ClarificationOption {
  id: string
  label: string
  description?: string
  icon?: string
}

export interface ClarificationRequest {
  type: 'project_selection' | 'confirmation' | 'options'
  question: string
  options: ClarificationOption[]
  context?: Record<string, unknown>
}

// Project Types (멀티테넌시)
export interface Project {
  id: string
  name: string
  description: string | null
  workingDirectory: string
  gitRemote: string | null
  defaultBranch: string
  isDefault: boolean
  createdAt: string | null
  updatedAt: string | null
  // Optional fields (서버에서 기본값 처리)
  enableUserContext?: boolean
  classifyModel?: string
  classifyTimeout?: number
  rateLimitRpm?: number
  allowedTools?: string[]
  disallowedTools?: string[]
  fallbackAgentId?: string
}

// 프로젝트 생성/수정 요청용 타입
export interface ProjectInput {
  id: string
  name: string
  description?: string
  workingDirectory: string
  gitRemote?: string
  defaultBranch?: string
  isDefault?: boolean
}

export interface ProjectStats {
  projectId: string
  projectName: string
  totalExecutions: number
  uniqueUsers: number
  agentCount: number
  totalCost: number
  avgDurationMs: number
}

export interface ChannelMapping {
  channel: string
  projectId: string
}

// Verified Feedback Types
export interface VerifiedFeedbackStats {
  totalFeedback: number
  verifiedFeedback: number
  verifiedPositive: number
  verifiedNegative: number
  verificationRate: number
  satisfactionRate: number
}

export interface FeedbackByCategory {
  category: string
  count: number
  verifiedCount: number
}

export interface ExtendedFeedbackStats {
  basic: FeedbackAnalysis
  verified: VerifiedFeedbackStats
  byCategory: FeedbackByCategory[]
}

// GitLab Review Types
export interface GitLabReviewRecord {
  id: string
  projectId: string
  mrIid: number
  noteId: number
  discussionId: string | null
  reviewContent: string
  mrContext: string | null
  createdAt: string
  feedback?: GitLabFeedbackRecord[]
}

export interface GitLabFeedbackRecord {
  id: string
  noteId: number
  reaction: string
  userId: string
  source: string
  comment: string | null
  createdAt: string
}

export interface GitLabFeedbackStats {
  positive: number
  negative: number
  satisfactionRate: number
  total: number
}

export interface GitLabProject {
  id: string
  name: string
  gitlabPath: string
  defaultBranch: string
}
