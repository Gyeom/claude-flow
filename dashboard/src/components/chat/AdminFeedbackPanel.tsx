import { useState, useEffect } from 'react'
import { ThumbsUp, ThumbsDown, Star, Edit3, Save, X, AlertCircle } from 'lucide-react'
import { cn } from '@/lib/utils'
import { adminFeedbackApi, type IssueType } from '@/lib/api'

interface AdminFeedbackPanelProps {
  executionId: string
  onClose?: () => void
}

const ADMIN_ID = 'admin' // TODO: 실제 사용자 ID로 교체

export function AdminFeedbackPanel({ executionId, onClose }: AdminFeedbackPanelProps) {
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [issueTypes, setIssueTypes] = useState<IssueType[]>([])

  // Form state
  const [quickRating, setQuickRating] = useState<'POSITIVE' | 'NEGATIVE' | 'PENDING'>('PENDING')
  const [correctness, setCorrectness] = useState<number | null>(null)
  const [helpfulness, setHelpfulness] = useState<number | null>(null)
  const [verbosity, setVerbosity] = useState<number | null>(null)
  const [selectedIssues, setSelectedIssues] = useState<string[]>([])
  const [comment, setComment] = useState('')
  const [isExemplary, setIsExemplary] = useState(false)
  const [goldResponse, setGoldResponse] = useState('')
  const [showGoldEditor, setShowGoldEditor] = useState(false)

  // Load existing feedback and issue types
  useEffect(() => {
    const loadData = async () => {
      try {
        const [existing, types] = await Promise.all([
          adminFeedbackApi.getByExecutionId(executionId),
          adminFeedbackApi.getIssueTypes()
        ])

        setIssueTypes(types)

        if (existing) {
          setQuickRating(existing.quickRating as 'POSITIVE' | 'NEGATIVE' | 'PENDING')
          setCorrectness(existing.correctness)
          setHelpfulness(existing.helpfulness)
          setVerbosity(existing.verbosity)
          setSelectedIssues(existing.issues)
          setComment(existing.comment || '')
          setIsExemplary(existing.isExemplary)
          setGoldResponse(existing.goldResponse || '')
        }
      } catch (e) {
        console.error('Failed to load feedback data:', e)
      } finally {
        setLoading(false)
      }
    }

    loadData()
  }, [executionId])

  const handleSave = async () => {
    setSaving(true)
    try {
      await adminFeedbackApi.save({
        executionId,
        adminId: ADMIN_ID,
        quickRating,
        correctness: correctness ?? undefined,
        helpfulness: helpfulness ?? undefined,
        verbosity: verbosity ?? undefined,
        issues: selectedIssues,
        comment: comment || undefined,
        isExemplary,
        goldResponse: goldResponse || undefined
      })
      onClose?.()
    } catch (e) {
      console.error('Failed to save feedback:', e)
    } finally {
      setSaving(false)
    }
  }

  const toggleIssue = (issueName: string) => {
    setSelectedIssues(prev =>
      prev.includes(issueName)
        ? prev.filter(i => i !== issueName)
        : [...prev, issueName]
    )
  }

  if (loading) {
    return (
      <div className="p-4 text-center text-muted-foreground">
        로딩 중...
      </div>
    )
  }

  return (
    <div className="border rounded-lg bg-card p-4 space-y-4 shadow-lg">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="font-semibold text-sm">관리자 상세 평가</h3>
        {onClose && (
          <button onClick={onClose} className="p-1 hover:bg-muted rounded">
            <X className="h-4 w-4" />
          </button>
        )}
      </div>

      {/* Quick Rating */}
      <div className="space-y-2">
        <label className="text-xs text-muted-foreground">빠른 평가</label>
        <div className="flex gap-2">
          <button
            onClick={() => setQuickRating('POSITIVE')}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-all',
              quickRating === 'POSITIVE'
                ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                : 'bg-muted hover:bg-muted/80'
            )}
          >
            <ThumbsUp className="h-4 w-4" />
            좋음
          </button>
          <button
            onClick={() => setQuickRating('NEGATIVE')}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-all',
              quickRating === 'NEGATIVE'
                ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400'
                : 'bg-muted hover:bg-muted/80'
            )}
          >
            <ThumbsDown className="h-4 w-4" />
            개선필요
          </button>
        </div>
      </div>

      {/* Multi-dimensional Scores */}
      <div className="space-y-3">
        <label className="text-xs text-muted-foreground">품질 평가 (0-4)</label>

        <ScoreSlider
          label="정확성"
          value={correctness}
          onChange={setCorrectness}
        />
        <ScoreSlider
          label="유용성"
          value={helpfulness}
          onChange={setHelpfulness}
        />
        <ScoreSlider
          label="적절한 길이"
          value={verbosity}
          onChange={setVerbosity}
          labels={['너무 짧음', '', '적절', '', '너무 장황']}
        />
      </div>

      {/* Issue Tags */}
      {quickRating === 'NEGATIVE' && (
        <div className="space-y-2">
          <label className="text-xs text-muted-foreground flex items-center gap-1">
            <AlertCircle className="h-3 w-3" />
            문제 유형 (다중 선택)
          </label>
          <div className="flex flex-wrap gap-1.5">
            {issueTypes.map(issue => (
              <button
                key={issue.name}
                onClick={() => toggleIssue(issue.name)}
                title={issue.description}
                className={cn(
                  'px-2 py-1 text-xs rounded-full transition-all',
                  selectedIssues.includes(issue.name)
                    ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400'
                    : 'bg-muted hover:bg-muted/80'
                )}
              >
                {issue.displayName}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Comment */}
      <div className="space-y-2">
        <label className="text-xs text-muted-foreground">코멘트</label>
        <textarea
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          placeholder="개선점이나 좋았던 점을 기록하세요..."
          className="w-full px-3 py-2 text-sm rounded-md border bg-background resize-none h-20"
        />
      </div>

      {/* Exemplary & Gold Response */}
      <div className="space-y-3 pt-2 border-t">
        <div className="flex items-center gap-2">
          <button
            onClick={() => setIsExemplary(!isExemplary)}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-all',
              isExemplary
                ? 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400'
                : 'bg-muted hover:bg-muted/80'
            )}
          >
            <Star className={cn('h-4 w-4', isExemplary && 'fill-current')} />
            우수 사례로 저장
          </button>
          <button
            onClick={() => setShowGoldEditor(!showGoldEditor)}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-all',
              showGoldEditor || goldResponse
                ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400'
                : 'bg-muted hover:bg-muted/80'
            )}
          >
            <Edit3 className="h-4 w-4" />
            이상적 응답 작성
          </button>
        </div>

        {showGoldEditor && (
          <div className="space-y-2">
            <label className="text-xs text-muted-foreground">
              이상적인 응답 (Gold Standard)
            </label>
            <textarea
              value={goldResponse}
              onChange={(e) => setGoldResponse(e.target.value)}
              placeholder="이 질문에 대한 이상적인 응답을 작성하세요..."
              className="w-full px-3 py-2 text-sm rounded-md border bg-background resize-none h-32 font-mono"
            />
          </div>
        )}
      </div>

      {/* Save Button */}
      <div className="flex justify-end pt-2">
        <button
          onClick={handleSave}
          disabled={saving}
          className="flex items-center gap-1.5 px-4 py-2 bg-primary text-primary-foreground rounded-md text-sm font-medium hover:bg-primary/90 disabled:opacity-50"
        >
          <Save className="h-4 w-4" />
          {saving ? '저장 중...' : '저장'}
        </button>
      </div>
    </div>
  )
}

// Score Slider Component
function ScoreSlider({
  label,
  value,
  onChange,
  labels = ['0', '1', '2', '3', '4']
}: {
  label: string
  value: number | null
  onChange: (value: number | null) => void
  labels?: string[]
}) {
  return (
    <div className="flex items-center gap-3">
      <span className="text-xs w-16 text-muted-foreground">{label}</span>
      <div className="flex gap-1 flex-1">
        {[0, 1, 2, 3, 4].map((score) => (
          <button
            key={score}
            onClick={() => onChange(value === score ? null : score)}
            title={labels[score] || score.toString()}
            className={cn(
              'w-8 h-8 rounded-full text-xs font-medium transition-all',
              value === score
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted hover:bg-muted/80'
            )}
          >
            {score}
          </button>
        ))}
      </div>
      <span className="text-xs w-8 text-right text-muted-foreground">
        {value !== null ? `${value}/4` : '-'}
      </span>
    </div>
  )
}
