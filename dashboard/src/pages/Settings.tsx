import { useState, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Settings as SettingsIcon,
  Save,
  Eye,
  EyeOff,
  CheckCircle,
  AlertCircle,
  Loader2,
  RefreshCw,
  AlertTriangle,
  FileText,
  ChevronDown,
  ChevronRight,
} from 'lucide-react'
import { Card, CardHeader } from '@/components/Card'
import { systemApi, healthApi, type EnvVariable, type EnvVarSchema, type EnvGroup } from '@/lib/api'
import { cn } from '@/lib/utils'

export function Settings() {
  const queryClient = useQueryClient()
  const [saveMessage, setSaveMessage] = useState<{ type: 'success' | 'error' | 'warning'; text: string } | null>(null)
  const [editedValues, setEditedValues] = useState<Record<string, string>>({})
  const [showSensitive, setShowSensitive] = useState<Record<string, boolean>>({})
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({ slack: true })

  // Fetch environment schema
  const { data: schemaData } = useQuery({
    queryKey: ['envSchema'],
    queryFn: systemApi.getEnvSchema,
  })

  // Fetch current environment config
  const { data: envData, isLoading: envLoading } = useQuery({
    queryKey: ['envConfig'],
    queryFn: systemApi.getEnvConfig,
  })

  // Fetch health status
  const { data: health } = useQuery({
    queryKey: ['health'],
    queryFn: healthApi.check,
    refetchInterval: 30000,
  })

  // Initialize edited values from loaded config
  useEffect(() => {
    if (envData?.variables) {
      const values: Record<string, string> = {}
      for (const v of envData.variables) {
        values[v.key] = v.value
      }
      setEditedValues(values)
    }
  }, [envData])

  // Save mutation
  const saveMutation = useMutation({
    mutationFn: async () => {
      const variables: EnvVariable[] = Object.entries(editedValues)
        .filter(([, value]) => value.trim() !== '')
        .map(([key, value]) => ({ key, value }))
      return systemApi.saveEnvConfig(variables)
    },
    onSuccess: (result) => {
      if (result.success) {
        queryClient.invalidateQueries({ queryKey: ['envConfig'] })
        setSaveMessage({ type: 'warning', text: 'Saved! Restart services to apply changes.' })
        setTimeout(() => setSaveMessage(null), 5000)
      } else {
        setSaveMessage({ type: 'error', text: result.message })
      }
    },
    onError: () => {
      setSaveMessage({ type: 'error', text: 'Failed to save settings' })
    },
  })

  // Check if there are unsaved changes
  const hasChanges = () => {
    if (!envData?.variables) return Object.keys(editedValues).length > 0
    const originalValues: Record<string, string> = {}
    for (const v of envData.variables) {
      originalValues[v.key] = v.value
    }
    for (const [key, value] of Object.entries(editedValues)) {
      if ((originalValues[key] || '') !== value) return true
    }
    return false
  }

  // Group schema by group
  const groupedSchema = schemaData?.schema.reduce((acc, item) => {
    if (!acc[item.group]) acc[item.group] = []
    acc[item.group].push(item)
    return acc
  }, {} as Record<string, EnvVarSchema[]>) || {}

  const groups = schemaData?.groups || []

  if (envLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Toast Message */}
      {saveMessage && (
        <div className={cn(
          "fixed top-4 right-4 z-50 px-4 py-3 rounded-lg shadow-lg flex items-center gap-2 text-sm max-w-md",
          saveMessage.type === 'success' && "bg-green-500 text-white",
          saveMessage.type === 'error' && "bg-red-500 text-white",
          saveMessage.type === 'warning' && "bg-yellow-500 text-white"
        )}>
          {saveMessage.type === 'success' && <CheckCircle className="h-4 w-4 shrink-0" />}
          {saveMessage.type === 'error' && <AlertCircle className="h-4 w-4 shrink-0" />}
          {saveMessage.type === 'warning' && <AlertTriangle className="h-4 w-4 shrink-0" />}
          {saveMessage.text}
        </div>
      )}

      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold flex items-center gap-3">
            <SettingsIcon className="h-8 w-8" />
            Settings
          </h1>
          <p className="text-muted-foreground mt-1">
            Configure Claude Flow environment settings
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => queryClient.invalidateQueries({ queryKey: ['envConfig'] })}
            className="p-2 hover:bg-muted rounded-lg transition-colors"
            title="Refresh"
          >
            <RefreshCw className="h-4 w-4" />
          </button>
          <button
            onClick={() => saveMutation.mutate()}
            disabled={saveMutation.isPending || !hasChanges()}
            className={cn(
              "flex items-center gap-2 px-4 py-2 rounded-lg transition-colors",
              hasChanges()
                ? "bg-primary text-primary-foreground hover:bg-primary/90"
                : "bg-muted text-muted-foreground cursor-not-allowed"
            )}
          >
            {saveMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Save className="h-4 w-4" />
            )}
            Save Changes
          </button>
        </div>
      </div>

      {/* System Status */}
      <Card>
        <CardHeader
          title="System Status"
          description="Current system health"
        />
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="p-4 rounded-lg bg-muted/50">
            <div className="flex items-center gap-2 mb-2">
              <div className={cn(
                "w-2 h-2 rounded-full",
                health?.status === 'UP' ? "bg-green-500" : "bg-red-500"
              )} />
              <span className="text-sm font-medium">API Server</span>
            </div>
            <p className="text-2xl font-bold">{health?.status || 'Unknown'}</p>
          </div>
          <div className="p-4 rounded-lg bg-muted/50">
            <p className="text-sm text-muted-foreground mb-2">Version</p>
            <p className="text-2xl font-bold font-mono">{health?.version || '-'}</p>
          </div>
          <div className="p-4 rounded-lg bg-muted/50">
            <p className="text-sm text-muted-foreground mb-2">Config File</p>
            <p className="text-sm font-mono truncate" title={envData?.path || ''}>
              {envData?.path ? envData.path.split('/').slice(-2).join('/') : 'Not found'}
            </p>
            {envData?.exists === false && (
              <p className="text-xs text-yellow-500 mt-1">Will be created on save</p>
            )}
          </div>
        </div>
      </Card>

      {/* Environment Configuration */}
      <Card>
        <CardHeader
          title="Environment Configuration"
          description="Edit environment variables directly"
          action={
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <FileText className="h-4 w-4" />
              <span>{envData?.path?.split('/').pop() || '.env'}</span>
            </div>
          }
        />

        {/* Configuration Groups */}
        <div className="space-y-4">
          {groups.map((group) => (
            <EnvGroupSection
              key={group.id}
              group={group}
              schema={groupedSchema[group.id] || []}
              values={editedValues}
              showSensitive={showSensitive}
              expanded={expandedGroups[group.id] ?? false}
              onToggleExpand={() => setExpandedGroups(prev => ({ ...prev, [group.id]: !prev[group.id] }))}
              onValueChange={(key, value) => setEditedValues(prev => ({ ...prev, [key]: value }))}
              onToggleSensitive={(key) => setShowSensitive(prev => ({ ...prev, [key]: !prev[key] }))}
            />
          ))}
        </div>

        {/* Help Text */}
        <div className="mt-6 pt-4 border-t border-border">
          <p className="text-xs text-muted-foreground">
            <AlertTriangle className="h-3 w-3 inline mr-1" />
            Changes require a service restart to take effect. Sensitive values are masked but stored securely.
          </p>
        </div>
      </Card>
    </div>
  )
}

// Environment Variable Group Section
function EnvGroupSection({
  group,
  schema,
  values,
  showSensitive,
  expanded,
  onToggleExpand,
  onValueChange,
  onToggleSensitive,
}: {
  group: EnvGroup
  schema: EnvVarSchema[]
  values: Record<string, string>
  showSensitive: Record<string, boolean>
  expanded: boolean
  onToggleExpand: () => void
  onValueChange: (key: string, value: string) => void
  onToggleSensitive: (key: string) => void
}) {
  const filledCount = schema.filter(s => values[s.key]?.trim()).length
  const missingRequired = schema.filter(s => s.required && !values[s.key]?.trim()).length

  return (
    <div className="rounded-lg border border-border overflow-hidden">
      {/* Group Header */}
      <button
        onClick={onToggleExpand}
        className="w-full flex items-center justify-between p-4 hover:bg-muted/50 transition-colors"
      >
        <div className="flex items-center gap-3">
          {expanded ? (
            <ChevronDown className="h-4 w-4 text-muted-foreground" />
          ) : (
            <ChevronRight className="h-4 w-4 text-muted-foreground" />
          )}
          <div>
            <div className="flex items-center gap-2">
              <span className="font-medium">{group.name}</span>
              {group.required && (
                <span className="text-[10px] px-1.5 py-0.5 bg-red-500/10 text-red-500 rounded">required</span>
              )}
            </div>
            <p className="text-xs text-muted-foreground text-left">{group.description}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {missingRequired > 0 && (
            <span className="text-xs px-2 py-1 bg-yellow-500/10 text-yellow-600 rounded-full">
              {missingRequired} missing
            </span>
          )}
          <span className="text-xs text-muted-foreground">
            {filledCount}/{schema.length} configured
          </span>
        </div>
      </button>

      {/* Group Content */}
      {expanded && (
        <div className="border-t border-border p-4 space-y-4 bg-muted/20">
          {schema.map((varSchema) => (
            <EnvVarInput
              key={varSchema.key}
              schema={varSchema}
              value={values[varSchema.key] || ''}
              showValue={!varSchema.sensitive || showSensitive[varSchema.key]}
              onChange={(value) => onValueChange(varSchema.key, value)}
              onToggleShow={() => onToggleSensitive(varSchema.key)}
            />
          ))}
        </div>
      )}
    </div>
  )
}

// Environment Variable Input
function EnvVarInput({
  schema,
  value,
  showValue,
  onChange,
  onToggleShow,
}: {
  schema: EnvVarSchema
  value: string
  showValue: boolean
  onChange: (value: string) => void
  onToggleShow: () => void
}) {
  return (
    <div>
      <div className="flex items-center justify-between mb-1.5">
        <label className="text-sm font-medium flex items-center gap-2">
          {schema.label}
          {schema.required && <span className="text-red-500">*</span>}
        </label>
        <code className="text-[10px] text-muted-foreground bg-muted px-1.5 py-0.5 rounded">
          {schema.key}
        </code>
      </div>
      <div className="flex items-center gap-2">
        <input
          type={schema.sensitive && !showValue ? 'password' : 'text'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={schema.placeholder || schema.description}
          className={cn(
            "flex-1 px-3 py-2 rounded-lg border bg-background text-sm",
            "focus:ring-2 focus:ring-primary/50 focus:border-primary",
            schema.required && !value.trim() && "border-yellow-500/50"
          )}
        />
        {schema.sensitive && (
          <button
            type="button"
            onClick={onToggleShow}
            className="p-2 hover:bg-muted rounded-lg transition-colors"
            title={showValue ? 'Hide value' : 'Show value'}
          >
            {showValue ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
          </button>
        )}
      </div>
      <p className="text-xs text-muted-foreground mt-1">{schema.description}</p>
    </div>
  )
}

export default Settings
