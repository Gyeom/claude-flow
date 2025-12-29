import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { X, Search, ExternalLink, GitMerge, Loader2, AlertCircle, CheckCircle, Link2 } from 'lucide-react'
import { jiraApi } from '@/lib/api'
import { cn } from '@/lib/utils'
import { toast } from 'sonner'

interface MRLinkDialogProps {
  issueKey: string
  transitionId: string
  transitionName: string
  onClose: () => void
  onLinked: () => void
}

interface GitLabMR {
  iid: number
  title: string
  state: string
  source_branch: string
  target_branch: string
  author: string
  web_url: string
  project: string
  created_at: string
  merged_at: string | null
}

export function MRLinkDialog({
  issueKey,
  transitionId: _transitionId,
  transitionName,
  onClose,
  onLinked,
}: MRLinkDialogProps) {
  // transitionId is passed for potential future use (e.g., checking specific transition requirements)
  void _transitionId

  const [manualUrl, setManualUrl] = useState('')
  const [manualTitle, setManualTitle] = useState('')
  const [activeTab, setActiveTab] = useState<'search' | 'manual'>('search')

  // GitLab에서 이슈 키로 MR 검색
  const {
    data: searchResult,
    isLoading: isSearching,
  } = useQuery({
    queryKey: ['gitlab-mr-search', issueKey],
    queryFn: () => jiraApi.searchMRsByIssueKey(issueKey),
    enabled: true,
  })

  // MR 링크 추가 mutation
  const linkMutation = useMutation({
    mutationFn: ({ url, title }: { url: string; title: string }) =>
      jiraApi.addRemoteLink(issueKey, url, title),
    onSuccess: (data) => {
      if (data.success) {
        toast.success(`MR이 ${issueKey}에 연결되었습니다`)
        onLinked()
      } else {
        toast.error(data.error || 'MR 연결 실패')
      }
    },
    onError: (error) => {
      toast.error(`MR 연결 실패: ${error}`)
    },
  })

  // 검색된 MR 선택해서 연결
  const handleSelectMR = (mr: GitLabMR) => {
    const title = `MR !${mr.iid}: ${mr.title}`
    linkMutation.mutate({ url: mr.web_url, title })
  }

  // 수동 입력으로 연결
  const handleManualLink = () => {
    if (!manualUrl.trim()) {
      toast.error('URL을 입력해주세요')
      return
    }

    const title = manualTitle.trim() || `MR: ${issueKey}`
    linkMutation.mutate({ url: manualUrl.trim(), title })
  }

  // MR 상태에 따른 색상
  const getStateColor = (state: string) => {
    switch (state) {
      case 'merged':
        return 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300'
      case 'opened':
        return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300'
      case 'closed':
        return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
      default:
        return 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
    }
  }

  const mrs = searchResult?.data?.mrs || []
  const hasMRs = mrs.length > 0

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative bg-background rounded-xl shadow-2xl border w-full max-w-2xl mx-4 max-h-[80vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b flex-shrink-0">
          <div>
            <h3 className="text-lg font-semibold flex items-center gap-2">
              <Link2 className="h-5 w-5 text-blue-500" />
              MR 연결 필요
            </h3>
            <p className="text-sm text-muted-foreground mt-1">
              <span className="font-medium">{issueKey}</span>를{' '}
              <span className="font-medium">"{transitionName}"</span>로 전환하려면 MR/PR 연결이 필요합니다
            </p>
          </div>
          <button onClick={onClose} className="p-1 hover:bg-muted rounded">
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Tab buttons */}
        <div className="flex border-b px-4 flex-shrink-0">
          <button
            onClick={() => setActiveTab('search')}
            className={cn(
              'px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors',
              activeTab === 'search'
                ? 'border-blue-500 text-blue-600'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            )}
          >
            <Search className="h-4 w-4 inline mr-2" />
            자동 검색 ({mrs.length})
          </button>
          <button
            onClick={() => setActiveTab('manual')}
            className={cn(
              'px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors',
              activeTab === 'manual'
                ? 'border-blue-500 text-blue-600'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            )}
          >
            <ExternalLink className="h-4 w-4 inline mr-2" />
            직접 입력
          </button>
        </div>

        {/* Content */}
        <div className="p-4 overflow-y-auto flex-1">
          {activeTab === 'search' ? (
            <>
              {isSearching ? (
                <div className="flex items-center justify-center py-8">
                  <Loader2 className="h-6 w-6 animate-spin text-blue-500 mr-2" />
                  <span className="text-muted-foreground">GitLab에서 관련 MR 검색 중...</span>
                </div>
              ) : hasMRs ? (
                <div className="space-y-2">
                  <p className="text-sm text-muted-foreground mb-3">
                    <CheckCircle className="h-4 w-4 inline mr-1 text-green-500" />
                    {issueKey}와 연관된 MR {mrs.length}개를 찾았습니다. 연결할 MR을 선택하세요.
                  </p>
                  {mrs.map((mr) => (
                    <button
                      key={`${mr.project}-${mr.iid}`}
                      onClick={() => handleSelectMR(mr)}
                      disabled={linkMutation.isPending}
                      className={cn(
                        'w-full p-3 rounded-lg border text-left transition-all',
                        'hover:border-blue-400 hover:bg-blue-50/50 dark:hover:bg-blue-900/20',
                        linkMutation.isPending && 'opacity-50 cursor-not-allowed'
                      )}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2">
                            <GitMerge className="h-4 w-4 text-purple-500 flex-shrink-0" />
                            <span className="font-medium text-sm truncate">
                              !{mr.iid} {mr.title}
                            </span>
                          </div>
                          <div className="flex items-center gap-2 mt-1 text-xs text-muted-foreground">
                            <span className="truncate">{mr.project}</span>
                            <span>|</span>
                            <span>{mr.source_branch} → {mr.target_branch}</span>
                          </div>
                        </div>
                        <div className="flex items-center gap-2 flex-shrink-0">
                          <span
                            className={cn(
                              'px-2 py-0.5 rounded-full text-xs font-medium',
                              getStateColor(mr.state)
                            )}
                          >
                            {mr.state === 'merged' ? 'Merged' : mr.state === 'opened' ? 'Open' : 'Closed'}
                          </span>
                          <ExternalLink className="h-3.5 w-3.5 text-muted-foreground" />
                        </div>
                      </div>
                      {mr.author && (
                        <div className="text-xs text-muted-foreground mt-1">
                          by {mr.author} · {new Date(mr.created_at).toLocaleDateString()}
                        </div>
                      )}
                    </button>
                  ))}
                </div>
              ) : (
                <div className="text-center py-8">
                  <AlertCircle className="h-12 w-12 text-amber-500 mx-auto mb-3" />
                  <p className="text-muted-foreground mb-2">
                    {issueKey}와 연관된 MR을 찾을 수 없습니다
                  </p>
                  <p className="text-sm text-muted-foreground mb-4">
                    MR 제목, 설명, 브랜치명에 "{issueKey}"가 포함되어 있어야 합니다
                  </p>
                  <button
                    onClick={() => setActiveTab('manual')}
                    className="text-blue-500 hover:underline text-sm"
                  >
                    직접 MR URL 입력하기 →
                  </button>
                </div>
              )}
            </>
          ) : (
            <div className="space-y-4">
              <p className="text-sm text-muted-foreground">
                GitLab MR 또는 GitHub PR URL을 직접 입력하세요.
              </p>

              <div>
                <label className="block text-sm font-medium mb-1">
                  MR/PR URL <span className="text-red-500">*</span>
                </label>
                <input
                  type="url"
                  value={manualUrl}
                  onChange={(e) => setManualUrl(e.target.value)}
                  placeholder="https://gitlab.example.com/group/project/-/merge_requests/123"
                  className="w-full px-3 py-2 border rounded-lg bg-background focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>

              <div>
                <label className="block text-sm font-medium mb-1">
                  제목 (선택)
                </label>
                <input
                  type="text"
                  value={manualTitle}
                  onChange={(e) => setManualTitle(e.target.value)}
                  placeholder={`MR: ${issueKey} 관련 변경사항`}
                  className="w-full px-3 py-2 border rounded-lg bg-background focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>

              <button
                onClick={handleManualLink}
                disabled={!manualUrl.trim() || linkMutation.isPending}
                className={cn(
                  'w-full py-2 rounded-lg font-medium transition-colors',
                  'bg-blue-600 text-white hover:bg-blue-700',
                  (!manualUrl.trim() || linkMutation.isPending) && 'opacity-50 cursor-not-allowed'
                )}
              >
                {linkMutation.isPending ? (
                  <span className="flex items-center justify-center gap-2">
                    <Loader2 className="h-4 w-4 animate-spin" />
                    연결 중...
                  </span>
                ) : (
                  <span className="flex items-center justify-center gap-2">
                    <Link2 className="h-4 w-4" />
                    MR 연결
                  </span>
                )}
              </button>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-2 p-4 border-t bg-muted/30 flex-shrink-0">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm text-muted-foreground hover:bg-muted rounded-lg transition-colors"
          >
            취소
          </button>
        </div>
      </div>
    </div>
  )
}
