import { useState, useRef, useCallback, useMemo, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  BookOpen,
  Upload,
  Link,
  Search,
  RefreshCw,
  Trash2,
  FileText,
  Image,
  Globe,
  CheckCircle,
  AlertCircle,
  Clock,
  Loader2,
  Plus,
  X,
  ChevronRight,
  ChevronDown,
  Database,
  Layers,
  Server,
  User,
  FolderGit2,
  Bot,
  MessageSquare,
  FileCode,
  Figma,
  ExternalLink,
} from 'lucide-react'
import { Card, CardHeader, StatCard } from '@/components/Card'
import { knowledgeApi, type KnowledgeDocument, type KnowledgeSearchResult, type VectorItem, type FigmaApiSpecResult, type ScreenApiSpec, type FigmaAnalysisJob } from '@/lib/api'
import { cn } from '@/lib/utils'

export function Knowledge() {
  const queryClient = useQueryClient()
  const [selectedDocument, setSelectedDocument] = useState<string | null>(null)
  const [selectedVectorItem, setSelectedVectorItem] = useState<VectorItem | null>(null)
  const [showUploadModal, setShowUploadModal] = useState(false)
  const [showUrlModal, setShowUrlModal] = useState(false)
  const [showApiSpecModal, setShowApiSpecModal] = useState(false)
  const [apiSpecResult, setApiSpecResult] = useState<FigmaApiSpecResult | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [searchResults, setSearchResults] = useState<KnowledgeSearchResult[] | null>(null)
  const [isSearching, setIsSearching] = useState(false)
  const [activeTab, setActiveTab] = useState<'vectors' | 'documents'>('vectors')
  const [expandedDocuments, setExpandedDocuments] = useState<Set<string>>(new Set())

  // Fetch documents
  const { data: documents, isLoading } = useQuery({
    queryKey: ['knowledge-documents'],
    queryFn: () => knowledgeApi.getDocuments(),
  })

  // Fetch stats
  const { data: stats } = useQuery({
    queryKey: ['knowledge-stats'],
    queryFn: () => knowledgeApi.getStats(),
  })

  // Fetch vector data
  const { data: vectorData, isLoading: isLoadingVectors } = useQuery({
    queryKey: ['knowledge-vectors'],
    queryFn: () => knowledgeApi.getVectorData(),
  })

  // Group user documents by documentId for hierarchical display
  const groupedUserDocuments = useMemo(() => {
    if (!vectorData?.user) return new Map<string, { title: string; chunks: VectorItem[] }>()

    const groups = new Map<string, { title: string; chunks: VectorItem[] }>()

    for (const item of vectorData.user) {
      const documentId = (item.metadata?.documentId as string) || item.docId
      const documentTitle = (item.metadata?.documentTitle as string) || item.name || documentId

      if (!groups.has(documentId)) {
        groups.set(documentId, { title: documentTitle, chunks: [] })
      }
      groups.get(documentId)!.chunks.push(item)
    }

    // Sort chunks by chunkIndex within each document
    for (const group of groups.values()) {
      group.chunks.sort((a, b) => {
        const indexA = (a.metadata?.chunkIndex as number) ?? 0
        const indexB = (b.metadata?.chunkIndex as number) ?? 0
        return indexA - indexB
      })
    }

    return groups
  }, [vectorData?.user])

  // Toggle document expansion
  const toggleDocumentExpansion = useCallback((documentId: string) => {
    setExpandedDocuments(prev => {
      const next = new Set(prev)
      if (next.has(documentId)) {
        next.delete(documentId)
      } else {
        next.add(documentId)
      }
      return next
    })
  }, [])

  // Sync mutation
  const syncMutation = useMutation({
    mutationFn: () => knowledgeApi.triggerSync(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge-documents'] })
      queryClient.invalidateQueries({ queryKey: ['knowledge-stats'] })
    },
  })

  // Delete mutation
  const deleteMutation = useMutation({
    mutationFn: (id: string) => knowledgeApi.deleteDocument(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge-documents'] })
      queryClient.invalidateQueries({ queryKey: ['knowledge-stats'] })
      setSelectedDocument(null)
    },
  })

  // Reindex mutation
  const reindexMutation = useMutation({
    mutationFn: (id: string) => knowledgeApi.reindexDocument(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge-documents'] })
    },
  })

  // Search handler
  const handleSearch = useCallback(async () => {
    if (!searchQuery.trim()) {
      setSearchResults(null)
      return
    }
    setIsSearching(true)
    try {
      const results = await knowledgeApi.search(searchQuery)
      setSearchResults(results)
    } catch (error) {
      console.error('Search failed:', error)
      setSearchResults([])
    } finally {
      setIsSearching(false)
    }
  }, [searchQuery])

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    )
  }

  const documentList = documents || []
  const selected = documentList.find(d => d.id === selectedDocument)

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Knowledge Base</h1>
          <p className="text-muted-foreground mt-1">
            Manage domain knowledge, documents, and image specs for AI context
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => syncMutation.mutate()}
            disabled={syncMutation.isPending}
            className="flex items-center gap-2 px-4 py-2 border border-border rounded-lg hover:bg-muted transition-colors"
          >
            {syncMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <RefreshCw className="h-4 w-4" />
            )}
            Sync All
          </button>
          <button
            onClick={() => setShowUrlModal(true)}
            className="flex items-center gap-2 px-4 py-2 border border-border rounded-lg hover:bg-muted transition-colors"
          >
            <Link className="h-4 w-4" />
            Add URL
          </button>
          <button
            onClick={() => setShowApiSpecModal(true)}
            className="flex items-center gap-2 px-4 py-2 border border-[#F24E1E] text-[#F24E1E] rounded-lg hover:bg-[#F24E1E]/10 transition-colors"
          >
            <Figma className="h-4 w-4" />
            Extract API Specs
          </button>
          <button
            onClick={() => setShowUploadModal(true)}
            className="flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-colors"
          >
            <Upload className="h-4 w-4" />
            Upload
          </button>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <StatCard
          title="Total Vectors"
          value={vectorData?.stats.total || 0}
          icon={<Database className="h-6 w-6" />}
        />
        <StatCard
          title="System (Auto)"
          value={vectorData?.system.length || 0}
          icon={<Server className="h-6 w-6" />}
          subtitle={Object.entries(vectorData?.stats.system || {}).map(([k, v]) => `${k}: ${v}`).join(', ') || '-'}
        />
        <StatCard
          title="User Documents"
          value={vectorData?.user.length || 0}
          icon={<User className="h-6 w-6" />}
          subtitle={Object.entries(vectorData?.stats.user || {}).map(([k, v]) => `${k}: ${v}`).join(', ') || '-'}
        />
        <StatCard
          title="Indexed Chunks"
          value={stats?.totalChunks || 0}
          icon={<Layers className="h-6 w-6" />}
        />
      </div>

      {/* Tabs */}
      <div className="flex border-b border-border">
        <button
          onClick={() => setActiveTab('vectors')}
          className={cn(
            "px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors",
            activeTab === 'vectors'
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          )}
        >
          <Database className="h-4 w-4 inline-block mr-2" />
          Vector Store
        </button>
        <button
          onClick={() => setActiveTab('documents')}
          className={cn(
            "px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors",
            activeTab === 'documents'
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          )}
        >
          <FileText className="h-4 w-4 inline-block mr-2" />
          Documents ({documents?.length || 0})
        </button>
      </div>

      {/* Search */}
      <Card>
        <CardHeader title="Search Knowledge" />
        <div className="flex gap-2">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
              placeholder="Search indexed knowledge..."
              className="w-full pl-10 pr-4 py-2.5 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50"
            />
          </div>
          <button
            onClick={handleSearch}
            disabled={isSearching || !searchQuery.trim()}
            className="px-6 py-2.5 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 disabled:opacity-50 transition-colors"
          >
            {isSearching ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Search'}
          </button>
        </div>

        {/* Search Results */}
        {searchResults && (
          <div className="mt-4 space-y-3">
            {searchResults.length === 0 ? (
              <p className="text-center text-muted-foreground py-4">No results found</p>
            ) : (
              searchResults.map((result, idx) => (
                <div key={idx} className="p-4 rounded-lg bg-muted/50 border border-border">
                  <div className="flex items-start justify-between">
                    <div>
                      <p className="font-medium">{result.documentTitle}</p>
                      <p className="text-sm text-muted-foreground line-clamp-2 mt-1">
                        {result.content}
                      </p>
                    </div>
                    <span className="text-xs bg-primary/10 text-primary px-2 py-1 rounded">
                      {(result.score * 100).toFixed(1)}%
                    </span>
                  </div>
                </div>
              ))
            )}
          </div>
        )}
      </Card>

      {/* Vector Store Tab Content */}
      {activeTab === 'vectors' && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* System (Auto-indexed) */}
          <div className="lg:col-span-1">
            <Card>
              <CardHeader
                title="System (Auto-indexed)"
                action={
                  <span className="text-xs text-muted-foreground">
                    {vectorData?.system.length || 0} items
                  </span>
                }
              />
              <div className="space-y-2 max-h-[400px] overflow-y-auto">
                {isLoadingVectors ? (
                  <div className="flex justify-center py-8">
                    <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                  </div>
                ) : (
                  vectorData?.system.map((item) => (
                    <button
                      key={item.docId}
                      onClick={() => setSelectedVectorItem(item)}
                      className={cn(
                        "w-full p-3 rounded-lg text-left transition-colors",
                        selectedVectorItem?.docId === item.docId
                          ? "bg-primary/10 border border-primary/30"
                          : "bg-muted/50 hover:bg-muted"
                      )}
                    >
                      <div className="flex items-start gap-3">
                        <VectorTypeIcon type={item.type} />
                        <div className="flex-1 min-w-0">
                          <p className="font-medium truncate">{item.name || item.docId}</p>
                          <div className="flex items-center gap-2 mt-1">
                            <span className="text-xs bg-blue-500/10 text-blue-500 px-2 py-0.5 rounded">
                              {item.type}
                            </span>
                          </div>
                        </div>
                        <ChevronRight className="h-4 w-4 text-muted-foreground" />
                      </div>
                    </button>
                  ))
                )}
                {!isLoadingVectors && (vectorData?.system.length || 0) === 0 && (
                  <p className="text-center text-muted-foreground py-8">
                    No system data indexed yet.
                  </p>
                )}
              </div>
            </Card>
          </div>

          {/* User Documents - Hierarchical View */}
          <div className="lg:col-span-1">
            <Card>
              <CardHeader
                title="User Documents"
                action={
                  <span className="text-xs text-muted-foreground">
                    {groupedUserDocuments.size} docs · {vectorData?.user.length || 0} chunks
                  </span>
                }
              />
              <div className="space-y-1 max-h-[400px] overflow-y-auto">
                {isLoadingVectors ? (
                  <div className="flex justify-center py-8">
                    <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                  </div>
                ) : (
                  Array.from(groupedUserDocuments.entries()).map(([documentId, { title, chunks }]) => {
                    const isExpanded = expandedDocuments.has(documentId)
                    return (
                      <div key={documentId} className="rounded-lg overflow-hidden">
                        {/* Document Header */}
                        <button
                          onClick={() => toggleDocumentExpansion(documentId)}
                          className={cn(
                            "w-full p-3 text-left transition-colors",
                            isExpanded
                              ? "bg-primary/10 border-l-2 border-primary"
                              : "bg-muted/50 hover:bg-muted"
                          )}
                        >
                          <div className="flex items-start gap-3">
                            {isExpanded ? (
                              <ChevronDown className="h-4 w-4 text-primary mt-0.5 shrink-0" />
                            ) : (
                              <ChevronRight className="h-4 w-4 text-muted-foreground mt-0.5 shrink-0" />
                            )}
                            <FileText className="h-5 w-5 text-orange-500 shrink-0" />
                            <div className="flex-1 min-w-0">
                              <p className="font-medium truncate">{title}</p>
                              <div className="flex items-center gap-2 mt-1">
                                <span className="text-xs bg-green-500/10 text-green-500 px-2 py-0.5 rounded">
                                  {chunks.length} chunks
                                </span>
                              </div>
                            </div>
                          </div>
                        </button>

                        {/* Chunks (Collapsible) */}
                        {isExpanded && (
                          <div className="pl-6 pr-2 pb-2 bg-muted/30 space-y-1">
                            {chunks.map((chunk, idx) => {
                              const chunkIndex = (chunk.metadata?.chunkIndex as number) ?? idx
                              return (
                                <button
                                  key={chunk.docId}
                                  onClick={() => setSelectedVectorItem(chunk)}
                                  className={cn(
                                    "w-full p-2 rounded-md text-left transition-colors text-sm",
                                    selectedVectorItem?.docId === chunk.docId
                                      ? "bg-primary/15 border border-primary/30"
                                      : "hover:bg-muted"
                                  )}
                                >
                                  <div className="flex items-center gap-2">
                                    <Layers className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                                    <span className="text-muted-foreground">Chunk {chunkIndex}</span>
                                    <span className="text-xs text-muted-foreground/60 truncate flex-1">
                                      {chunk.content.substring(0, 50)}...
                                    </span>
                                  </div>
                                </button>
                              )
                            })}
                          </div>
                        )}
                      </div>
                    )
                  })
                )}
                {!isLoadingVectors && groupedUserDocuments.size === 0 && (
                  <p className="text-center text-muted-foreground py-8">
                    No user documents yet.
                  </p>
                )}
              </div>
            </Card>
          </div>

          {/* Vector Item Details */}
          <div className="lg:col-span-1">
            {selectedVectorItem ? (
              <Card>
                <CardHeader title={selectedVectorItem.name || selectedVectorItem.docId} />
                <div className="space-y-4">
                  <div className="grid grid-cols-2 gap-4">
                    <InfoItem label="Type" value={selectedVectorItem.type} />
                    <InfoItem label="Doc ID" value={selectedVectorItem.docId} />
                  </div>
                  {selectedVectorItem.description && (
                    <InfoItem label="Description" value={selectedVectorItem.description} />
                  )}
                  {selectedVectorItem.updatedAt && (
                    <InfoItem
                      label="Updated"
                      value={new Date(selectedVectorItem.updatedAt).toLocaleString()}
                    />
                  )}

                  {/* Figma Frame Image Preview */}
                  <FigmaImagePreview content={selectedVectorItem.content} />

                  <div className="p-3 rounded-lg bg-muted/50 max-h-[400px] overflow-y-auto">
                    <p className="text-sm text-muted-foreground whitespace-pre-wrap font-mono leading-relaxed">
                      {selectedVectorItem.content}
                    </p>
                  </div>
                </div>
              </Card>
            ) : (
              <Card className="flex flex-col items-center justify-center py-16">
                <Database className="h-16 w-16 text-muted-foreground/30 mb-4" />
                <p className="text-muted-foreground">Select an item to view details</p>
              </Card>
            )}
          </div>
        </div>
      )}

      {/* Document List and Details */}
      {activeTab === 'documents' && (
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Document List */}
        <div className="lg:col-span-1">
          <Card>
            <CardHeader title="Documents" />
            <div className="space-y-2 max-h-[600px] overflow-y-auto">
              {documentList.map((doc) => (
                <button
                  key={doc.id}
                  onClick={() => setSelectedDocument(doc.id)}
                  className={cn(
                    "w-full p-3 rounded-lg text-left transition-colors",
                    selectedDocument === doc.id
                      ? "bg-primary/10 border border-primary/30"
                      : "bg-muted/50 hover:bg-muted"
                  )}
                >
                  <div className="flex items-start gap-3">
                    <SourceIcon source={doc.source} />
                    <div className="flex-1 min-w-0">
                      <p className="font-medium truncate">{doc.title}</p>
                      <div className="flex items-center gap-2 mt-1">
                        <StatusBadge status={doc.status} />
                        <span className="text-xs text-muted-foreground">
                          {doc.chunkCount} chunks
                        </span>
                      </div>
                    </div>
                    <ChevronRight className="h-4 w-4 text-muted-foreground" />
                  </div>
                </button>
              ))}
              {documentList.length === 0 && (
                <p className="text-center text-muted-foreground py-8">
                  No documents yet. Upload files or add URLs.
                </p>
              )}
            </div>
          </Card>
        </div>

        {/* Document Details */}
        <div className="lg:col-span-2">
          {selected ? (
            <Card>
              <CardHeader
                title={selected.title}
                action={
                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => reindexMutation.mutate(selected.id)}
                      disabled={reindexMutation.isPending}
                      className="p-2 hover:bg-muted rounded-lg transition-colors"
                      title="Re-index"
                    >
                      {reindexMutation.isPending ? (
                        <Loader2 className="h-4 w-4 animate-spin" />
                      ) : (
                        <RefreshCw className="h-4 w-4" />
                      )}
                    </button>
                    <button
                      onClick={() => {
                        if (confirm('Delete this document?')) {
                          deleteMutation.mutate(selected.id)
                        }
                      }}
                      className="p-2 hover:bg-red-500/10 text-red-500 rounded-lg transition-colors"
                      title="Delete"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                }
              />

              <div className="grid grid-cols-2 gap-4">
                <InfoItem label="Source" value={selected.source} />
                <InfoItem label="Status">
                  <StatusBadge status={selected.status} />
                </InfoItem>
                <InfoItem label="Chunks" value={selected.chunkCount.toString()} />
                <InfoItem label="MIME Type" value={selected.mimeType || '-'} />
                <InfoItem
                  label="Created"
                  value={new Date(selected.createdAt).toLocaleString()}
                />
                <InfoItem
                  label="Last Indexed"
                  value={selected.lastIndexedAt ? new Date(selected.lastIndexedAt).toLocaleString() : '-'}
                />
              </div>

              {selected.sourceUrl && (
                <div className="mt-4 p-3 rounded-lg bg-muted/50">
                  <p className="text-sm text-muted-foreground">Source URL</p>
                  <a
                    href={selected.sourceUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-sm text-primary hover:underline break-all"
                  >
                    {selected.sourceUrl}
                  </a>
                </div>
              )}

              {selected.errorMessage && (
                <div className="mt-4 p-3 rounded-lg bg-red-500/10 border border-red-500/30">
                  <p className="text-sm text-red-500">{selected.errorMessage}</p>
                </div>
              )}
            </Card>
          ) : (
            <Card className="flex flex-col items-center justify-center py-16">
              <BookOpen className="h-16 w-16 text-muted-foreground/30 mb-4" />
              <p className="text-muted-foreground">Select a document to view details</p>
            </Card>
          )}
        </div>
      </div>
      )}

      {/* Upload Modal */}
      {showUploadModal && (
        <UploadModal
          onClose={() => setShowUploadModal(false)}
          onSuccess={() => {
            setShowUploadModal(false)
            queryClient.invalidateQueries({ queryKey: ['knowledge-documents'] })
            queryClient.invalidateQueries({ queryKey: ['knowledge-stats'] })
          }}
        />
      )}

      {/* URL Modal */}
      {showUrlModal && (
        <UrlModal
          onClose={() => setShowUrlModal(false)}
          onSuccess={() => {
            setShowUrlModal(false)
            queryClient.invalidateQueries({ queryKey: ['knowledge-documents'] })
            queryClient.invalidateQueries({ queryKey: ['knowledge-stats'] })
          }}
        />
      )}

      {/* Figma API Spec Modal */}
      {showApiSpecModal && (
        <FigmaApiSpecModal
          onClose={() => setShowApiSpecModal(false)}
          onResult={(result) => {
            setApiSpecResult(result)
            queryClient.invalidateQueries({ queryKey: ['knowledge-vectors'] })
          }}
        />
      )}

      {/* API Spec Result Modal */}
      {apiSpecResult && (
        <ApiSpecResultModal
          result={apiSpecResult}
          onClose={() => setApiSpecResult(null)}
        />
      )}
    </div>
  )
}

// Helper Components

function SourceIcon({ source }: { source: string }) {
  switch (source) {
    case 'UPLOAD':
      return <FileText className="h-5 w-5 text-blue-500" />
    case 'URL':
      return <Globe className="h-5 w-5 text-green-500" />
    case 'IMAGE':
      return <Image className="h-5 w-5 text-purple-500" />
    case 'FIGMA':
      return <Figma className="h-5 w-5 text-[#F24E1E]" />
    default:
      return <FileText className="h-5 w-5 text-muted-foreground" />
  }
}

function VectorTypeIcon({ type }: { type: string }) {
  switch (type) {
    case 'project':
    case 'project-list':
      return <FolderGit2 className="h-5 w-5 text-blue-500" />
    case 'agent':
      return <Bot className="h-5 w-5 text-purple-500" />
    case 'conversation':
      return <MessageSquare className="h-5 w-5 text-green-500" />
    case 'document':
      return <FileText className="h-5 w-5 text-orange-500" />
    case 'url':
      return <Globe className="h-5 w-5 text-cyan-500" />
    case 'image':
      return <Image className="h-5 w-5 text-pink-500" />
    case 'figma':
      return <Figma className="h-5 w-5 text-[#F24E1E]" />
    default:
      return <FileCode className="h-5 w-5 text-muted-foreground" />
  }
}

function StatusBadge({ status }: { status: KnowledgeDocument['status'] }) {
  const styles = {
    PENDING: 'bg-yellow-500/10 text-yellow-500',
    PROCESSING: 'bg-blue-500/10 text-blue-500',
    INDEXED: 'bg-green-500/10 text-green-500',
    OUTDATED: 'bg-orange-500/10 text-orange-500',
    ERROR: 'bg-red-500/10 text-red-500',
  }

  const icons = {
    PENDING: <Clock className="h-3 w-3" />,
    PROCESSING: <Loader2 className="h-3 w-3 animate-spin" />,
    INDEXED: <CheckCircle className="h-3 w-3" />,
    OUTDATED: <RefreshCw className="h-3 w-3" />,
    ERROR: <AlertCircle className="h-3 w-3" />,
  }

  return (
    <span className={cn('inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs', styles[status])}>
      {icons[status]}
      {status}
    </span>
  )
}

function InfoItem({ label, value, children }: { label: string; value?: string; children?: React.ReactNode }) {
  return (
    <div className="p-3 rounded-lg bg-muted/50">
      <p className="text-sm text-muted-foreground">{label}</p>
      {children || <p className="font-medium mt-0.5">{value}</p>}
    </div>
  )
}

/**
 * Figma Frame 이미지 미리보기 컴포넌트
 * 콘텐츠에서 "Image: https://..." 패턴을 찾아 이미지 미리보기 표시
 */
function FigmaImagePreview({ content }: { content: string }) {
  // Extract ALL frames with images using matchAll (global flag)
  const frames = useMemo(() => {
    const result: { name: string; imageUrl: string }[] = []

    // Split content by Frame headers (## FrameName)
    const frameRegex = /^##\s+(.+)$/gm
    const imageRegex = /Image:\s*(https:\/\/[^\s\n]+)/gi

    // Find all frame names
    const frameMatches = [...content.matchAll(frameRegex)]
    // Find all image URLs
    const imageMatches = [...content.matchAll(imageRegex)]

    // Pair them up (frames and images appear in same order)
    const maxPairs = Math.min(frameMatches.length, imageMatches.length)
    for (let i = 0; i < maxPairs; i++) {
      result.push({
        name: frameMatches[i][1],
        imageUrl: imageMatches[i][1]
      })
    }

    return result
  }, [content])

  const [loadingStates, setLoadingStates] = useState<Record<number, boolean>>({})
  const [errorStates, setErrorStates] = useState<Record<number, boolean>>({})
  const [selectedIndex, setSelectedIndex] = useState(0)

  if (frames.length === 0) return null

  const selectedFrame = frames[selectedIndex]

  return (
    <div className="rounded-lg overflow-hidden border border-border bg-muted/30">
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 bg-muted/50 border-b border-border">
        <div className="flex items-center gap-2">
          <Figma className="h-4 w-4 text-[#F24E1E]" />
          <span className="text-sm font-medium">{selectedFrame.name || 'Frame Preview'}</span>
          {frames.length > 1 && (
            <span className="text-xs text-muted-foreground bg-muted px-1.5 py-0.5 rounded">
              {selectedIndex + 1} / {frames.length}
            </span>
          )}
        </div>
        <a
          href={selectedFrame.imageUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-1 text-xs text-primary hover:underline"
        >
          <ExternalLink className="h-3 w-3" />
          Open
        </a>
      </div>

      {/* Main Image */}
      <div className="relative aspect-video bg-muted/50">
        {loadingStates[selectedIndex] !== false && !errorStates[selectedIndex] && (
          <div className="absolute inset-0 flex items-center justify-center">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        )}
        {errorStates[selectedIndex] ? (
          <div className="absolute inset-0 flex flex-col items-center justify-center text-muted-foreground">
            <AlertCircle className="h-8 w-8 mb-2" />
            <p className="text-sm">Failed to load image</p>
            <p className="text-xs mt-1">(Figma image URLs expire after 30 days)</p>
          </div>
        ) : (
          <img
            src={selectedFrame.imageUrl}
            alt={selectedFrame.name || 'Figma Frame'}
            className={cn(
              "w-full h-full object-contain transition-opacity",
              loadingStates[selectedIndex] === false ? "opacity-100" : "opacity-0"
            )}
            onLoad={() => setLoadingStates(prev => ({ ...prev, [selectedIndex]: false }))}
            onError={() => {
              setLoadingStates(prev => ({ ...prev, [selectedIndex]: false }))
              setErrorStates(prev => ({ ...prev, [selectedIndex]: true }))
            }}
          />
        )}
      </div>

      {/* Thumbnail Strip (if multiple frames) */}
      {frames.length > 1 && (
        <div className="flex gap-1 p-2 overflow-x-auto bg-muted/30 border-t border-border">
          {frames.map((frame, idx) => (
            <button
              key={idx}
              onClick={() => setSelectedIndex(idx)}
              className={cn(
                "flex-shrink-0 w-16 h-12 rounded border-2 overflow-hidden transition-all",
                selectedIndex === idx
                  ? "border-primary ring-1 ring-primary"
                  : "border-transparent opacity-60 hover:opacity-100"
              )}
            >
              <img
                src={frame.imageUrl}
                alt={frame.name}
                className="w-full h-full object-cover"
              />
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

// Upload Modal
function UploadModal({ onClose, onSuccess }: { onClose: () => void; onSuccess: () => void }) {
  const [title, setTitle] = useState('')
  const [isDragging, setIsDragging] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [isUploading, setIsUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(false)
    const droppedFile = e.dataTransfer.files[0]
    if (droppedFile) {
      setFile(droppedFile)
      if (!title) setTitle(droppedFile.name.replace(/\.[^/.]+$/, ''))
    }
  }, [title])

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFile = e.target.files?.[0]
    if (selectedFile) {
      setFile(selectedFile)
      if (!title) setTitle(selectedFile.name.replace(/\.[^/.]+$/, ''))
    }
  }

  const handleUpload = async () => {
    if (!file) return
    setIsUploading(true)
    setError(null)
    try {
      await knowledgeApi.uploadFile(file, title || undefined)
      onSuccess()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setIsUploading(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-card rounded-xl p-6 w-full max-w-lg">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-xl font-bold">Upload Document</h2>
          <button onClick={onClose} className="p-2 hover:bg-muted rounded-lg">
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Drop Zone */}
        <div
          onDragOver={(e) => { e.preventDefault(); setIsDragging(true) }}
          onDragLeave={() => setIsDragging(false)}
          onDrop={handleDrop}
          onClick={() => fileInputRef.current?.click()}
          className={cn(
            "border-2 border-dashed rounded-lg p-8 text-center cursor-pointer transition-colors",
            isDragging ? "border-primary bg-primary/5" : "border-border hover:border-primary/50"
          )}
        >
          <input
            ref={fileInputRef}
            type="file"
            onChange={handleFileSelect}
            className="hidden"
            accept=".txt,.md,.html,.pdf,.doc,.docx,.xlsx,.xls,.csv,.png,.jpg,.jpeg,.gif,.webp"
          />
          {file ? (
            <div className="flex items-center gap-3 justify-center">
              <FileText className="h-8 w-8 text-primary" />
              <div className="text-left">
                <p className="font-medium">{file.name}</p>
                <p className="text-sm text-muted-foreground">
                  {(file.size / 1024).toFixed(1)} KB
                </p>
              </div>
            </div>
          ) : (
            <>
              <Upload className="h-12 w-12 text-muted-foreground mx-auto mb-4" />
              <p className="font-medium">Drop file here or click to browse</p>
              <p className="text-sm text-muted-foreground mt-1">
                Supports: TXT, MD, HTML, PDF, DOC, DOCX, XLSX, XLS, CSV, Images
              </p>
            </>
          )}
        </div>

        {/* Title Input */}
        <div className="mt-4">
          <label className="block text-sm font-medium mb-2">Title (optional)</label>
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Document title"
            className="w-full px-4 py-2.5 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50"
          />
        </div>

        {error && (
          <div className="mt-4 p-3 rounded-lg bg-red-500/10 border border-red-500/30">
            <p className="text-sm text-red-500">{error}</p>
          </div>
        )}

        <div className="flex justify-end gap-3 mt-6">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm border border-border rounded-lg hover:bg-muted transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleUpload}
            disabled={!file || isUploading}
            className="flex items-center gap-2 px-4 py-2 text-sm bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 disabled:opacity-50 transition-colors"
          >
            {isUploading ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Plus className="h-4 w-4" />
            )}
            Upload
          </button>
        </div>
      </div>
    </div>
  )
}

// URL Modal
function UrlModal({ onClose, onSuccess }: { onClose: () => void; onSuccess: () => void }) {
  const [url, setUrl] = useState('')
  const [title, setTitle] = useState('')
  const [autoSync, setAutoSync] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async () => {
    if (!url.trim()) return
    setIsLoading(true)
    setError(null)
    try {
      await knowledgeApi.fetchUrl(url, title || undefined, undefined, autoSync)
      onSuccess()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch URL')
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-card rounded-xl p-6 w-full max-w-lg">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-xl font-bold">Add URL</h2>
          <button onClick={onClose} className="p-2 hover:bg-muted rounded-lg">
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium mb-2">URL *</label>
            <input
              type="url"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="https://example.com/docs/..."
              className="w-full px-4 py-2.5 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50"
            />
          </div>

          <div>
            <label className="block text-sm font-medium mb-2">Title (optional)</label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Document title"
              className="w-full px-4 py-2.5 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50"
            />
          </div>

          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              checked={autoSync}
              onChange={(e) => setAutoSync(e.target.checked)}
              className="w-4 h-4 rounded"
            />
            <span className="text-sm">Auto-sync (re-fetch periodically)</span>
          </label>
        </div>

        {error && (
          <div className="mt-4 p-3 rounded-lg bg-red-500/10 border border-red-500/30">
            <p className="text-sm text-red-500">{error}</p>
          </div>
        )}

        <div className="flex justify-end gap-3 mt-6">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm border border-border rounded-lg hover:bg-muted transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={!url.trim() || isLoading}
            className="flex items-center gap-2 px-4 py-2 text-sm bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 disabled:opacity-50 transition-colors"
          >
            {isLoading ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Link className="h-4 w-4" />
            )}
            Add URL
          </button>
        </div>
      </div>
    </div>
  )
}

// Figma API Spec Modal (Design-Aware Code Review) - Job-based Background Processing with SSE
function FigmaApiSpecModal({
  onClose,
  onResult,
}: {
  onClose: () => void
  onResult: (result: FigmaApiSpecResult) => void
}) {
  const [figmaUrl, setFigmaUrl] = useState('')
  const [projectId, setProjectId] = useState('')
  const [isStarting, setIsStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [currentJob, setCurrentJob] = useState<FigmaAnalysisJob | null>(null)
  const eventSourceRef = useRef<EventSource | null>(null)

  // SSE로 Job 진행 상황 스트리밍
  useEffect(() => {
    if (!currentJob || currentJob.status === 'COMPLETED' || currentJob.status === 'FAILED') {
      return
    }

    // 기존 EventSource 정리
    if (eventSourceRef.current) {
      eventSourceRef.current.close()
    }

    const eventSource = new EventSource(`/api/v1/knowledge/figma/jobs/${currentJob.id}/stream`)
    eventSourceRef.current = eventSource

    eventSource.addEventListener('job', (event) => {
      try {
        const updatedJob = JSON.parse(event.data) as FigmaAnalysisJob
        setCurrentJob(updatedJob)

        if (updatedJob.status === 'COMPLETED' && updatedJob.result) {
          eventSource.close()
          onResult(updatedJob.result)
          onClose()
        } else if (updatedJob.status === 'FAILED') {
          eventSource.close()
        }
      } catch (err) {
        console.error('Failed to parse job update:', err)
      }
    })

    eventSource.addEventListener('error', (event) => {
      console.error('SSE error:', event)
      // 연결 끊김 시 상태 확인을 위해 폴백
      eventSource.close()
    })

    return () => {
      eventSource.close()
      eventSourceRef.current = null
    }
  }, [currentJob?.id, currentJob?.status, onResult, onClose])

  const handleSubmit = async () => {
    if (!figmaUrl.trim()) return
    setIsStarting(true)
    setError(null)

    try {
      const job = await knowledgeApi.startFigmaAnalysisJob(figmaUrl, {
        projectId: projectId || undefined,
        indexToKnowledgeBase: true,
      })
      setCurrentJob(job)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Job 시작 실패')
    } finally {
      setIsStarting(false)
    }
  }

  const isFigmaUrl = figmaUrl.includes('figma.com')
  const isProcessing = !!(currentJob && (currentJob.status === 'PENDING' || currentJob.status === 'PROCESSING'))

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-card rounded-xl p-6 w-full max-w-lg">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-3">
            <Figma className="h-6 w-6 text-[#F24E1E]" />
            <h2 className="text-xl font-bold">Extract API Specs</h2>
          </div>
          <button onClick={onClose} className="p-2 hover:bg-muted rounded-lg" disabled={isProcessing}>
            <X className="h-5 w-5" />
          </button>
        </div>

        <p className="text-sm text-muted-foreground mb-4">
          Figma 기획서를 Vision AI로 분석하여 백엔드 API 스펙을 추출합니다.
          전체 프레임을 백그라운드에서 분석합니다. (시간이 오래 걸릴 수 있습니다)
        </p>

        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium mb-2">Figma URL *</label>
            <input
              type="url"
              value={figmaUrl}
              onChange={(e) => setFigmaUrl(e.target.value)}
              placeholder="https://www.figma.com/file/xxx..."
              disabled={isProcessing}
              className={cn(
                'w-full px-4 py-2.5 rounded-lg border bg-background focus:ring-2 focus:ring-primary/50 disabled:opacity-50',
                isFigmaUrl ? 'border-[#F24E1E]' : 'border-border'
              )}
            />
            {figmaUrl && !isFigmaUrl && (
              <p className="text-xs text-yellow-500 mt-1">Figma URL을 입력해주세요</p>
            )}
          </div>

          <div>
            <label className="block text-sm font-medium mb-2">Project ID (optional)</label>
            <input
              type="text"
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              placeholder="e.g., ccds, sirius"
              disabled={isProcessing}
              className="w-full px-4 py-2.5 rounded-lg border border-border bg-background focus:ring-2 focus:ring-primary/50 disabled:opacity-50"
            />
            <p className="text-xs text-muted-foreground mt-1">MR 리뷰 시 검색 필터로 사용됩니다</p>
          </div>
        </div>

        {/* Job Progress Display */}
        {currentJob && (
          <div className="mt-4 p-4 rounded-lg bg-muted/50 border border-border">
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                {currentJob.status === 'PENDING' && (
                  <Clock className="h-4 w-4 text-yellow-500" />
                )}
                {currentJob.status === 'PROCESSING' && (
                  <Loader2 className="h-4 w-4 animate-spin text-blue-500" />
                )}
                {currentJob.status === 'COMPLETED' && (
                  <CheckCircle className="h-4 w-4 text-green-500" />
                )}
                {currentJob.status === 'FAILED' && (
                  <AlertCircle className="h-4 w-4 text-red-500" />
                )}
                <span className={cn(
                  'text-sm font-medium',
                  currentJob.status === 'PENDING' && 'text-yellow-500',
                  currentJob.status === 'PROCESSING' && 'text-blue-500',
                  currentJob.status === 'COMPLETED' && 'text-green-500',
                  currentJob.status === 'FAILED' && 'text-red-500'
                )}>
                  {currentJob.status === 'PENDING' && '대기 중...'}
                  {currentJob.status === 'PROCESSING' && '분석 중...'}
                  {currentJob.status === 'COMPLETED' && '완료!'}
                  {currentJob.status === 'FAILED' && '실패'}
                </span>
              </div>
              <span className="text-sm text-muted-foreground">
                {currentJob.progress.percentage}%
              </span>
            </div>

            {/* Progress Bar */}
            <div className="w-full h-2 bg-muted rounded-full overflow-hidden">
              <div
                className={cn(
                  'h-full transition-all duration-300',
                  currentJob.status === 'FAILED' ? 'bg-red-500' : 'bg-[#F24E1E]'
                )}
                style={{ width: `${currentJob.progress.percentage}%` }}
              />
            </div>

            {/* Progress Details */}
            <div className="flex items-center justify-between mt-2 text-xs text-muted-foreground">
              <span>
                {currentJob.progress.analyzedFrames} / {currentJob.progress.totalFrames} frames
              </span>
              {currentJob.progress.currentFrame && (
                <span className="truncate max-w-[200px]">
                  {currentJob.progress.currentFrame}
                </span>
              )}
            </div>

            {/* File Name */}
            {currentJob.fileName && (
              <p className="text-xs text-muted-foreground mt-2 truncate">
                📄 {currentJob.fileName}
              </p>
            )}
          </div>
        )}

        {currentJob?.status === 'FAILED' && currentJob.errorMessage && (
          <div className="mt-4 p-3 rounded-lg bg-red-500/10 border border-red-500/30">
            <p className="text-sm text-red-500">{currentJob.errorMessage}</p>
          </div>
        )}

        {error && (
          <div className="mt-4 p-3 rounded-lg bg-red-500/10 border border-red-500/30">
            <p className="text-sm text-red-500">{error}</p>
          </div>
        )}

        <div className="flex justify-end gap-3 mt-6">
          <button
            onClick={onClose}
            disabled={isProcessing}
            className="px-4 py-2 text-sm border border-border rounded-lg hover:bg-muted transition-colors disabled:opacity-50"
          >
            {isProcessing ? 'Processing...' : 'Cancel'}
          </button>
          <button
            onClick={handleSubmit}
            disabled={!figmaUrl.trim() || !isFigmaUrl || isStarting || isProcessing}
            className="flex items-center gap-2 px-4 py-2 text-sm bg-[#F24E1E] text-white rounded-lg hover:bg-[#F24E1E]/90 disabled:opacity-50 transition-colors"
          >
            {isStarting ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : isProcessing ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Figma className="h-4 w-4" />
            )}
            {isProcessing ? 'Analyzing...' : 'Start Analysis'}
          </button>
        </div>
      </div>
    </div>
  )
}

// API Spec Result Modal
function ApiSpecResultModal({
  result,
  onClose,
}: {
  result: FigmaApiSpecResult
  onClose: () => void
}) {
  const [selectedScreen, setSelectedScreen] = useState<ScreenApiSpec | null>(
    result.screenSpecs[0] || null
  )

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <div className="bg-card rounded-xl w-full max-w-6xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b border-border">
          <div>
            <div className="flex items-center gap-3">
              <Figma className="h-6 w-6 text-[#F24E1E]" />
              <h2 className="text-xl font-bold">{result.fileName}</h2>
            </div>
            <p className="text-sm text-muted-foreground mt-1">
              {result.stats.analyzedFrames} frames analyzed •{' '}
              {result.stats.totalApis} APIs •{' '}
              {result.stats.totalValidations} validations •{' '}
              {result.stats.totalBusinessRules} business rules
            </p>
          </div>
          <button onClick={onClose} className="p-2 hover:bg-muted rounded-lg">
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Content */}
        <div className="flex flex-1 overflow-hidden">
          {/* Left: Screen List */}
          <div className="w-64 border-r border-border overflow-y-auto">
            <div className="p-2">
              {result.screenSpecs.map((spec) => (
                <button
                  key={spec.screenId}
                  onClick={() => setSelectedScreen(spec)}
                  className={cn(
                    'w-full text-left p-3 rounded-lg mb-1 transition-colors',
                    selectedScreen?.screenId === spec.screenId
                      ? 'bg-primary/10 border border-primary/30'
                      : 'hover:bg-muted'
                  )}
                >
                  <p className="font-medium truncate">{spec.screenName}</p>
                  <p className="text-xs text-muted-foreground mt-1">
                    {spec.apis.length} APIs • {spec.validations.length} validations
                  </p>
                </button>
              ))}
            </div>
          </div>

          {/* Right: Screen Details */}
          <div className="flex-1 overflow-y-auto p-6">
            {selectedScreen ? (
              <div className="space-y-6">
                {/* Screen Image */}
                {selectedScreen.imageUrl && (
                  <div className="flex justify-center">
                    <a
                      href={selectedScreen.imageUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="block max-w-md"
                    >
                      <img
                        src={selectedScreen.imageUrl}
                        alt={selectedScreen.screenName}
                        className="rounded-lg border border-border max-h-48 object-contain"
                      />
                    </a>
                  </div>
                )}

                {/* APIs */}
                {selectedScreen.apis.length > 0 && (
                  <div>
                    <h3 className="text-lg font-semibold mb-3 flex items-center gap-2">
                      <Server className="h-5 w-5" /> APIs
                    </h3>
                    <div className="space-y-3">
                      {selectedScreen.apis.map((api, idx) => (
                        <div key={idx} className="p-4 rounded-lg bg-muted/50 border border-border">
                          <div className="flex items-center gap-2 mb-2">
                            <span className={cn(
                              'px-2 py-0.5 text-xs font-mono rounded',
                              api.method === 'GET' && 'bg-green-500/20 text-green-500',
                              api.method === 'POST' && 'bg-blue-500/20 text-blue-500',
                              api.method === 'PUT' && 'bg-yellow-500/20 text-yellow-500',
                              api.method === 'PATCH' && 'bg-orange-500/20 text-orange-500',
                              api.method === 'DELETE' && 'bg-red-500/20 text-red-500'
                            )}>
                              {api.method}
                            </span>
                            <code className="text-sm font-mono">{api.path}</code>
                          </div>
                          <p className="text-sm text-muted-foreground">{api.description}</p>
                          {api.requestFields.length > 0 && (
                            <div className="mt-2">
                              <p className="text-xs font-medium text-muted-foreground">Request:</p>
                              <div className="flex flex-wrap gap-1 mt-1">
                                {api.requestFields.map((f, i) => (
                                  <span key={i} className="px-2 py-0.5 text-xs bg-background rounded">
                                    {f.name}: {f.type}{f.required && '*'}
                                  </span>
                                ))}
                              </div>
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* Validations */}
                {selectedScreen.validations.length > 0 && (
                  <div>
                    <h3 className="text-lg font-semibold mb-3">Validations</h3>
                    <div className="space-y-2">
                      {selectedScreen.validations.map((v, idx) => (
                        <div key={idx} className="p-3 rounded-lg bg-yellow-500/10 border border-yellow-500/30">
                          <p className="font-medium text-sm">{v.field}</p>
                          <div className="flex flex-wrap gap-1 mt-1">
                            {v.rules.map((rule, i) => (
                              <span key={i} className="px-2 py-0.5 text-xs bg-yellow-500/20 rounded">
                                {rule}
                              </span>
                            ))}
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* Business Rules */}
                {selectedScreen.businessRules.length > 0 && (
                  <div>
                    <h3 className="text-lg font-semibold mb-3">Business Rules</h3>
                    <ul className="space-y-2">
                      {selectedScreen.businessRules.map((rule, idx) => (
                        <li key={idx} className="flex items-start gap-2 text-sm">
                          <span className="text-primary">•</span>
                          {rule}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </div>
            ) : (
              <div className="flex items-center justify-center h-full text-muted-foreground">
                Select a screen to view details
              </div>
            )}
          </div>
        </div>

        {/* Footer */}
        <div className="flex justify-between items-center p-4 border-t border-border">
          <p className="text-xs text-muted-foreground">
            Processing time: {(result.processingTimeMs / 1000).toFixed(1)}s
          </p>
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  )
}

export default Knowledge
