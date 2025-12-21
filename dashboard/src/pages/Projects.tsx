import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  FolderOpen,
  Plus,
  Star,
  Trash2,
  X,
  Check,
  Tag,
  TestTube,
  CheckCircle,
  AlertCircle,
  Loader2,
  Save,
  Edit3,
  GitBranch,
} from 'lucide-react'
import { Card, CardHeader, StatCard } from '@/components/Card'
import { projectsApi, settingsApi, type ProjectAlias } from '@/lib/api'
import { cn } from '@/lib/utils'
import type { Project } from '@/types'

export function Projects() {
  const queryClient = useQueryClient()
  const [selectedProject, setSelectedProject] = useState<string | null>(null)
  const [showCreateModal, setShowCreateModal] = useState(false)
  const [showEditModal, setShowEditModal] = useState(false)

  // Fetch projects
  const { data: projects, isLoading } = useQuery({
    queryKey: ['projects'],
    queryFn: () => projectsApi.getAll(),
  })

  // Fetch selected project details
  const { data: projectStats } = useQuery({
    queryKey: ['projects', selectedProject, 'stats'],
    queryFn: () => projectsApi.getStats(selectedProject!),
    enabled: !!selectedProject,
  })

  // Fetch project aliases config
  const { data: aliasConfig } = useQuery({
    queryKey: ['projectAliases'],
    queryFn: settingsApi.getProjectAliases,
  })

  // Mutations
  const setDefaultMutation = useMutation({
    mutationFn: (projectId: string) => projectsApi.setDefault(projectId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['projects'] }),
  })

  const deleteProjectMutation = useMutation({
    mutationFn: (projectId: string) => projectsApi.delete(projectId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects'] })
      setSelectedProject(null)
    },
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    )
  }

  const projectList = projects || []
  const selected = projectList.find(p => p.id === selectedProject)
  const selectedAlias = selectedProject && aliasConfig?.aliases ? aliasConfig.aliases[selectedProject] : null

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Projects</h1>
          <p className="text-muted-foreground mt-1">
            Manage multi-tenant projects, aliases, and channel mappings
          </p>
        </div>
        <button
          onClick={() => setShowCreateModal(true)}
          className="flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-colors"
        >
          <Plus className="h-4 w-4" />
          New Project
        </button>
      </div>

      {/* Overview Stats */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <StatCard
          title="Total Projects"
          value={projectList.length}
          icon={<FolderOpen className="h-6 w-6" />}
        />
        <StatCard
          title="Default Project"
          value={projectList.find(p => p.isDefault)?.name || '-'}
          icon={<Star className="h-6 w-6 text-yellow-500" />}
        />
        <StatCard
          title="Total Executions"
          value={projectStats?.totalExecutions || 0}
          icon={<GitBranch className="h-6 w-6" />}
        />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Project List */}
        <div className="lg:col-span-1">
          <Card>
            <CardHeader title="All Projects" />
            <div className="space-y-2">
              {projectList.map((project) => (
                <button
                  key={project.id}
                  onClick={() => setSelectedProject(project.id)}
                  className={cn(
                    "w-full p-4 rounded-lg text-left transition-colors",
                    selectedProject === project.id
                      ? "bg-primary/10 border border-primary/30"
                      : "bg-muted/50 hover:bg-muted"
                  )}
                >
                  <div className="flex items-start justify-between">
                    <div className="flex items-center gap-2">
                      <FolderOpen className="h-5 w-5 text-muted-foreground" />
                      <div>
                        <p className="font-medium">{project.name}</p>
                        <p className="text-xs text-muted-foreground">{project.id}</p>
                      </div>
                    </div>
                    {project.isDefault && (
                      <Star className="h-4 w-4 text-yellow-500 fill-yellow-500" />
                    )}
                  </div>
                  {project.description && (
                    <p className="text-sm text-muted-foreground mt-2 line-clamp-2">
                      {project.description}
                    </p>
                  )}
                </button>
              ))}
              {projectList.length === 0 && (
                <p className="text-center text-muted-foreground py-8">
                  No projects yet
                </p>
              )}
            </div>
          </Card>
        </div>

        {/* Project Details */}
        <div className="lg:col-span-2 space-y-6">
          {selected ? (
            <>
              {/* Project Info */}
              <Card>
                <CardHeader
                  title={selected.name}
                  description={selected.description || 'No description'}
                  action={
                    <div className="flex items-center gap-2">
                      <button
                        onClick={() => setShowEditModal(true)}
                        className="p-2 hover:bg-muted rounded-lg transition-colors"
                        title="Edit project"
                      >
                        <Edit3 className="h-4 w-4" />
                      </button>
                      {!selected.isDefault && (
                        <button
                          onClick={() => setDefaultMutation.mutate(selected.id)}
                          className="p-2 hover:bg-muted rounded-lg transition-colors"
                          title="Set as default"
                        >
                          <Star className="h-4 w-4" />
                        </button>
                      )}
                      <button
                        onClick={() => {
                          if (confirm('Delete this project?')) {
                            deleteProjectMutation.mutate(selected.id)
                          }
                        }}
                        className="p-2 hover:bg-red-500/10 text-red-500 rounded-lg transition-colors"
                        title="Delete project"
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </div>
                  }
                />
                <div className="grid grid-cols-2 gap-4 text-sm">
                  <div className="p-3 rounded-lg bg-muted/50">
                    <p className="text-muted-foreground">Working Directory</p>
                    <p className="font-mono text-xs mt-1 truncate">{selected.workingDirectory}</p>
                  </div>
                  <div className="p-3 rounded-lg bg-muted/50">
                    <p className="text-muted-foreground">Git Remote</p>
                    <p className="font-mono text-xs mt-1 truncate">{selected.gitRemote || '-'}</p>
                  </div>
                  <div className="p-3 rounded-lg bg-muted/50">
                    <p className="text-muted-foreground">Default Branch</p>
                    <p className="font-medium">{selected.defaultBranch}</p>
                  </div>
                  <div className="p-3 rounded-lg bg-muted/50">
                    <p className="text-muted-foreground">Classify Model</p>
                    <p className="font-medium">{selected.classifyModel}</p>
                  </div>
                </div>
              </Card>

              {/* Stats */}
              {projectStats && (
                <Card>
                  <CardHeader title="Statistics" />
                  <div className="grid grid-cols-3 gap-4">
                    <div className="p-3 rounded-lg bg-primary/5 text-center">
                      <p className="text-2xl font-bold">{projectStats.totalExecutions}</p>
                      <p className="text-xs text-muted-foreground">Executions</p>
                    </div>
                    <div className="p-3 rounded-lg bg-primary/5 text-center">
                      <p className="text-2xl font-bold">{projectStats.uniqueUsers}</p>
                      <p className="text-xs text-muted-foreground">Users</p>
                    </div>
                    <div className="p-3 rounded-lg bg-primary/5 text-center">
                      <p className="text-2xl font-bold">${projectStats.totalCost.toFixed(2)}</p>
                      <p className="text-xs text-muted-foreground">Total Cost</p>
                    </div>
                  </div>
                </Card>
              )}

              {/* Project Aliases */}
              <ProjectAliasSection
                projectId={selected.id}
                alias={selectedAlias}
                onUpdate={() => queryClient.invalidateQueries({ queryKey: ['projectAliases'] })}
              />
            </>
          ) : (
            <Card className="flex flex-col items-center justify-center py-16">
              <FolderOpen className="h-16 w-16 text-muted-foreground/30 mb-4" />
              <p className="text-muted-foreground">Select a project to view details</p>
            </Card>
          )}
        </div>
      </div>

      {/* Create Project Modal */}
      {showCreateModal && (
        <CreateProjectModal
          onClose={() => setShowCreateModal(false)}
          onSuccess={() => {
            setShowCreateModal(false)
            queryClient.invalidateQueries({ queryKey: ['projects'] })
          }}
        />
      )}

      {/* Edit Project Modal */}
      {showEditModal && selected && (
        <EditProjectModal
          project={selected}
          onClose={() => setShowEditModal(false)}
          onSuccess={() => {
            setShowEditModal(false)
            queryClient.invalidateQueries({ queryKey: ['projects'] })
          }}
        />
      )}
    </div>
  )
}

// Project Aliases Section Component
function ProjectAliasSection({
  projectId,
  alias,
  onUpdate,
}: {
  projectId: string
  alias: ProjectAlias | null
  onUpdate: () => void
}) {
  const [isEditing, setIsEditing] = useState(false)
  const [patterns, setPatterns] = useState(alias?.patterns.join(', ') || '')
  const [description, setDescription] = useState(alias?.description || '')
  const [testText, setTestText] = useState('')
  const [testResult, setTestResult] = useState<{ projectId: string; matchedPattern: string }[] | null>(null)
  const [saveMessage, setSaveMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)

  // Save mutation
  const saveMutation = useMutation({
    mutationFn: () => settingsApi.upsertProjectAlias(projectId, {
      patterns: patterns.split(',').map(p => p.trim()).filter(Boolean),
      description: description.trim(),
    }),
    onSuccess: (result) => {
      if (result.success) {
        onUpdate()
        setIsEditing(false)
        setSaveMessage({ type: 'success', text: 'Aliases saved!' })
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
    mutationFn: () => settingsApi.deleteProjectAlias(projectId),
    onSuccess: () => {
      onUpdate()
      setPatterns('')
      setDescription('')
      setSaveMessage({ type: 'success', text: 'Aliases deleted!' })
      setTimeout(() => setSaveMessage(null), 3000)
    },
  })

  // Test mutation
  const testMutation = useMutation({
    mutationFn: settingsApi.testProjectAliases,
    onSuccess: (data) => {
      setTestResult(data.detected)
    },
  })

  const handleSave = () => {
    if (patterns.trim()) {
      saveMutation.mutate()
    }
  }

  const hasAliases = alias && alias.patterns.length > 0

  return (
    <Card>
      {/* Toast Message */}
      {saveMessage && (
        <div className={cn(
          "absolute top-4 right-4 z-50 px-4 py-2 rounded-lg shadow-lg flex items-center gap-2 text-sm",
          saveMessage.type === 'success' ? "bg-green-500 text-white" : "bg-red-500 text-white"
        )}>
          {saveMessage.type === 'success' ? <CheckCircle className="h-4 w-4" /> : <AlertCircle className="h-4 w-4" />}
          {saveMessage.text}
        </div>
      )}

      <CardHeader
        title="Project Aliases"
        description="Keywords that auto-detect this project from messages (e.g., '인가서버', 'auth')"
        action={
          hasAliases && !isEditing ? (
            <div className="flex gap-2">
              <button
                onClick={() => setIsEditing(true)}
                className="px-3 py-1 text-sm border border-border rounded-lg hover:bg-muted transition-colors"
              >
                Edit
              </button>
              <button
                onClick={() => {
                  if (confirm('Delete all aliases for this project?')) {
                    deleteMutation.mutate()
                  }
                }}
                className="px-3 py-1 text-sm text-red-500 border border-red-500/30 rounded-lg hover:bg-red-500/10 transition-colors"
              >
                Delete
              </button>
            </div>
          ) : undefined
        }
      />

      {isEditing || !hasAliases ? (
        // Edit / Add Form
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium mb-2">
              Patterns <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={patterns}
              onChange={(e) => setPatterns(e.target.value)}
              placeholder="e.g., 인가, auth서버, authorization"
              className="w-full px-4 py-2.5 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50"
            />
            <p className="text-xs text-muted-foreground mt-1">
              Comma-separated keywords that identify this project
            </p>
          </div>

          <div>
            <label className="block text-sm font-medium mb-2">Description</label>
            <input
              type="text"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="e.g., Authorization server project"
              className="w-full px-4 py-2.5 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50"
            />
          </div>

          <div className="flex justify-end gap-3">
            {isEditing && (
              <button
                onClick={() => {
                  setIsEditing(false)
                  setPatterns(alias?.patterns.join(', ') || '')
                  setDescription(alias?.description || '')
                }}
                className="px-4 py-2 text-sm border border-border rounded-lg hover:bg-muted transition-colors"
              >
                Cancel
              </button>
            )}
            <button
              onClick={handleSave}
              disabled={saveMutation.isPending || !patterns.trim()}
              className="flex items-center gap-2 px-4 py-2 text-sm bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 disabled:opacity-50 transition-colors"
            >
              {saveMutation.isPending ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Save className="h-4 w-4" />
              )}
              Save Aliases
            </button>
          </div>
        </div>
      ) : (
        // Display Mode
        <div className="space-y-4">
          <div className="flex flex-wrap gap-2">
            {alias?.patterns.map((p, i) => (
              <span key={i} className="inline-flex items-center gap-1 px-3 py-1 bg-primary/10 text-primary rounded-full text-sm">
                <Tag className="h-3 w-3" />
                {p}
              </span>
            ))}
          </div>
          {alias?.description && (
            <p className="text-sm text-muted-foreground">{alias.description}</p>
          )}
        </div>
      )}

      {/* Test Section */}
      <div className="mt-6 pt-4 border-t border-border">
        <p className="text-sm font-medium mb-3">Test Detection</p>
        <div className="flex gap-2">
          <input
            type="text"
            value={testText}
            onChange={(e) => setTestText(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && testText.trim() && testMutation.mutate(testText)}
            placeholder="e.g., 인가 서버 코드 리뷰해줘"
            className="flex-1 px-3 py-2 text-sm rounded-lg border border-border bg-background"
          />
          <button
            onClick={() => testMutation.mutate(testText)}
            disabled={!testText.trim() || testMutation.isPending}
            className="px-4 py-2 bg-muted hover:bg-muted/80 rounded-lg transition-colors disabled:opacity-50"
          >
            {testMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <TestTube className="h-4 w-4" />
            )}
          </button>
        </div>

        {testResult !== null && (
          <div className="mt-3 p-3 rounded-lg bg-muted/50 text-sm">
            {testResult.length > 0 ? (
              <div className="space-y-2">
                <p className="text-green-600 dark:text-green-400 flex items-center gap-2">
                  <CheckCircle className="h-4 w-4" />
                  {testResult.length} project(s) detected
                </p>
                {testResult.map((r, i) => (
                  <div key={i} className="flex items-center gap-2 text-xs">
                    <span className="font-mono font-medium">{r.projectId}</span>
                    <span className="text-muted-foreground">matched "{r.matchedPattern}"</span>
                    {r.projectId === projectId && (
                      <span className="px-2 py-0.5 bg-green-500/10 text-green-500 rounded">this project</span>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-muted-foreground flex items-center gap-2">
                <AlertCircle className="h-4 w-4" />
                No projects detected
              </p>
            )}
          </div>
        )}
      </div>
    </Card>
  )
}

// Create Project Modal
function CreateProjectModal({
  onClose,
  onSuccess,
}: {
  onClose: () => void
  onSuccess: () => void
}) {
  const [formData, setFormData] = useState({
    id: '',
    name: '',
    description: '',
    workingDirectory: '',
    gitRemote: '',
    defaultBranch: 'main',
    isDefault: false,
  })

  const createMutation = useMutation({
    mutationFn: () => projectsApi.create(formData),
    onSuccess,
  })

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-card rounded-xl p-6 w-full max-w-lg max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-xl font-bold">Create New Project</h2>
          <button onClick={onClose} className="p-2 hover:bg-muted rounded-lg">
            <X className="h-5 w-5" />
          </button>
        </div>

        <form
          onSubmit={(e) => {
            e.preventDefault()
            createMutation.mutate()
          }}
          className="space-y-4"
        >
          <div>
            <label className="text-sm font-medium">Project ID *</label>
            <input
              type="text"
              value={formData.id}
              onChange={(e) => setFormData({ ...formData, id: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '-') })}
              className="w-full mt-1 px-3 py-2 bg-muted border border-border rounded-lg"
              placeholder="my-project"
              required
            />
          </div>

          <div>
            <label className="text-sm font-medium">Name *</label>
            <input
              type="text"
              value={formData.name}
              onChange={(e) => setFormData({ ...formData, name: e.target.value })}
              className="w-full mt-1 px-3 py-2 bg-muted border border-border rounded-lg"
              placeholder="My Project"
              required
            />
          </div>

          <div>
            <label className="text-sm font-medium">Description</label>
            <textarea
              value={formData.description}
              onChange={(e) => setFormData({ ...formData, description: e.target.value })}
              className="w-full mt-1 px-3 py-2 bg-muted border border-border rounded-lg"
              placeholder="Project description..."
              rows={2}
            />
          </div>

          <div>
            <label className="text-sm font-medium">Working Directory *</label>
            <input
              type="text"
              value={formData.workingDirectory}
              onChange={(e) => setFormData({ ...formData, workingDirectory: e.target.value })}
              className="w-full mt-1 px-3 py-2 bg-muted border border-border rounded-lg font-mono text-sm"
              placeholder="/path/to/project"
              required
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="text-sm font-medium">Git Remote</label>
              <input
                type="text"
                value={formData.gitRemote}
                onChange={(e) => setFormData({ ...formData, gitRemote: e.target.value })}
                className="w-full mt-1 px-3 py-2 bg-muted border border-border rounded-lg text-sm"
                placeholder="https://gitlab.example.com/..."
              />
            </div>
            <div>
              <label className="text-sm font-medium">Default Branch</label>
              <input
                type="text"
                value={formData.defaultBranch}
                onChange={(e) => setFormData({ ...formData, defaultBranch: e.target.value })}
                className="w-full mt-1 px-3 py-2 bg-muted border border-border rounded-lg"
                placeholder="main"
              />
            </div>
          </div>

          <div className="flex items-center">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={formData.isDefault}
                onChange={(e) => setFormData({ ...formData, isDefault: e.target.checked })}
                className="w-4 h-4 rounded"
              />
              <span className="text-sm">Set as default project</span>
            </label>
          </div>

          <div className="flex justify-end gap-3 pt-4 border-t border-border">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm border border-border rounded-lg hover:bg-muted transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={createMutation.isPending || !formData.id || !formData.name || !formData.workingDirectory}
              className="flex items-center gap-2 px-4 py-2 text-sm bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 disabled:opacity-50 transition-colors"
            >
              {createMutation.isPending ? (
                <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white" />
              ) : (
                <Check className="h-4 w-4" />
              )}
              Create Project
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// Edit Project Modal
function EditProjectModal({
  project,
  onClose,
  onSuccess,
}: {
  project: Project
  onClose: () => void
  onSuccess: () => void
}) {
  const [formData, setFormData] = useState({
    name: project.name,
    description: project.description || '',
    workingDirectory: project.workingDirectory,
    gitRemote: project.gitRemote || '',
    defaultBranch: project.defaultBranch,
  })

  const updateMutation = useMutation({
    mutationFn: () => projectsApi.update(project.id, formData),
    onSuccess,
  })

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-card rounded-xl p-6 w-full max-w-lg max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-xl font-bold">Edit Project</h2>
          <button onClick={onClose} className="p-2 hover:bg-muted rounded-lg">
            <X className="h-5 w-5" />
          </button>
        </div>

        <form
          onSubmit={(e) => {
            e.preventDefault()
            updateMutation.mutate()
          }}
          className="space-y-4"
        >
          <div>
            <label className="text-sm font-medium">Project ID</label>
            <input
              type="text"
              value={project.id}
              disabled
              className="w-full mt-1 px-3 py-2 bg-muted/50 border border-border rounded-lg text-muted-foreground cursor-not-allowed"
            />
            <p className="text-xs text-muted-foreground mt-1">Project ID cannot be changed</p>
          </div>

          <div>
            <label className="text-sm font-medium">Name *</label>
            <input
              type="text"
              value={formData.name}
              onChange={(e) => setFormData({ ...formData, name: e.target.value })}
              className="w-full mt-1 px-3 py-2 bg-muted border border-border rounded-lg"
              placeholder="My Project"
              required
            />
          </div>

          <div>
            <label className="text-sm font-medium">Description</label>
            <textarea
              value={formData.description}
              onChange={(e) => setFormData({ ...formData, description: e.target.value })}
              className="w-full mt-1 px-3 py-2 bg-muted border border-border rounded-lg"
              placeholder="Project description..."
              rows={2}
            />
          </div>

          <div>
            <label className="text-sm font-medium">Working Directory *</label>
            <input
              type="text"
              value={formData.workingDirectory}
              onChange={(e) => setFormData({ ...formData, workingDirectory: e.target.value })}
              className="w-full mt-1 px-3 py-2 bg-muted border border-border rounded-lg font-mono text-sm"
              placeholder="/path/to/project"
              required
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="text-sm font-medium">Git Remote</label>
              <input
                type="text"
                value={formData.gitRemote}
                onChange={(e) => setFormData({ ...formData, gitRemote: e.target.value })}
                className="w-full mt-1 px-3 py-2 bg-muted border border-border rounded-lg text-sm"
                placeholder="https://gitlab.example.com/..."
              />
            </div>
            <div>
              <label className="text-sm font-medium">Default Branch</label>
              <input
                type="text"
                value={formData.defaultBranch}
                onChange={(e) => setFormData({ ...formData, defaultBranch: e.target.value })}
                className="w-full mt-1 px-3 py-2 bg-muted border border-border rounded-lg"
                placeholder="main"
              />
            </div>
          </div>

          <div className="flex justify-end gap-3 pt-4 border-t border-border">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm border border-border rounded-lg hover:bg-muted transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={updateMutation.isPending || !formData.name || !formData.workingDirectory}
              className="flex items-center gap-2 px-4 py-2 text-sm bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 disabled:opacity-50 transition-colors"
            >
              {updateMutation.isPending ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Save className="h-4 w-4" />
              )}
              Save Changes
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default Projects
