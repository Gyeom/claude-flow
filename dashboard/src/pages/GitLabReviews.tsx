import { useState, useEffect } from 'react'
import ReactMarkdown from 'react-markdown'
import { gitlabReviewsApi, systemApi } from '@/lib/api'
import type { GitLabReviewRecord, GitLabFeedbackStats, GitLabProject } from '@/types'

export function GitLabReviews() {
  const [projects, setProjects] = useState<GitLabProject[]>([])
  const [selectedProject, setSelectedProject] = useState<string>('')
  const [reviews, setReviews] = useState<GitLabReviewRecord[]>([])
  const [stats, setStats] = useState<GitLabFeedbackStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [expandedReviews, setExpandedReviews] = useState<Set<string>>(new Set())
  const [gitlabUrl, setGitlabUrl] = useState<string>('')

  useEffect(() => {
    loadProjects()
    loadStats()
    loadGitlabUrl()
  }, [])

  const loadGitlabUrl = async () => {
    try {
      const config = await systemApi.getEnvConfig()
      const gitlabVar = config.variables.find(v => v.key === 'GITLAB_URL')
      if (gitlabVar?.value) {
        setGitlabUrl(gitlabVar.value)
      }
    } catch (error) {
      console.error('Failed to load GitLab URL:', error)
    }
  }

  const getMrUrl = (projectPath: string, mrIid: number) => {
    if (!gitlabUrl) return null
    return `${gitlabUrl}/${projectPath}/-/merge_requests/${mrIid}`
  }

  const getNoteUrl = (projectPath: string, mrIid: number, noteId: number) => {
    if (!gitlabUrl) return null
    return `${gitlabUrl}/${projectPath}/-/merge_requests/${mrIid}#note_${noteId}`
  }

  useEffect(() => {
    loadReviews()
  }, [selectedProject])

  const loadProjects = async () => {
    try {
      const data = await gitlabReviewsApi.getProjects()
      setProjects(data)
    } catch (error) {
      console.error('Failed to load GitLab projects:', error)
    }
  }

  const loadReviews = async () => {
    setLoading(true)
    try {
      const data = await gitlabReviewsApi.getReviews(selectedProject || undefined)
      setReviews(data)
    } catch (error) {
      console.error('Failed to load reviews:', error)
      setReviews([])
    } finally {
      setLoading(false)
    }
  }

  const loadStats = async () => {
    try {
      const data = await gitlabReviewsApi.getStats()
      setStats(data)
    } catch (error) {
      console.error('Failed to load stats:', error)
    }
  }

  const toggleExpand = (reviewId: string) => {
    setExpandedReviews(prev => {
      const newSet = new Set(prev)
      if (newSet.has(reviewId)) {
        newSet.delete(reviewId)
      } else {
        newSet.add(reviewId)
      }
      return newSet
    })
  }

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleString('ko-KR', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  const getFeedbackIcon = (reaction: string) => {
    if (reaction === 'thumbsup' || reaction === '+1') return '👍'
    if (reaction === 'thumbsdown' || reaction === '-1') return '👎'
    return reaction
  }

  const getFeedbackBadge = (feedback: GitLabReviewRecord['feedback']) => {
    if (!feedback || feedback.length === 0) return null

    const positive = feedback.filter(f => f.reaction === 'thumbsup' || f.reaction === '+1').length
    const negative = feedback.filter(f => f.reaction === 'thumbsdown' || f.reaction === '-1').length

    return (
      <div className="flex items-center gap-2">
        {positive > 0 && (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400 rounded-full text-xs font-medium">
            👍 {positive}
          </span>
        )}
        {negative > 0 && (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400 rounded-full text-xs font-medium">
            👎 {negative}
          </span>
        )}
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">GitLab MR Reviews</h1>
          <p className="text-muted-foreground">
            AI 코드 리뷰 기록 및 이모지 피드백 (👍/👎)
          </p>
        </div>
      </div>

      {/* Stats Cards */}
      <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
        <div className="bg-card rounded-lg border p-4">
          <div className="text-sm text-muted-foreground">Total Reviews</div>
          <div className="text-2xl font-bold">{reviews.length}</div>
        </div>
        <div className="bg-card rounded-lg border p-4">
          <div className="text-sm text-muted-foreground">With Feedback</div>
          <div className="text-2xl font-bold text-blue-600">
            {stats ? stats.positive + stats.negative : 0}
          </div>
        </div>
        <div className="bg-card rounded-lg border p-4">
          <div className="text-sm text-muted-foreground">Positive</div>
          <div className="text-2xl font-bold text-green-600">{stats?.positive ?? 0} 👍</div>
        </div>
        <div className="bg-card rounded-lg border p-4">
          <div className="text-sm text-muted-foreground">Negative</div>
          <div className="text-2xl font-bold text-red-600">{stats?.negative ?? 0} 👎</div>
        </div>
        <div className="bg-card rounded-lg border p-4">
          <div className="text-sm text-muted-foreground">Satisfaction</div>
          <div className="text-2xl font-bold">
            {stats && (stats.positive + stats.negative) > 0
              ? (stats.satisfactionRate * 100).toFixed(0) + '%'
              : '-'}
          </div>
        </div>
      </div>

      {/* Filters */}
      <div className="flex gap-4">
        <select
          value={selectedProject}
          onChange={(e) => setSelectedProject(e.target.value)}
          className="px-3 py-2 border rounded-lg bg-background"
        >
          <option value="">All Projects</option>
          {projects.map((project) => (
            <option key={project.id} value={project.gitlabPath}>
              {project.name} ({project.gitlabPath})
            </option>
          ))}
        </select>
      </div>

      {/* Reviews List */}
      <div className="bg-card rounded-lg border">
        <div className="p-4 border-b">
          <h2 className="font-semibold">Review History</h2>
        </div>

        {loading ? (
          <div className="p-8 text-center text-muted-foreground">
            Loading reviews...
          </div>
        ) : reviews.length === 0 ? (
          <div className="p-8 text-center text-muted-foreground">
            No reviews found. Reviews will appear here after scheduled MR reviews run.
          </div>
        ) : (
          <div className="divide-y">
            {reviews.map((review) => {
              const isExpanded = expandedReviews.has(review.id)

              return (
                <div key={review.id} className="p-4 hover:bg-muted/30">
                  {/* Header */}
                  <div className="flex items-center gap-3 mb-3 flex-wrap">
                    {getMrUrl(review.projectId, review.mrIid) ? (
                      <a
                        href={getMrUrl(review.projectId, review.mrIid)!}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="font-semibold text-primary text-lg hover:underline"
                      >
                        MR #{review.mrIid} ↗
                      </a>
                    ) : (
                      <span className="font-semibold text-primary text-lg">MR #{review.mrIid}</span>
                    )}
                    <span className="text-sm text-muted-foreground px-2 py-0.5 bg-muted rounded">
                      {review.projectId}
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {formatDate(review.createdAt)}
                    </span>
                    {getNoteUrl(review.projectId, review.mrIid, review.noteId) && (
                      <a
                        href={getNoteUrl(review.projectId, review.mrIid, review.noteId)!}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-xs text-muted-foreground hover:text-primary"
                        title="View comment on GitLab"
                      >
                        💬 코멘트 보기
                      </a>
                    )}
                    {getFeedbackBadge(review.feedback)}
                  </div>

                  {/* MR Context (Title) */}
                  {review.mrContext && (
                    <h3 className="text-base font-medium mb-3 text-foreground">
                      {review.mrContext}
                    </h3>
                  )}

                  {/* Review Content - Markdown Rendered */}
                  <div
                    className={`prose prose-sm dark:prose-invert max-w-none
                      bg-muted/20 rounded-lg p-4 border
                      ${!isExpanded ? 'max-h-64 overflow-hidden relative' : ''}`}
                  >
                    <ReactMarkdown
                      components={{
                        // 테이블 스타일링
                        table: ({ children }) => (
                          <div className="overflow-x-auto my-2">
                            <table className="min-w-full border-collapse text-sm">
                              {children}
                            </table>
                          </div>
                        ),
                        thead: ({ children }) => (
                          <thead className="bg-muted/50">{children}</thead>
                        ),
                        th: ({ children }) => (
                          <th className="border border-border px-3 py-2 text-left font-semibold">
                            {children}
                          </th>
                        ),
                        td: ({ children }) => (
                          <td className="border border-border px-3 py-2">{children}</td>
                        ),
                        // 코드 블록 스타일링
                        code: ({ className, children, ...props }) => {
                          const isInline = !className
                          return isInline ? (
                            <code className="bg-muted px-1.5 py-0.5 rounded text-sm font-mono" {...props}>
                              {children}
                            </code>
                          ) : (
                            <code className={`${className} block bg-muted p-3 rounded-lg overflow-x-auto text-sm`} {...props}>
                              {children}
                            </code>
                          )
                        },
                        // 리스트 스타일링
                        ul: ({ children }) => (
                          <ul className="list-disc list-inside space-y-1 my-2">{children}</ul>
                        ),
                        ol: ({ children }) => (
                          <ol className="list-decimal list-inside space-y-1 my-2">{children}</ol>
                        ),
                        // 헤딩 스타일링
                        h1: ({ children }) => (
                          <h1 className="text-xl font-bold mt-4 mb-2 text-foreground">{children}</h1>
                        ),
                        h2: ({ children }) => (
                          <h2 className="text-lg font-bold mt-4 mb-2 text-foreground">{children}</h2>
                        ),
                        h3: ({ children }) => (
                          <h3 className="text-base font-semibold mt-3 mb-2 text-foreground">{children}</h3>
                        ),
                        // 링크 스타일링
                        a: ({ children, href }) => (
                          <a href={href} className="text-primary hover:underline" target="_blank" rel="noopener noreferrer">
                            {children}
                          </a>
                        ),
                        // 강조 스타일링
                        strong: ({ children }) => (
                          <strong className="font-semibold text-foreground">{children}</strong>
                        ),
                        // blockquote 스타일링
                        blockquote: ({ children }) => (
                          <blockquote className="border-l-4 border-primary/50 pl-4 italic text-muted-foreground my-2">
                            {children}
                          </blockquote>
                        ),
                      }}
                    >
                      {review.reviewContent}
                    </ReactMarkdown>

                    {/* Gradient overlay when collapsed */}
                    {!isExpanded && review.reviewContent.length > 500 && (
                      <div className="absolute bottom-0 left-0 right-0 h-16 bg-gradient-to-t from-muted/80 to-transparent" />
                    )}
                  </div>

                  {/* Expand/Collapse Button */}
                  {review.reviewContent.length > 500 && (
                    <button
                      onClick={() => toggleExpand(review.id)}
                      className="mt-3 px-4 py-1.5 text-sm font-medium text-primary border border-primary/30 rounded-lg hover:bg-primary/10 transition-colors"
                    >
                      {isExpanded ? '접기 ▲' : '전체 보기 ▼'}
                    </button>
                  )}

                  {/* Feedback Detail */}
                  {review.feedback && review.feedback.length > 0 && (
                    <div className="mt-4 pt-3 border-t border-border/50">
                      <div className="text-xs text-muted-foreground mb-2">피드백</div>
                      <div className="flex flex-wrap gap-2">
                        {review.feedback.map((fb) => (
                          <span
                            key={fb.id}
                            className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-muted rounded-full text-xs"
                            title={`User ${fb.userId} via ${fb.source}`}
                          >
                            <span className="text-base">{getFeedbackIcon(fb.reaction)}</span>
                            <span className="text-muted-foreground">
                              {fb.userId.length > 8 ? fb.userId.slice(0, 8) + '...' : fb.userId}
                            </span>
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
