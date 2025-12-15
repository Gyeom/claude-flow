import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Users as UsersIcon,
  User,
  Search,
  MessageSquare,
  Clock,
  Settings,
  Plus,
  Trash2,
  Edit,
  ChevronDown,
  ChevronUp,
  AlertCircle,
  RefreshCw,
} from 'lucide-react'
import { toast } from 'sonner'
import { Card, StatCard } from '@/components/Card'
import { usersApi } from '@/lib/api'
import { cn } from '@/lib/utils'

export function Users() {
  const [searchQuery, setSearchQuery] = useState('')
  const [expandedUser, setExpandedUser] = useState<string | null>(null)
  const [newRule, setNewRule] = useState('')
  const queryClient = useQueryClient()

  const { data: users, isLoading, error } = useQuery({
    queryKey: ['users'],
    queryFn: usersApi.getAll,
  })

  // Fetch rules when user is expanded
  const { data: rulesData } = useQuery({
    queryKey: ['userRules', expandedUser],
    queryFn: () => expandedUser ? usersApi.getRules(expandedUser) : null,
    enabled: !!expandedUser,
  })

  // Add rule mutation
  const addRuleMutation = useMutation({
    mutationFn: ({ userId, rule }: { userId: string; rule: string }) =>
      usersApi.addRule(userId, rule),
    onSuccess: (_, { userId }) => {
      queryClient.invalidateQueries({ queryKey: ['userRules', userId] })
      setNewRule('')
      toast.success('Rule added')
    },
    onError: () => {
      toast.error('Failed to add rule')
    },
  })

  // Delete rule mutation
  const deleteRuleMutation = useMutation({
    mutationFn: ({ userId, rule }: { userId: string; rule: string }) =>
      usersApi.deleteRule(userId, rule),
    onSuccess: (_, { userId }) => {
      queryClient.invalidateQueries({ queryKey: ['userRules', userId] })
      toast.success('Rule deleted')
    },
    onError: () => {
      toast.error('Failed to delete rule')
    },
  })

  // Use API data directly
  const userList = users || []

  const filteredUsers = userList.filter(user => {
    if (!searchQuery) return true
    return (
      user.userId.toLowerCase().includes(searchQuery.toLowerCase()) ||
      user.displayName?.toLowerCase().includes(searchQuery.toLowerCase())
    )
  })

  const formatTimeAgo = (isoString: string) => {
    const diff = Date.now() - new Date(isoString).getTime()
    const minutes = Math.floor(diff / 60000)
    const hours = Math.floor(diff / 3600000)
    const days = Math.floor(diff / 86400000)
    if (days > 0) return `${days}d ago`
    if (hours > 0) return `${hours}h ago`
    return `${minutes}m ago`
  }

  const totalInteractions = userList.reduce((sum, u) => sum + u.totalInteractions, 0)
  const activeUsers = userList.filter(u => {
    const diff = Date.now() - new Date(u.lastSeen).getTime()
    return diff < 86400000 // Active in last 24h
  }).length

  // Get rules for expanded user
  const currentUserRules = rulesData?.rules || []

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-4">
        <AlertCircle className="h-12 w-12 text-destructive" />
        <p className="text-muted-foreground">Failed to load users</p>
        <button
          onClick={() => queryClient.invalidateQueries({ queryKey: ['users'] })}
          className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground"
        >
          <RefreshCw className="h-4 w-4" />
          Retry
        </button>
      </div>
    )
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    )
  }

  return (
    <div className="space-y-8">
      {/* Header */}
      <div>
        <h1 className="text-3xl font-bold">User Management</h1>
        <p className="text-muted-foreground mt-1">
          Manage user contexts, rules, and preferences
        </p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <StatCard
          title="Total Users"
          value={userList.length}
          icon={<UsersIcon className="h-6 w-6" />}
        />
        <StatCard
          title="Active (24h)"
          value={activeUsers}
          icon={<Clock className="h-6 w-6 text-green-500" />}
          className="border-green-500/30"
        />
        <StatCard
          title="Total Interactions"
          value={totalInteractions.toLocaleString()}
          icon={<MessageSquare className="h-6 w-6" />}
        />
        <StatCard
          title="With Summary"
          value={userList.filter(u => u.summary).length}
          icon={<Edit className="h-6 w-6" />}
        />
      </div>

      {/* Search */}
      <Card className="p-4">
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search users by ID or name..."
            className="w-full pl-10 pr-4 py-2 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50"
          />
        </div>
      </Card>

      {/* User List */}
      <div className="space-y-4">
        {filteredUsers.length === 0 ? (
          <Card className="p-12 text-center">
            <UsersIcon className="h-12 w-12 text-muted-foreground mx-auto mb-4" />
            <p className="text-muted-foreground">
              {searchQuery ? 'No users found matching your search' : 'No users found'}
            </p>
          </Card>
        ) : (
          filteredUsers.map((user) => (
            <Card key={user.userId} className="p-0 overflow-hidden">
              {/* Summary Row */}
              <div
                className="flex items-center gap-4 p-4 cursor-pointer hover:bg-muted/50 transition-colors"
                onClick={() => setExpandedUser(expandedUser === user.userId ? null : user.userId)}
              >
                <div className="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center">
                  <User className="h-5 w-5 text-primary" />
                </div>

                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <h3 className="font-semibold">{user.displayName || user.userId}</h3>
                    <span className="text-xs text-muted-foreground">({user.userId})</span>
                  </div>
                  <div className="flex items-center gap-4 mt-1 text-sm text-muted-foreground">
                    <span>{user.totalInteractions} interactions</span>
                    <span>{user.preferredLanguage.toUpperCase()}</span>
                    {user.domain && <span className="px-2 py-0.5 rounded bg-muted">{user.domain}</span>}
                  </div>
                </div>

                <div className="flex items-center gap-4 flex-shrink-0">
                  <div className="text-right">
                    <p className="text-sm text-muted-foreground">Last seen</p>
                    <p className="text-sm font-medium">{formatTimeAgo(user.lastSeen)}</p>
                  </div>
                  {expandedUser === user.userId ? (
                    <ChevronUp className="h-4 w-4 text-muted-foreground" />
                  ) : (
                    <ChevronDown className="h-4 w-4 text-muted-foreground" />
                  )}
                </div>
              </div>

              {/* Expanded Details */}
              {expandedUser === user.userId && (
                <div className="border-t border-border p-4 bg-muted/30 space-y-6">
                  {/* Summary */}
                  <div>
                    <h4 className="text-sm font-medium mb-2 flex items-center gap-2">
                      <Edit className="h-4 w-4" />
                      User Summary
                    </h4>
                    {user.summary ? (
                      <div className="p-3 rounded-lg bg-background border border-border">
                        <p className="text-sm">{user.summary}</p>
                        {user.summaryUpdatedAt && (
                          <p className="text-xs text-muted-foreground mt-2">
                            Updated {formatTimeAgo(user.summaryUpdatedAt)}
                          </p>
                        )}
                      </div>
                    ) : (
                      <div className="p-3 rounded-lg bg-muted/50 text-center">
                        <p className="text-sm text-muted-foreground">No summary generated yet</p>
                        <button className="mt-2 text-sm text-primary hover:underline">
                          Generate Summary
                        </button>
                      </div>
                    )}
                  </div>

                  {/* Rules */}
                  <div>
                    <h4 className="text-sm font-medium mb-2 flex items-center gap-2">
                      <Settings className="h-4 w-4" />
                      User Rules
                    </h4>
                    <div className="space-y-2">
                      {currentUserRules.map((rule, index) => (
                        <div
                          key={index}
                          className="flex items-center justify-between p-3 rounded-lg bg-background border border-border"
                        >
                          <p className="text-sm">{rule}</p>
                          <button
                            onClick={() => deleteRuleMutation.mutate({ userId: user.userId, rule })}
                            disabled={deleteRuleMutation.isPending}
                            className="p-1 text-red-500 hover:bg-red-500/10 rounded"
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </div>
                      ))}
                      {currentUserRules.length === 0 && (
                        <p className="text-sm text-muted-foreground text-center py-2">
                          No rules configured
                        </p>
                      )}
                    </div>

                    {/* Add Rule */}
                    <div className="flex gap-2 mt-3">
                      <input
                        type="text"
                        value={newRule}
                        onChange={(e) => setNewRule(e.target.value)}
                        placeholder="Add a new rule..."
                        className="flex-1 px-3 py-2 rounded-lg border border-border bg-background text-sm focus:ring-2 focus:ring-primary/50"
                      />
                      <button
                        disabled={!newRule.trim() || addRuleMutation.isPending}
                        onClick={() => addRuleMutation.mutate({ userId: user.userId, rule: newRule })}
                        className={cn(
                          "flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors",
                          "bg-primary text-primary-foreground hover:bg-primary/90",
                          "disabled:opacity-50 disabled:cursor-not-allowed"
                        )}
                      >
                        <Plus className="h-4 w-4" />
                        Add
                      </button>
                    </div>
                  </div>

                  {/* Stats */}
                  <div className="grid grid-cols-3 gap-4">
                    <div className="text-center p-3 rounded-lg bg-background border border-border">
                      <p className="text-2xl font-bold">{user.totalInteractions}</p>
                      <p className="text-xs text-muted-foreground">Interactions</p>
                    </div>
                    <div className="text-center p-3 rounded-lg bg-background border border-border">
                      <p className="text-2xl font-bold">{Math.round(user.totalChars / 1000)}K</p>
                      <p className="text-xs text-muted-foreground">Characters</p>
                    </div>
                    <div className="text-center p-3 rounded-lg bg-background border border-border">
                      <p className="text-2xl font-bold">{currentUserRules.length}</p>
                      <p className="text-xs text-muted-foreground">Rules</p>
                    </div>
                  </div>
                </div>
              )}
            </Card>
          ))
        )}
      </div>
    </div>
  )
}
