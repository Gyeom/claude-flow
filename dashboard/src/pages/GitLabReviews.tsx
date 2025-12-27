import { useState, useEffect } from 'react'
import { gitlabReviewsApi } from '@/lib/api'
import type { GitLabReviewRecord, GitLabFeedbackStats, GitLabProject } from '@/types'

export function GitLabReviews() {
  const [projects, setProjects] = useState<GitLabProject[]>([])
  const [selectedProject, setSelectedProject] = useState<string>('')
  const [reviews, setReviews] = useState<GitLabReviewRecord[]>([])
  const [stats, setStats] = useState<GitLabFeedbackStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [selectedReview, setSelectedReview] = useState<GitLabReviewRecord | null>(null)
  const [comment, setComment] = useState('')
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    loadProjects()
    loadStats()
  }, [])

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

  const handleAddComment = async () => {
    if (!selectedReview || !comment.trim()) return

    setSubmitting(true)
    try {
      await gitlabReviewsApi.addComment(selectedReview.id, comment)
      setComment('')
      setSelectedReview(null)
      loadReviews()
    } catch (error) {
      console.error('Failed to add comment:', error)
    } finally {
      setSubmitting(false)
    }
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

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">GitLab MR Reviews</h1>
          <p className="text-muted-foreground">
            AI 코드 리뷰 기록 및 피드백 관리
          </p>
        </div>
      </div>

      {/* Stats Cards */}
      {stats && (
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <div className="bg-card rounded-lg border p-4">
            <div className="text-sm text-muted-foreground">Total Reviews</div>
            <div className="text-2xl font-bold">{stats.total}</div>
          </div>
          <div className="bg-card rounded-lg border p-4">
            <div className="text-sm text-muted-foreground">Positive Feedback</div>
            <div className="text-2xl font-bold text-green-600">{stats.positive} 👍</div>
          </div>
          <div className="bg-card rounded-lg border p-4">
            <div className="text-sm text-muted-foreground">Negative Feedback</div>
            <div className="text-2xl font-bold text-red-600">{stats.negative} 👎</div>
          </div>
          <div className="bg-card rounded-lg border p-4">
            <div className="text-sm text-muted-foreground">Satisfaction Rate</div>
            <div className="text-2xl font-bold">
              {(stats.satisfactionRate * 100).toFixed(1)}%
            </div>
          </div>
        </div>
      )}

      {/* Filters */}
      <div className="flex gap-4">
        <select
          value={selectedProject}
          onChange={(e) => setSelectedProject(e.target.value)}
          className="px-3 py-2 border rounded-lg bg-background"
        >
          <option value="">All Projects</option>
          {projects.map((project) => (
            <option key={project.id} value={project.id}>
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
            {reviews.map((review) => (
              <div key={review.id} className="p-4 hover:bg-muted/50">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-2 mb-2">
                      <span className="font-medium">MR #{review.mrIid}</span>
                      <span className="text-sm text-muted-foreground">
                        {review.projectId}
                      </span>
                      <span className="text-xs text-muted-foreground">
                        {formatDate(review.createdAt)}
                      </span>
                    </div>

                    {review.mrContext && (
                      <p className="text-sm text-muted-foreground mb-2">
                        {review.mrContext}
                      </p>
                    )}

                    <div className="text-sm bg-muted/30 p-3 rounded-lg max-h-32 overflow-y-auto">
                      <pre className="whitespace-pre-wrap font-mono text-xs">
                        {review.reviewContent.slice(0, 500)}
                        {review.reviewContent.length > 500 && '...'}
                      </pre>
                    </div>

                    {/* Feedback */}
                    {review.feedback && review.feedback.length > 0 && (
                      <div className="mt-2 flex gap-2">
                        {review.feedback.map((fb) => (
                          <span
                            key={fb.id}
                            className="inline-flex items-center gap-1 px-2 py-1 bg-muted rounded text-sm"
                            title={`User ${fb.userId} - ${fb.source}`}
                          >
                            {getFeedbackIcon(fb.reaction)}
                            {fb.comment && (
                              <span className="text-xs text-muted-foreground ml-1">
                                {fb.comment.slice(0, 30)}
                              </span>
                            )}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>

                  <button
                    onClick={() => setSelectedReview(review)}
                    className="ml-4 px-3 py-1 text-sm border rounded hover:bg-muted"
                  >
                    Add Comment
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Comment Modal */}
      {selectedReview && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-card rounded-lg border shadow-lg p-6 w-full max-w-lg mx-4">
            <h3 className="text-lg font-semibold mb-4">
              Add Comment to MR #{selectedReview.mrIid}
            </h3>

            <div className="mb-4 text-sm text-muted-foreground">
              {selectedReview.mrContext || `Project: ${selectedReview.projectId}`}
            </div>

            <textarea
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder="Enter your feedback comment..."
              className="w-full h-32 px-3 py-2 border rounded-lg bg-background resize-none"
            />

            <div className="flex justify-end gap-2 mt-4">
              <button
                onClick={() => {
                  setSelectedReview(null)
                  setComment('')
                }}
                className="px-4 py-2 border rounded hover:bg-muted"
              >
                Cancel
              </button>
              <button
                onClick={handleAddComment}
                disabled={submitting || !comment.trim()}
                className="px-4 py-2 bg-primary text-primary-foreground rounded hover:bg-primary/90 disabled:opacity-50"
              >
                {submitting ? 'Submitting...' : 'Submit'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
