import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Settings as SettingsIcon,
  FolderGit2,
  Plus,
  Trash2,
  Save,
  TestTube,
  CheckCircle,
  AlertCircle,
  Tag,
  Edit2,
  Loader2,
} from 'lucide-react'
import { Card, CardHeader } from '@/components/Card'
import { settingsApi, type ProjectAlias } from '@/lib/api'
import { cn } from '@/lib/utils'

export function Settings() {
  const [activeTab, setActiveTab] = useState<'aliases' | 'general'>('aliases')

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-3xl font-bold flex items-center gap-3">
          <SettingsIcon className="h-8 w-8" />
          Settings
        </h1>
        <p className="text-muted-foreground mt-1">
          Configure Claude Flow behavior and integrations
        </p>
      </div>

      {/* Tabs */}
      <div className="flex gap-2 border-b border-border">
        <button
          onClick={() => setActiveTab('aliases')}
          className={cn(
            "px-4 py-2 text-sm font-medium border-b-2 transition-colors",
            activeTab === 'aliases'
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          )}
        >
          <FolderGit2 className="h-4 w-4 inline mr-2" />
          Project Aliases
        </button>
        <button
          onClick={() => setActiveTab('general')}
          className={cn(
            "px-4 py-2 text-sm font-medium border-b-2 transition-colors",
            activeTab === 'general'
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          )}
        >
          <SettingsIcon className="h-4 w-4 inline mr-2" />
          General
        </button>
      </div>

      {/* Tab Content */}
      {activeTab === 'aliases' && <ProjectAliasesTab />}
      {activeTab === 'general' && <GeneralTab />}
    </div>
  )
}

function ProjectAliasesTab() {
  const queryClient = useQueryClient()
  const [showAddForm, setShowAddForm] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [testText, setTestText] = useState('')
  const [testResult, setTestResult] = useState<{ projectId: string; matchedPattern: string; description: string | null }[] | null>(null)
  const [saveMessage, setSaveMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)

  // Fetch config
  const { data: config, isLoading, error } = useQuery({
    queryKey: ['projectAliases'],
    queryFn: settingsApi.getProjectAliases,
    retry: 1,
  })

  // Save mutation
  const saveMutation = useMutation({
    mutationFn: (data: { projectId: string; alias: ProjectAlias }) =>
      settingsApi.upsertProjectAlias(data.projectId, data.alias),
    onSuccess: (result) => {
      if (result.success) {
        queryClient.invalidateQueries({ queryKey: ['projectAliases'] })
        setEditingId(null)
        setShowAddForm(false)
        setSaveMessage({ type: 'success', text: 'Saved successfully!' })
        setTimeout(() => setSaveMessage(null), 3000)
      } else {
        setSaveMessage({ type: 'error', text: result.message })
      }
    },
    onError: () => {
      setSaveMessage({ type: 'error', text: 'Failed to save' })
    },
  })

  // Delete mutation
  const deleteMutation = useMutation({
    mutationFn: settingsApi.deleteProjectAlias,
    onSuccess: (result) => {
      if (result.success) {
        queryClient.invalidateQueries({ queryKey: ['projectAliases'] })
        setSaveMessage({ type: 'success', text: 'Deleted successfully!' })
        setTimeout(() => setSaveMessage(null), 3000)
      }
    },
  })

  // Test mutation
  const testMutation = useMutation({
    mutationFn: settingsApi.testProjectAliases,
    onSuccess: (data) => {
      setTestResult(data.detected)
    },
  })

  const handleTest = () => {
    if (testText.trim()) {
      testMutation.mutate(testText)
    }
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 className="h-8 w-8 animate-spin text-primary" />
      </div>
    )
  }

  if (error) {
    return (
      <Card className="border-destructive">
        <div className="p-8 text-center">
          <AlertCircle className="h-12 w-12 mx-auto mb-4 text-destructive" />
          <p className="text-destructive font-medium">Failed to load settings</p>
          <p className="text-sm text-muted-foreground mt-1">Make sure the backend server is running</p>
        </div>
      </Card>
    )
  }

  const aliases = config?.aliases || {}
  const aliasEntries = Object.entries(aliases)

  return (
    <div className="space-y-6">
      {/* Save Message Toast */}
      {saveMessage && (
        <div className={cn(
          "fixed top-4 right-4 z-50 px-4 py-3 rounded-lg shadow-lg flex items-center gap-2",
          saveMessage.type === 'success' ? "bg-green-500 text-white" : "bg-destructive text-white"
        )}>
          {saveMessage.type === 'success' ? <CheckCircle className="h-5 w-5" /> : <AlertCircle className="h-5 w-5" />}
          {saveMessage.text}
        </div>
      )}

      {/* Info Card */}
      <Card>
        <CardHeader
          title="Project Aliases"
          description="프로젝트 별칭을 설정하면 사용자가 '인가 서버', 'auth 서버' 등으로 언급할 때 자동으로 해당 프로젝트의 컨텍스트가 주입됩니다."
        />
      </Card>

      {/* Add New Button */}
      {!showAddForm && (
        <button
          onClick={() => setShowAddForm(true)}
          className="w-full p-4 border-2 border-dashed border-border rounded-lg hover:border-primary hover:bg-primary/5 transition-colors flex items-center justify-center gap-2 text-muted-foreground hover:text-primary"
        >
          <Plus className="h-5 w-5" />
          Add New Project Alias
        </button>
      )}

      {/* Add Form */}
      {showAddForm && (
        <AliasForm
          onSave={(projectId, alias) => saveMutation.mutate({ projectId, alias })}
          onCancel={() => setShowAddForm(false)}
          isSaving={saveMutation.isPending}
        />
      )}

      {/* Aliases List */}
      <div className="space-y-3">
        {aliasEntries.map(([projectId, alias]) => (
          editingId === projectId ? (
            <AliasForm
              key={projectId}
              initialProjectId={projectId}
              initialPatterns={alias.patterns}
              initialDescription={alias.description}
              isEditing
              onSave={(_, updatedAlias) => saveMutation.mutate({ projectId, alias: updatedAlias })}
              onCancel={() => setEditingId(null)}
              isSaving={saveMutation.isPending}
            />
          ) : (
            <AliasCard
              key={projectId}
              projectId={projectId}
              alias={alias}
              onEdit={() => setEditingId(projectId)}
              onDelete={() => {
                if (confirm(`Delete "${projectId}"?`)) {
                  deleteMutation.mutate(projectId)
                }
              }}
              isDeleting={deleteMutation.isPending}
            />
          )
        ))}

        {aliasEntries.length === 0 && !showAddForm && (
          <div className="text-center py-12 text-muted-foreground">
            <FolderGit2 className="h-16 w-16 mx-auto mb-4 opacity-30" />
            <p className="font-medium">No project aliases configured</p>
            <p className="text-sm mt-1">Click "Add New Project Alias" to get started</p>
          </div>
        )}
      </div>

      {/* Test Section */}
      <Card>
        <CardHeader
          title="Test Detection"
          description="텍스트를 입력하여 어떤 프로젝트가 탐지되는지 테스트합니다"
        />
        <div className="flex gap-3">
          <input
            type="text"
            value={testText}
            onChange={(e) => setTestText(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleTest()}
            placeholder="예: 인가 서버 코드 리뷰해줘"
            className="flex-1 px-4 py-3 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50 focus:border-primary"
          />
          <button
            onClick={handleTest}
            disabled={!testText.trim() || testMutation.isPending}
            className="px-6 py-3 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 disabled:opacity-50 flex items-center gap-2 font-medium"
          >
            {testMutation.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <TestTube className="h-4 w-4" />}
            Test
          </button>
        </div>

        {testResult !== null && (
          <div className="mt-4 p-4 rounded-lg bg-muted/50">
            {testResult.length > 0 ? (
              <div className="space-y-2">
                <p className="text-sm font-medium text-green-600 dark:text-green-400 flex items-center gap-2">
                  <CheckCircle className="h-4 w-4" />
                  {testResult.length}개 프로젝트 탐지됨
                </p>
                {testResult.map((r, i) => (
                  <div key={i} className="flex items-center gap-3 p-3 rounded-lg bg-background border border-border">
                    <Tag className="h-4 w-4 text-primary" />
                    <span className="font-mono font-medium">{r.projectId}</span>
                    <span className="text-sm text-muted-foreground">
                      → "{r.matchedPattern}" 매칭
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground flex items-center gap-2">
                <AlertCircle className="h-4 w-4" />
                탐지된 프로젝트가 없습니다
              </p>
            )}
          </div>
        )}
      </Card>
    </div>
  )
}

function AliasCard({
  projectId,
  alias,
  onEdit,
  onDelete,
  isDeleting,
}: {
  projectId: string
  alias: ProjectAlias
  onEdit: () => void
  onDelete: () => void
  isDeleting: boolean
}) {
  return (
    <Card className="hover:shadow-md transition-shadow">
      <div className="flex items-start gap-4">
        <div className="p-3 rounded-lg bg-primary/10 shrink-0">
          <FolderGit2 className="h-6 w-6 text-primary" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <h3 className="font-semibold text-lg">{projectId}</h3>
          </div>
          <p className="text-sm text-muted-foreground mt-1">
            {alias.description || 'No description'}
          </p>
          <div className="flex flex-wrap gap-2 mt-3">
            {alias.patterns.map((p, i) => (
              <span key={i} className="px-3 py-1 text-sm rounded-full bg-muted font-medium">
                {p}
              </span>
            ))}
          </div>
        </div>
        <div className="flex gap-2 shrink-0">
          <button
            onClick={onEdit}
            className="p-2 rounded-lg hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
            title="Edit"
          >
            <Edit2 className="h-4 w-4" />
          </button>
          <button
            onClick={onDelete}
            disabled={isDeleting}
            className="p-2 rounded-lg hover:bg-destructive/10 transition-colors text-muted-foreground hover:text-destructive disabled:opacity-50"
            title="Delete"
          >
            {isDeleting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
          </button>
        </div>
      </div>
    </Card>
  )
}

function AliasForm({
  initialProjectId = '',
  initialPatterns = [],
  initialDescription = '',
  isEditing = false,
  onSave,
  onCancel,
  isSaving,
}: {
  initialProjectId?: string
  initialPatterns?: string[]
  initialDescription?: string
  isEditing?: boolean
  onSave: (projectId: string, alias: ProjectAlias) => void
  onCancel: () => void
  isSaving: boolean
}) {
  const [projectId, setProjectId] = useState(initialProjectId)
  const [patterns, setPatterns] = useState(initialPatterns.join(', '))
  const [description, setDescription] = useState(initialDescription)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!projectId.trim() || !patterns.trim()) return

    onSave(projectId.trim(), {
      patterns: patterns.split(',').map(p => p.trim()).filter(Boolean),
      description: description.trim(),
    })
  }

  return (
    <Card className="border-2 border-primary/30 bg-primary/5">
      <form onSubmit={handleSubmit}>
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium mb-2">
              Project ID <span className="text-destructive">*</span>
            </label>
            <input
              type="text"
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              disabled={isEditing}
              placeholder="e.g., authorization-server"
              className={cn(
                "w-full px-4 py-2.5 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50",
                isEditing && "bg-muted cursor-not-allowed"
              )}
            />
            <p className="text-xs text-muted-foreground mt-1">실제 프로젝트 디렉토리 이름과 일치해야 합니다</p>
          </div>

          <div>
            <label className="block text-sm font-medium mb-2">
              Patterns <span className="text-destructive">*</span>
            </label>
            <input
              type="text"
              value={patterns}
              onChange={(e) => setPatterns(e.target.value)}
              placeholder="e.g., 인가, 인가서버, auth서버, authorization"
              className="w-full px-4 py-2.5 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50"
            />
            <p className="text-xs text-muted-foreground mt-1">쉼표로 구분하여 여러 패턴을 입력하세요</p>
          </div>

          <div>
            <label className="block text-sm font-medium mb-2">Description</label>
            <input
              type="text"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="e.g., 권한 관리 서버"
              className="w-full px-4 py-2.5 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50"
            />
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onCancel}
              className="px-4 py-2 rounded-lg border border-border hover:bg-muted transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSaving || !projectId.trim() || !patterns.trim()}
              className="px-6 py-2 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 disabled:opacity-50 flex items-center gap-2 font-medium"
            >
              {isSaving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
              {isEditing ? 'Update' : 'Add'}
            </button>
          </div>
        </div>
      </form>
    </Card>
  )
}

function GeneralTab() {
  return (
    <Card>
      <CardHeader
        title="General Settings"
        description="General configuration options"
      />
      <div className="text-center py-12 text-muted-foreground">
        <SettingsIcon className="h-12 w-12 mx-auto mb-3 opacity-50" />
        <p>No general settings available yet</p>
        <p className="text-sm">More settings will be added in future updates</p>
      </div>
    </Card>
  )
}

export default Settings
