import { useState, useMemo, useCallback } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  DndContext,
  DragOverlay,
  rectIntersection,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragStartEvent,
  type DragEndEvent,
} from '@dnd-kit/core'
import { useSortable, SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { useDroppable } from '@dnd-kit/core'
import { CSS } from '@dnd-kit/utilities'
import {
  Search,
  RefreshCw,
  ExternalLink,
  User,
  Clock,
  Tag,
  MessageSquare,
  Plus,
  ChevronRight,
  ChevronDown,
  AlertCircle,
  CheckCircle2,
  Circle,
  PlayCircle,
  Eye,
  Send,
  X,
  Loader2,
  List,
  Sparkles,
  FolderKanban,
  Columns3,
  Bug,
  Bookmark,
  Zap,
  FileText,
  ArrowUpCircle,
  ArrowDownCircle,
  MinusCircle,
  TrendingUp,
  Brain,
  Wand2,
  GripVertical,
  Bot,
} from 'lucide-react'
import { Card } from '@/components/Card'
import { jiraApi, type JiraIssueListItem, type JiraIssue, type JiraComment, type JiraProject, type JiraBoard, type JiraSprint } from '@/lib/api'
import { cn } from '@/lib/utils'

type AssigneeFilter = 'me' | 'all' | 'unassigned'
type DisplayMode = 'list' | 'board'

// 선택된 프로젝트 정보 타입
interface SelectedProject {
  key: string
  name: string
}

// 동적 상태 설정 - 실제 데이터에서 추출
const STATUS_CATEGORIES = {
  todo: ['To Do', 'Open', 'Backlog', '할 일', 'New', 'Postpone'],
  inProgress: ['In Progress', '진행 중', 'In Development', 'Working'],
  inReview: ['In Review', '검토 중', 'Review', 'Code Review', 'QA'],
  done: ['Done', 'Closed', '완료', '해결됨', 'Resolved', 'Released'],
}

// 상태별 카테고리 찾기
function getStatusCategory(status: string): keyof typeof STATUS_CATEGORIES {
  for (const [category, statuses] of Object.entries(STATUS_CATEGORIES)) {
    if (statuses.some(s => status.toLowerCase().includes(s.toLowerCase()) || s.toLowerCase().includes(status.toLowerCase()))) {
      return category as keyof typeof STATUS_CATEGORIES
    }
  }
  return 'todo'
}

// 상태 카테고리별 스타일
const categoryStyles = {
  todo: { icon: Circle, color: 'text-slate-500', bg: 'bg-slate-100 dark:bg-slate-800', border: 'border-slate-200 dark:border-slate-700' },
  inProgress: { icon: PlayCircle, color: 'text-blue-500', bg: 'bg-blue-50 dark:bg-blue-900/30', border: 'border-blue-200 dark:border-blue-800' },
  inReview: { icon: Eye, color: 'text-purple-500', bg: 'bg-purple-50 dark:bg-purple-900/30', border: 'border-purple-200 dark:border-purple-800' },
  done: { icon: CheckCircle2, color: 'text-emerald-500', bg: 'bg-emerald-50 dark:bg-emerald-900/30', border: 'border-emerald-200 dark:border-emerald-800' },
}

// 이슈 타입 아이콘
const issueTypeIcons: Record<string, React.ElementType> = {
  'Bug': Bug,
  '버그': Bug,
  'Story': Bookmark,
  '스토리': Bookmark,
  'Epic': Zap,
  '에픽': Zap,
  'Task': FileText,
  '작업': FileText,
  '하위 작업': FileText,
}

// 우선순위 스타일
const priorityConfig: Record<string, { icon: React.ElementType; color: string; label: string }> = {
  'Highest': { icon: ArrowUpCircle, color: 'text-red-500', label: 'Highest' },
  'P1 - Critical': { icon: ArrowUpCircle, color: 'text-red-500', label: 'Critical' },
  'High': { icon: ArrowUpCircle, color: 'text-orange-500', label: 'High' },
  'P2 - Medium': { icon: MinusCircle, color: 'text-yellow-500', label: 'Medium' },
  'Medium': { icon: MinusCircle, color: 'text-yellow-500', label: 'Medium' },
  'Low': { icon: ArrowDownCircle, color: 'text-blue-500', label: 'Low' },
  'P3 - Low': { icon: ArrowDownCircle, color: 'text-blue-400', label: 'Low' },
  'Lowest': { icon: ArrowDownCircle, color: 'text-slate-400', label: 'Lowest' },
}

export function Jira() {
  const queryClient = useQueryClient()

  // Filter states (Option B: 모든 것을 필터로)
  const [assigneeFilter, setAssigneeFilter] = useState<AssigneeFilter>('me')
  const [selectedProjects, setSelectedProjects] = useState<SelectedProject[]>([]) // 다중 프로젝트 선택
  const [selectedBoard, setSelectedBoard] = useState<JiraBoard | null>(null)
  const [selectedSprint, setSelectedSprint] = useState<JiraSprint | null>(null)

  // UI states
  const [displayMode, setDisplayMode] = useState<DisplayMode>('board')
  const [searchJql, setSearchJql] = useState('')
  const [selectedIssue, setSelectedIssue] = useState<string | null>(null)
  const [newComment, setNewComment] = useState('')
  const [showCreateModal, setShowCreateModal] = useState(false)
  const [showProjectSelector, setShowProjectSelector] = useState(false)
  const [projectSearch, setProjectSearch] = useState('')
  const [showSprintSelector, setShowSprintSelector] = useState(false)
  const [sprintSearch, setSprintSearch] = useState('')
  const [showAssigneeSelector, setShowAssigneeSelector] = useState(false)
  const [isAnalyzing, setIsAnalyzing] = useState(false)
  const [analysisResult, setAnalysisResult] = useState<string | null>(null)

  // Drag & Drop states
  const [activeId, setActiveId] = useState<string | null>(null)
  const [transitionDialog, setTransitionDialog] = useState<{
    issueKey: string
    currentStatus: string
    targetStatus: string
    availableTransitions: string[]
    requiresFields?: boolean
    selectedTransition?: string
  } | null>(null)
  const [transitionDates, setTransitionDates] = useState<{
    startDate: string
    dueDate: string
  }>({ startDate: '', dueDate: '' })

  // Natural Language Processing states
  const [showNlpPanel, setShowNlpPanel] = useState(false)
  const [nlpInput, setNlpInput] = useState('')
  const [nlpProcessing, setNlpProcessing] = useState(false)
  const [nlpResult, setNlpResult] = useState<{
    action: string
    issueKey?: string
    status?: string
    message: string
    success: boolean
  } | null>(null)

  // DnD sensors
  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: {
        distance: 8,
      },
    }),
    useSensor(KeyboardSensor)
  )

  // Fetch projects
  const { data: projectsData, isLoading: projectsLoading } = useQuery({
    queryKey: ['jira', 'projects'],
    queryFn: () => jiraApi.getProjects(),
  })

  const allProjects = projectsData?.data || []

  // 프로젝트 검색 필터링
  const projects = useMemo(() => {
    if (!projectSearch.trim()) return allProjects
    const search = projectSearch.toLowerCase()
    return allProjects.filter(p =>
      p.key.toLowerCase().includes(search) ||
      p.name.toLowerCase().includes(search)
    )
  }, [allProjects, projectSearch])

  // Fetch boards for all selected projects
  const { data: boardsData, isLoading: boardsLoading } = useQuery({
    queryKey: ['jira', 'boards', selectedProjects.map(p => p.key).join(',')],
    queryFn: async () => {
      if (selectedProjects.length === 0) return { data: [] }
      // 선택된 모든 프로젝트의 보드를 병렬로 가져옴
      const results = await Promise.all(
        selectedProjects.map(p => jiraApi.getBoards(p.key))
      )
      // 모든 결과를 하나로 합침
      const allBoards = results.flatMap(r => r.data || [])
      return { data: allBoards }
    },
    enabled: selectedProjects.length > 0,
  })

  const allBoards = boardsData?.data || []

  // 보드를 프로젝트별로 그룹화
  const boardsByProject = useMemo(() => {
    const grouped: Record<string, { project: SelectedProject; boards: JiraBoard[] }> = {}
    for (const p of selectedProjects) {
      grouped[p.key] = { project: p, boards: [] }
    }
    for (const board of allBoards) {
      const projectKey = board.projectKey || ''
      if (grouped[projectKey]) {
        grouped[projectKey].boards.push(board)
      }
    }
    return grouped
  }, [allBoards, selectedProjects])

  // Fetch sprints for selected board
  const { data: sprintsData, isLoading: sprintsLoading } = useQuery({
    queryKey: ['jira', 'sprints', selectedBoard?.id],
    queryFn: () => jiraApi.getSprints(selectedBoard!.id),
    enabled: !!selectedBoard,
  })

  const sprints = sprintsData?.data || []

  // Build JQL based on filters
  const buildJql = useMemo(() => {
    const conditions: string[] = []

    // Assignee filter
    if (assigneeFilter === 'me') {
      conditions.push('assignee = currentUser()')
    } else if (assigneeFilter === 'unassigned') {
      conditions.push('assignee is EMPTY')
    }
    // 'all' - no assignee filter, show all issues

    // Project filter (다중 프로젝트 지원)
    if (selectedProjects.length === 1) {
      conditions.push(`project = ${selectedProjects[0].key}`)
    } else if (selectedProjects.length > 1) {
      const projectKeys = selectedProjects.map(p => p.key).join(', ')
      conditions.push(`project IN (${projectKeys})`)
    }

    // Sprint filter (if sprint selected, use sprint issues API instead)
    // Sprint is handled separately via getSprintIssues

    // Build final JQL: conditions joined by AND, then ORDER BY
    // If no conditions, use "project is not EMPTY" to get all issues
    const jql = conditions.length > 0
      ? `${conditions.join(' AND ')} ORDER BY updated DESC`
      : 'project is not EMPTY ORDER BY updated DESC'
    return jql
  }, [assigneeFilter, selectedProjects])

  // Determine if we should use sprint API or search API
  const useSprintApi = !!selectedSprint

  // Fetch issues via search (when no sprint selected)
  const { data: searchIssues, isLoading: searchLoading } = useQuery({
    queryKey: ['jira', 'search', buildJql],
    queryFn: () => jiraApi.searchIssues(buildJql),
    enabled: !useSprintApi && !searchJql, // 검색어가 없고 스프린트도 없을 때
  })

  // Fetch sprint issues (when sprint selected)
  const { data: sprintIssues, isLoading: sprintLoading } = useQuery({
    queryKey: ['jira', 'sprint', selectedBoard?.id, selectedSprint?.id, assigneeFilter],
    queryFn: () => jiraApi.getSprintIssues(selectedBoard?.id, selectedSprint?.id),
    enabled: useSprintApi,
  })

  // Manual JQL search
  const { data: manualSearchResults, isLoading: manualSearchLoading, refetch: executeSearch } = useQuery({
    queryKey: ['jira', 'manual-search', searchJql],
    queryFn: () => jiraApi.searchIssues(searchJql),
    enabled: !!searchJql,
  })

  // Fetch selected issue details
  const { data: issueDetails, isLoading: detailsLoading } = useQuery({
    queryKey: ['jira', 'issue', selectedIssue],
    queryFn: () => jiraApi.getIssue(selectedIssue!),
    enabled: !!selectedIssue,
  })

  // Fetch comments for selected issue
  const { data: comments } = useQuery({
    queryKey: ['jira', 'comments', selectedIssue],
    queryFn: () => jiraApi.getComments(selectedIssue!),
    enabled: !!selectedIssue,
  })

  // Add comment mutation
  const addCommentMutation = useMutation({
    mutationFn: ({ issueKey, comment }: { issueKey: string; comment: string }) =>
      jiraApi.addComment(issueKey, comment),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['jira', 'comments', selectedIssue] })
      setNewComment('')
    },
  })

  // Transition mutation
  const transitionMutation = useMutation({
    mutationFn: ({
      issueKey,
      status,
      dueDate,
      startDate,
    }: {
      issueKey: string
      status: string
      dueDate?: string
      startDate?: string
    }) => jiraApi.transitionIssue(issueKey, status, { dueDate, startDate }),
    onSuccess: (data, variables) => {
      if (data.success) {
        toast.success(`${variables.issueKey}를 "${variables.status}"로 이동했습니다`)
        queryClient.invalidateQueries({ queryKey: ['jira'] })
        setTransitionDialog(null)
        setTransitionDates({ startDate: '', dueDate: '' })
      } else {
        const errorMsg = data.error || ''

        // 필수 필드 에러 감지 (기한, Start date)
        if (errorMsg.includes('필드') && (errorMsg.includes('기한') || errorMsg.includes('Start date') || errorMsg.includes('date'))) {
          const issue = currentIssues.find(i => i.key === variables.issueKey)
          // 날짜 입력을 요청하는 다이얼로그 표시
          setTransitionDialog({
            issueKey: variables.issueKey,
            currentStatus: issue?.status || 'Unknown',
            targetStatus: variables.status,
            availableTransitions: [],
            requiresFields: true,
            selectedTransition: variables.status,
          })
          return
        }

        // 가능한 전환 목록 추출
        const availableMatch = errorMsg.match(/Available:\s*(.+)$/)
        const availableTransitions = availableMatch
          ? availableMatch[1].split(',').map((s: string) => s.trim())
          : []

        if (availableTransitions.length > 0) {
          const issue = currentIssues.find(i => i.key === variables.issueKey)
          setTransitionDialog({
            issueKey: variables.issueKey,
            currentStatus: issue?.status || 'Unknown',
            targetStatus: variables.status,
            availableTransitions,
          })
        } else {
          toast.error(data.error || '상태 변경에 실패했습니다')
        }
      }
    },
    onError: () => {
      toast.error('상태 변경 중 오류가 발생했습니다')
    },
  })

  // Natural Language Processing handler
  const handleNlpSubmit = async () => {
    if (!nlpInput.trim()) return

    setNlpProcessing(true)
    setNlpResult(null)

    try {
      // 자연어 명령 파싱 및 실행
      const input = nlpInput.toLowerCase().trim()

      // 패턴 매칭으로 의도 파악
      let action = ''
      let issueKey = ''
      let targetStatus = ''

      // 이슈 키 추출 (예: CCDC-123, ABC-1)
      const issueKeyMatch = nlpInput.match(/([A-Z]+-\d+)/i)
      if (issueKeyMatch) {
        issueKey = issueKeyMatch[1].toUpperCase()
      }

      // 상태 변경 패턴
      if (input.includes('진행') || input.includes('시작') || input.includes('start') || input.includes('progress')) {
        action = 'transition'
        targetStatus = 'In Progress'
      } else if (input.includes('완료') || input.includes('done') || input.includes('finish') || input.includes('close')) {
        action = 'transition'
        targetStatus = 'Done'
      } else if (input.includes('리뷰') || input.includes('review') || input.includes('검토')) {
        action = 'transition'
        targetStatus = 'In Review'
      } else if (input.includes('백로그') || input.includes('backlog')) {
        action = 'transition'
        targetStatus = 'Backlog'
      } else if (input.includes('할 일') || input.includes('todo') || input.includes('to do')) {
        action = 'transition'
        targetStatus = 'To Do'
      } else if (input.includes('코멘트') || input.includes('comment') || input.includes('댓글')) {
        action = 'comment'
      } else if (input.includes('분석') || input.includes('analyze') || input.includes('요약')) {
        action = 'analyze'
      } else if (input.includes('생성') || input.includes('create') || input.includes('만들')) {
        action = 'create'
      }

      // 액션 실행
      if (action === 'transition' && issueKey && targetStatus) {
        await transitionMutation.mutateAsync({ issueKey, status: targetStatus })
        setNlpResult({
          action: 'transition',
          issueKey,
          status: targetStatus,
          message: `${issueKey}를 "${targetStatus}" 상태로 변경했습니다.`,
          success: true,
        })
      } else if (action === 'analyze' && issueKey) {
        setSelectedIssue(issueKey)
        handleAnalyzeIssue(issueKey)
        setNlpResult({
          action: 'analyze',
          issueKey,
          message: `${issueKey} 분석을 시작합니다...`,
          success: true,
        })
      } else if (action === 'create') {
        setShowCreateModal(true)
        setNlpResult({
          action: 'create',
          message: '새 이슈 생성 창을 열었습니다.',
          success: true,
        })
      } else if (!issueKey && action) {
        setNlpResult({
          action,
          message: '이슈 키를 찾을 수 없습니다. 예: "CCDC-123 진행 중으로 변경"',
          success: false,
        })
      } else {
        // AI에게 더 복잡한 처리 위임 (향후 확장)
        setNlpResult({
          action: 'unknown',
          message: `명령을 이해하지 못했습니다. 다음과 같이 시도해보세요:\n• "CCDC-123 진행 중으로 변경"\n• "ABC-456 완료 처리"\n• "PROJ-789 분석해줘"`,
          success: false,
        })
      }
    } catch (error) {
      setNlpResult({
        action: 'error',
        message: `오류가 발생했습니다: ${error instanceof Error ? error.message : '알 수 없는 오류'}`,
        success: false,
      })
    } finally {
      setNlpProcessing(false)
    }
  }

  // Get current issues based on filters
  const currentIssues = useMemo(() => {
    // Priority: manual search > sprint > filter-based search
    if (searchJql) {
      return manualSearchResults?.data || []
    }
    if (useSprintApi) {
      let issues = sprintIssues?.data || []
      // Apply assignee filter to sprint issues client-side
      if (assigneeFilter === 'unassigned') {
        issues = issues.filter(i => !i.assignee)
      }
      // Note: 'me' filter는 서버에서 처리하기 어려우므로 클라이언트에서는 스킵
      // 실제로는 currentUser 이름을 알아야 하므로 여기서는 all로 처리
      return issues
    }
    return searchIssues?.data || []
  }, [searchJql, useSprintApi, manualSearchResults, sprintIssues, searchIssues, assigneeFilter])

  const isLoading = searchJql ? manualSearchLoading
    : useSprintApi ? sprintLoading
    : searchLoading

  // Drag & Drop handlers (currentIssues 이후에 정의)
  const handleDragStart = useCallback((event: DragStartEvent) => {
    setActiveId(event.active.id as string)
  }, [])

  const handleDragEnd = useCallback((event: DragEndEvent) => {
    const { active, over } = event
    setActiveId(null)

    if (!over) return

    const issueKey = active.id as string
    const overId = over.id as string

    // over.data.current.status가 있으면 사용 (StatusColumn에서 제공)
    let newStatus: string

    if (over.data?.current?.status) {
      // StatusColumn에서 드롭 - data에서 status 가져옴
      newStatus = over.data.current.status as string
    } else {
      // 이슈 키인지 확인: "ABC-123" 형태
      const isIssueKey = /^[A-Z]+-\d+$/.test(overId)

      if (isIssueKey) {
        // 이슈 위에 드롭한 경우, 해당 이슈의 상태를 가져옴
        const targetIssue = currentIssues.find(i => i.key === overId)
        if (!targetIssue) return
        newStatus = targetIssue.status
      } else {
        // 상태 컬럼명으로 직접 드롭 (단일 프로젝트 모드)
        newStatus = overId
      }
    }

    // 현재 이슈의 상태 찾기
    const issue = currentIssues.find(i => i.key === issueKey)
    if (!issue || issue.status === newStatus) return

    // 상태 변경 실행
    transitionMutation.mutate({ issueKey, status: newStatus })
  }, [currentIssues, transitionMutation])

  const handleDragCancel = useCallback(() => {
    setActiveId(null)
  }, [])

  // 드래그 중인 이슈 찾기
  const activeIssue = useMemo(() => {
    if (!activeId) return null
    return currentIssues.find(i => i.key === activeId)
  }, [activeId, currentIssues])

  // 기본 상태 컬럼 (항상 표시) + 데이터에서 추출된 추가 상태
  const DEFAULT_STATUS_COLUMNS = ['Backlog', 'To Do', 'In Progress', 'In Review', 'Done']

  // 동적으로 상태 컬럼 생성 - 기본 컬럼 + 실제 데이터에서 추출
  const { statusColumns, issuesByStatus, issuesByProject } = useMemo(() => {
    const statusMap = new Map<string, JiraIssueListItem[]>()
    const projectMap = new Map<string, { name: string; issues: JiraIssueListItem[] }>()
    const categoryOrder: (keyof typeof STATUS_CATEGORIES)[] = ['todo', 'inProgress', 'inReview', 'done']

    // 기본 상태 컬럼 초기화 (빈 배열로)
    DEFAULT_STATUS_COLUMNS.forEach(status => {
      statusMap.set(status, [])
    })

    // 모든 이슈를 상태별 + 프로젝트별로 그룹화
    currentIssues.forEach(issue => {
      const status = issue.status || 'Unknown'
      if (!statusMap.has(status)) {
        statusMap.set(status, [])
      }
      statusMap.get(status)!.push(issue)

      // 프로젝트별 그룹화 (issue.key에서 프로젝트 키 추출)
      const projectKey = issue.key.split('-')[0]
      if (!projectMap.has(projectKey)) {
        projectMap.set(projectKey, { name: projectKey, issues: [] })
      }
      projectMap.get(projectKey)!.issues.push(issue)
    })

    // 카테고리별로 상태 정렬
    const sortedStatuses = Array.from(statusMap.keys()).sort((a, b) => {
      const catA = categoryOrder.indexOf(getStatusCategory(a))
      const catB = categoryOrder.indexOf(getStatusCategory(b))
      return catA - catB
    })

    return {
      statusColumns: sortedStatuses,
      issuesByStatus: Object.fromEntries(statusMap),
      issuesByProject: Object.fromEntries(projectMap),
    }
  }, [currentIssues])

  // 프로젝트별 + 상태별 이슈 매핑 (Board View용)
  const issuesByProjectAndStatus = useMemo(() => {
    const result: Record<string, Record<string, JiraIssueListItem[]>> = {}

    Object.entries(issuesByProject).forEach(([projectKey, { issues }]) => {
      result[projectKey] = {}
      statusColumns.forEach(status => {
        result[projectKey][status] = issues.filter(issue => issue.status === status)
      })
    })

    return result
  }, [issuesByProject, statusColumns])

  // 통계 계산
  const stats = useMemo(() => {
    const total = currentIssues.length
    const byCategory = {
      todo: 0,
      inProgress: 0,
      inReview: 0,
      done: 0,
    }
    currentIssues.forEach(issue => {
      const cat = getStatusCategory(issue.status)
      byCategory[cat]++
    })
    return { total, ...byCategory }
  }, [currentIssues])

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault()
    if (searchJql) {
      executeSearch()
    }
  }

  const handleProjectSelect = (project: JiraProject) => {
    const isSelected = selectedProjects.some(p => p.key === project.key)
    if (isSelected) {
      // 이미 선택된 프로젝트면 제거
      setSelectedProjects(prev => prev.filter(p => p.key !== project.key))
    } else {
      // 새 프로젝트 추가
      setSelectedProjects(prev => [...prev, { key: project.key, name: project.name }])
    }
    // 프로젝트 변경 시 보드/스프린트 선택 초기화
    setSelectedBoard(null)
    setSelectedSprint(null)
    setSearchJql('') // 검색어 초기화
  }

  const handleProjectRemove = (projectKey: string) => {
    setSelectedProjects(prev => prev.filter(p => p.key !== projectKey))
    // 프로젝트가 모두 제거되면 보드/스프린트 초기화
    setSelectedBoard(null)
    setSelectedSprint(null)
  }

  const handleSprintSelect = (sprint: JiraSprint) => {
    setSelectedSprint(sprint)
    setShowSprintSelector(false)
    setSprintSearch('')
    setSearchJql('') // 검색어 초기화
  }

  const handleClearFilters = () => {
    setAssigneeFilter('me')
    setSelectedProjects([])
    setSelectedBoard(null)
    setSelectedSprint(null)
    setSearchJql('')
  }

  // 현재 활성 필터 설명 생성
  const filterDescription = useMemo(() => {
    const parts: string[] = []
    if (assigneeFilter === 'me') parts.push('My Issues')
    else if (assigneeFilter === 'unassigned') parts.push('Unassigned')
    else parts.push('All Issues')

    if (selectedProjects.length === 1) {
      parts.push(selectedProjects[0].key)
    } else if (selectedProjects.length > 1) {
      parts.push(`${selectedProjects.length} projects`)
    }
    if (selectedSprint) parts.push(selectedSprint.name)
    if (searchJql) parts.push(`Search: "${searchJql}"`)

    return parts.join(' · ')
  }, [assigneeFilter, selectedProjects, selectedSprint, searchJql])

  // AI 분석 핸들러
  const handleAnalyzeIssue = async (issueKey: string) => {
    setIsAnalyzing(true)
    setAnalysisResult(null)
    try {
      const result = await jiraApi.analyzeIssue(issueKey)
      if (result.success && result.analysis) {
        setAnalysisResult(result.analysis)
      }
    } catch (error) {
      console.error('Analysis failed:', error)
    } finally {
      setIsAnalyzing(false)
    }
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-blue-600 rounded-xl flex items-center justify-center shadow-lg">
              <FolderKanban className="h-5 w-5 text-white" />
            </div>
            <div>
              <h1 className="text-2xl font-bold">Jira</h1>
              <p className="text-sm text-muted-foreground">
                {filterDescription}
              </p>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {/* AI Natural Language Button */}
          <button
            onClick={() => setShowNlpPanel(!showNlpPanel)}
            className={cn(
              "flex items-center gap-2 px-4 py-2 rounded-lg transition-colors shadow-sm",
              showNlpPanel
                ? "bg-purple-600 text-white"
                : "bg-gradient-to-r from-purple-500 to-blue-500 text-white hover:from-purple-600 hover:to-blue-600"
            )}
          >
            <Bot className="h-4 w-4" />
            <span className="hidden sm:inline">AI 명령</span>
          </button>
          <button
            onClick={() => setShowCreateModal(true)}
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors shadow-sm"
          >
            <Plus className="h-4 w-4" />
            <span className="hidden sm:inline">New Issue</span>
          </button>
          <button
            onClick={() => queryClient.invalidateQueries({ queryKey: ['jira'] })}
            className="p-2 hover:bg-muted rounded-lg transition-colors"
            title="Refresh"
          >
            <RefreshCw className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Natural Language Processing Panel */}
      {showNlpPanel && (
        <Card className="p-4 bg-gradient-to-r from-purple-50 to-blue-50 dark:from-purple-900/20 dark:to-blue-900/20 border-purple-200 dark:border-purple-800">
          <div className="flex items-start gap-4">
            <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-blue-500 rounded-xl flex items-center justify-center flex-shrink-0">
              <Bot className="h-5 w-5 text-white" />
            </div>
            <div className="flex-1 space-y-3">
              <div>
                <h3 className="font-semibold text-sm mb-1">자연어로 Jira 제어하기</h3>
                <p className="text-xs text-muted-foreground">
                  이슈 키와 함께 명령을 입력하세요. 예: "CCDC-123 진행 중으로 변경", "ABC-456 완료 처리"
                </p>
              </div>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={nlpInput}
                  onChange={(e) => setNlpInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !nlpProcessing) {
                      handleNlpSubmit()
                    }
                  }}
                  placeholder="CCDC-123 진행 중으로 변경해줘..."
                  className="flex-1 px-4 py-2 rounded-lg border bg-background text-sm focus:ring-2 focus:ring-purple-500/50 focus:border-purple-500"
                  disabled={nlpProcessing}
                />
                <button
                  onClick={handleNlpSubmit}
                  disabled={!nlpInput.trim() || nlpProcessing}
                  className="px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:opacity-50 transition-colors flex items-center gap-2"
                >
                  {nlpProcessing ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Send className="h-4 w-4" />
                  )}
                </button>
              </div>

              {/* Quick Commands */}
              <div className="flex flex-wrap gap-2">
                <span className="text-xs text-muted-foreground">빠른 명령:</span>
                {[
                  { label: '진행 중', cmd: '진행 중으로 변경' },
                  { label: '완료', cmd: '완료 처리' },
                  { label: '리뷰', cmd: '리뷰 요청' },
                  { label: '분석', cmd: '분석해줘' },
                ].map((item) => (
                  <button
                    key={item.label}
                    onClick={() => setNlpInput(prev => `${prev} ${item.cmd}`.trim())}
                    className="px-2 py-1 text-xs bg-white dark:bg-gray-800 border rounded-md hover:bg-muted transition-colors"
                  >
                    {item.label}
                  </button>
                ))}
              </div>

              {/* Result */}
              {nlpResult && (
                <div className={cn(
                  "p-3 rounded-lg text-sm",
                  nlpResult.success
                    ? "bg-green-100 dark:bg-green-900/30 text-green-800 dark:text-green-200"
                    : "bg-red-100 dark:bg-red-900/30 text-red-800 dark:text-red-200"
                )}>
                  <div className="flex items-start gap-2">
                    {nlpResult.success ? (
                      <CheckCircle2 className="h-4 w-4 flex-shrink-0 mt-0.5" />
                    ) : (
                      <AlertCircle className="h-4 w-4 flex-shrink-0 mt-0.5" />
                    )}
                    <div className="whitespace-pre-wrap">{nlpResult.message}</div>
                  </div>
                </div>
              )}
            </div>
            <button
              onClick={() => {
                setShowNlpPanel(false)
                setNlpResult(null)
                setNlpInput('')
              }}
              className="p-1 hover:bg-muted rounded transition-colors"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        </Card>
      )}

      {/* Stats Bar */}
      <div className="grid grid-cols-5 gap-4">
        <Card className="p-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-muted-foreground">Total</p>
              <p className="text-2xl font-bold">{stats.total}</p>
            </div>
            <div className="w-10 h-10 bg-slate-100 dark:bg-slate-800 rounded-lg flex items-center justify-center">
              <FileText className="h-5 w-5 text-slate-600 dark:text-slate-400" />
            </div>
          </div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-muted-foreground">To Do</p>
              <p className="text-2xl font-bold text-slate-600">{stats.todo}</p>
            </div>
            <div className="w-10 h-10 bg-slate-100 dark:bg-slate-800 rounded-lg flex items-center justify-center">
              <Circle className="h-5 w-5 text-slate-500" />
            </div>
          </div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-muted-foreground">In Progress</p>
              <p className="text-2xl font-bold text-blue-600">{stats.inProgress}</p>
            </div>
            <div className="w-10 h-10 bg-blue-100 dark:bg-blue-900/30 rounded-lg flex items-center justify-center">
              <PlayCircle className="h-5 w-5 text-blue-500" />
            </div>
          </div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-muted-foreground">In Review</p>
              <p className="text-2xl font-bold text-purple-600">{stats.inReview}</p>
            </div>
            <div className="w-10 h-10 bg-purple-100 dark:bg-purple-900/30 rounded-lg flex items-center justify-center">
              <Eye className="h-5 w-5 text-purple-500" />
            </div>
          </div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-muted-foreground">Done</p>
              <p className="text-2xl font-bold text-emerald-600">{stats.done}</p>
            </div>
            <div className="w-10 h-10 bg-emerald-100 dark:bg-emerald-900/30 rounded-lg flex items-center justify-center">
              <CheckCircle2 className="h-5 w-5 text-emerald-500" />
            </div>
          </div>
        </Card>
      </div>

      {/* Toolbar - Filter-based UI */}
      <Card className="p-3">
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          {/* Left: Filters */}
          <div className="flex items-center gap-2 flex-wrap flex-1">
            {/* Assignee Filter */}
            <div className="relative">
              <button
                onClick={() => setShowAssigneeSelector(!showAssigneeSelector)}
                className={cn(
                  "flex items-center gap-2 px-3 py-1.5 rounded-lg border transition-all",
                  assigneeFilter === 'me'
                    ? "bg-blue-50 dark:bg-blue-900/20 border-blue-200 dark:border-blue-800 text-blue-700 dark:text-blue-300"
                    : "border-border hover:bg-muted"
                )}
              >
                <User className="h-4 w-4" />
                <span className="text-sm font-medium">
                  {assigneeFilter === 'me' ? 'My Issues' : assigneeFilter === 'unassigned' ? 'Unassigned' : 'All Issues'}
                </span>
                <ChevronDown className={cn("h-4 w-4 transition-transform", showAssigneeSelector && "rotate-180")} />
              </button>

              {showAssigneeSelector && (
                <div className="absolute top-full left-0 mt-1 w-48 bg-background border rounded-lg shadow-lg z-50 overflow-hidden">
                  {[
                    { value: 'me' as const, label: 'My Issues', desc: 'Assigned to me' },
                    { value: 'all' as const, label: 'All Issues', desc: 'Everyone' },
                    { value: 'unassigned' as const, label: 'Unassigned', desc: 'No assignee' },
                  ].map((option) => (
                    <button
                      key={option.value}
                      onClick={() => {
                        setAssigneeFilter(option.value)
                        setShowAssigneeSelector(false)
                      }}
                      className={cn(
                        "w-full px-3 py-2 text-left hover:bg-muted transition-colors",
                        assigneeFilter === option.value && "bg-blue-50 dark:bg-blue-900/20"
                      )}
                    >
                      <div className="text-sm font-medium">{option.label}</div>
                      <div className="text-xs text-muted-foreground">{option.desc}</div>
                    </button>
                  ))}
                </div>
              )}
            </div>

            {/* Project Filter - Multi-select */}
            <div className="relative">
              <button
                onClick={() => setShowProjectSelector(!showProjectSelector)}
                className={cn(
                  "flex items-center gap-2 px-3 py-1.5 rounded-lg border transition-all",
                  selectedProjects.length > 0
                    ? "bg-blue-50 dark:bg-blue-900/20 border-blue-200 dark:border-blue-800 text-blue-700 dark:text-blue-300"
                    : "border-border hover:bg-muted"
                )}
              >
                <FolderKanban className="h-4 w-4" />
                <span className="text-sm font-medium">
                  {selectedProjects.length === 0
                    ? 'All Projects'
                    : selectedProjects.length === 1
                      ? selectedProjects[0].key
                      : `${selectedProjects.length} projects`}
                </span>
                <ChevronDown className={cn("h-4 w-4 transition-transform", showProjectSelector && "rotate-180")} />
              </button>

              {showProjectSelector && (
                <div className="absolute top-full left-0 mt-1 w-80 bg-background border rounded-lg shadow-lg z-50 max-h-[450px] overflow-hidden flex flex-col">
                  {/* Selected Projects Tags */}
                  {selectedProjects.length > 0 && (
                    <div className="p-2 border-b flex-shrink-0">
                      <div className="flex flex-wrap gap-1.5">
                        {selectedProjects.map((p) => (
                          <span
                            key={p.key}
                            className="inline-flex items-center gap-1 px-2 py-1 bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 text-xs rounded-md"
                          >
                            {p.key}
                            <X
                              className="h-3 w-3 cursor-pointer hover:text-red-500"
                              onClick={(e) => {
                                e.stopPropagation()
                                handleProjectRemove(p.key)
                              }}
                            />
                          </span>
                        ))}
                        <button
                          onClick={() => setSelectedProjects([])}
                          className="text-xs text-muted-foreground hover:text-foreground px-2 py-1"
                        >
                          Clear all
                        </button>
                      </div>
                    </div>
                  )}

                  {/* Search */}
                  <div className="p-2 border-b flex-shrink-0">
                    <div className="relative">
                      <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                      <input
                        type="text"
                        value={projectSearch}
                        onChange={(e) => setProjectSearch(e.target.value)}
                        placeholder="Search projects..."
                        className="w-full pl-9 pr-3 py-2 text-sm border rounded-md bg-muted/50 focus:ring-2 focus:ring-blue-500/50"
                        autoFocus
                      />
                    </div>
                  </div>

                  {/* Project List */}
                  <div className="overflow-y-auto flex-1">
                    {projectsLoading ? (
                      <div className="p-4 text-center text-muted-foreground">
                        <Loader2 className="h-4 w-4 animate-spin mx-auto" />
                      </div>
                    ) : projects.length === 0 ? (
                      <div className="p-4 text-center text-muted-foreground text-sm">
                        No projects found
                      </div>
                    ) : (
                      projects.map((project) => {
                        const isSelected = selectedProjects.some(p => p.key === project.key)
                        return (
                          <button
                            key={project.key}
                            onClick={() => handleProjectSelect(project)}
                            className={cn(
                              "w-full px-3 py-2 text-left hover:bg-muted transition-colors flex items-center gap-3",
                              isSelected && "bg-blue-50 dark:bg-blue-900/20"
                            )}
                          >
                            {/* Checkbox */}
                            <div className={cn(
                              "w-4 h-4 rounded border flex items-center justify-center flex-shrink-0",
                              isSelected
                                ? "bg-blue-500 border-blue-500"
                                : "border-gray-300 dark:border-gray-600"
                            )}>
                              {isSelected && (
                                <CheckCircle2 className="h-3 w-3 text-white" />
                              )}
                            </div>
                            {project.avatarUrl ? (
                              <img src={project.avatarUrl} alt="" className="w-6 h-6 rounded" />
                            ) : (
                              <div className="w-6 h-6 bg-gradient-to-br from-blue-400 to-purple-500 rounded flex items-center justify-center text-white text-xs font-bold">
                                {project.key[0]}
                              </div>
                            )}
                            <div className="flex-1 min-w-0">
                              <div className="text-sm font-medium truncate">{project.name}</div>
                              <div className="text-xs text-muted-foreground">{project.key}</div>
                            </div>
                          </button>
                        )
                      })
                    )}
                  </div>

                  {/* Footer */}
                  <div className="p-2 border-t bg-muted/30 flex-shrink-0">
                    <button
                      onClick={() => setShowProjectSelector(false)}
                      className="w-full px-3 py-2 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700 transition-colors"
                    >
                      Done
                    </button>
                  </div>
                </div>
              )}
            </div>

            {/* Sprint Filter - Enabled when 1+ projects selected */}
            <div className="relative">
              <button
                onClick={() => selectedProjects.length > 0 && setShowSprintSelector(!showSprintSelector)}
                disabled={selectedProjects.length === 0}
                className={cn(
                  "flex items-center gap-2 px-3 py-1.5 rounded-lg border transition-all",
                  selectedProjects.length === 0
                    ? "border-border opacity-50 cursor-not-allowed"
                    : selectedSprint
                    ? "bg-purple-50 dark:bg-purple-900/20 border-purple-200 dark:border-purple-800 text-purple-700 dark:text-purple-300"
                    : "border-border hover:bg-muted"
                )}
                title={selectedProjects.length === 0 ? "Select a project first" : undefined}
              >
                <Zap className="h-4 w-4" />
                <span className="text-sm font-medium max-w-32 truncate">
                  {selectedSprint ? selectedSprint.name : 'All Sprints'}
                </span>
                {selectedSprint && (
                  <X
                    className="h-3.5 w-3.5 hover:text-red-500"
                    onClick={(e) => {
                      e.stopPropagation()
                      setSelectedSprint(null)
                      setSelectedBoard(null)
                    }}
                  />
                )}
                {!selectedSprint && selectedProjects.length > 0 && <ChevronDown className={cn("h-4 w-4 transition-transform", showSprintSelector && "rotate-180")} />}
              </button>

              {showSprintSelector && (
                <div className="absolute top-full left-0 mt-1 w-96 bg-background border rounded-lg shadow-lg z-50 max-h-[500px] overflow-hidden flex flex-col">
                  {/* Search */}
                  <div className="p-2 border-b flex-shrink-0">
                    <div className="relative">
                      <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                      <input
                        type="text"
                        value={sprintSearch}
                        onChange={(e) => setSprintSearch(e.target.value)}
                        placeholder="Search boards or sprints..."
                        className="w-full pl-9 pr-3 py-2 text-sm border rounded-md bg-muted/50 focus:ring-2 focus:ring-blue-500/50"
                        autoFocus
                      />
                    </div>
                  </div>

                  {/* Boards & Sprints - Grouped by Project */}
                  <div className="overflow-y-auto flex-1">
                    {boardsLoading ? (
                      <div className="p-4 text-center text-muted-foreground">
                        <Loader2 className="h-4 w-4 animate-spin mx-auto" />
                      </div>
                    ) : Object.keys(boardsByProject).length === 0 ? (
                      <div className="p-4 text-center text-muted-foreground text-sm">
                        No boards found
                      </div>
                    ) : (
                      Object.entries(boardsByProject).map(([projectKey, { project, boards: projectBoards }]) => {
                        // 검색 필터링
                        const filteredBoards = sprintSearch.trim()
                          ? projectBoards.filter(b =>
                              b.name.toLowerCase().includes(sprintSearch.toLowerCase()) ||
                              projectKey.toLowerCase().includes(sprintSearch.toLowerCase())
                            )
                          : projectBoards

                        if (filteredBoards.length === 0 && sprintSearch.trim()) return null

                        return (
                          <div key={projectKey} className="border-b last:border-b-0">
                            {/* Project Header */}
                            <div className="px-3 py-2 bg-muted/50 border-b sticky top-0">
                              <div className="flex items-center gap-2">
                                <div className="w-5 h-5 bg-gradient-to-br from-blue-400 to-purple-500 rounded flex items-center justify-center text-white text-xs font-bold">
                                  {projectKey[0]}
                                </div>
                                <span className="text-sm font-semibold">{project.name}</span>
                                <span className="text-xs text-muted-foreground">({projectKey})</span>
                              </div>
                            </div>

                            {/* Boards for this project */}
                            {filteredBoards.length === 0 ? (
                              <div className="px-3 py-2 text-center text-muted-foreground text-xs">
                                No boards found
                              </div>
                            ) : (
                              filteredBoards.map((board) => (
                                <div key={board.id}>
                                  <button
                                    onClick={() => {
                                      if (selectedBoard?.id === board.id) {
                                        setSelectedBoard(null)
                                        setSelectedSprint(null)
                                      } else {
                                        setSelectedBoard(board)
                                        setSelectedSprint(null)
                                      }
                                    }}
                                    className={cn(
                                      "w-full px-3 py-2 text-left hover:bg-muted transition-colors flex items-center gap-3",
                                      selectedBoard?.id === board.id && "bg-orange-50 dark:bg-orange-900/20"
                                    )}
                                  >
                                    <div className={cn(
                                      "w-6 h-6 rounded flex items-center justify-center text-white text-xs font-bold",
                                      board.type === 'scrum' ? "bg-gradient-to-br from-purple-400 to-purple-600" : "bg-gradient-to-br from-green-400 to-green-600"
                                    )}>
                                      {board.type === 'scrum' ? 'S' : 'K'}
                                    </div>
                                    <div className="flex-1 min-w-0">
                                      <div className="text-sm font-medium truncate">{board.name}</div>
                                      <div className="text-xs text-muted-foreground">{board.type}</div>
                                    </div>
                                    <ChevronRight className={cn(
                                      "h-4 w-4 text-muted-foreground transition-transform",
                                      selectedBoard?.id === board.id && "rotate-90"
                                    )} />
                                  </button>

                                  {/* Sprints for this board */}
                                  {selectedBoard?.id === board.id && (
                                    <div className="pl-4 pb-2 bg-muted/30">
                                      {sprintsLoading ? (
                                        <div className="p-3 text-center text-muted-foreground">
                                          <Loader2 className="h-4 w-4 animate-spin mx-auto" />
                                        </div>
                                      ) : sprints.length === 0 ? (
                                        <div className="p-3 text-center text-muted-foreground text-xs">
                                          No sprints found
                                        </div>
                                      ) : (
                                        <div className="space-y-1 pt-1">
                                          {sprints.map((sprint) => (
                                            <button
                                              key={sprint.id}
                                              onClick={() => handleSprintSelect(sprint)}
                                              className={cn(
                                                "w-full px-3 py-2 text-left rounded-md hover:bg-background transition-colors flex items-center gap-2",
                                                selectedSprint?.id === sprint.id && "bg-purple-100 dark:bg-purple-900/30"
                                              )}
                                            >
                                              <div className={cn(
                                                "w-2 h-2 rounded-full flex-shrink-0",
                                                sprint.state === 'active' ? "bg-green-500" :
                                                sprint.state === 'future' ? "bg-blue-500" : "bg-gray-400"
                                              )} />
                                              <div className="flex-1 min-w-0">
                                                <div className="text-sm truncate">{sprint.name}</div>
                                                {sprint.state === 'active' && sprint.endDate && (
                                                  <div className="text-xs text-muted-foreground">
                                                    Ends: {new Date(sprint.endDate).toLocaleDateString()}
                                                  </div>
                                                )}
                                              </div>
                                              <span className={cn(
                                                "text-xs px-1.5 py-0.5 rounded-full",
                                                sprint.state === 'active' ? "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400" :
                                                sprint.state === 'future' ? "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400" :
                                                "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400"
                                              )}>
                                                {sprint.state}
                                              </span>
                                            </button>
                                          ))}
                                        </div>
                                      )}
                                    </div>
                                  )}
                                </div>
                              ))
                            )}
                          </div>
                        )
                      })
                    )}
                  </div>

                  {/* Quick Actions */}
                  <div className="p-2 border-t bg-muted/30 flex-shrink-0">
                    <button
                      onClick={() => {
                        setSelectedBoard(null)
                        setSelectedSprint(null)
                        setShowSprintSelector(false)
                      }}
                      className="w-full px-3 py-2 text-sm text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors text-left"
                    >
                      Clear sprint filter
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Right: Clear + Display Mode + Search */}
          <div className="flex items-center gap-3">
            {/* Display Mode Toggle */}
            <div className="inline-flex items-center gap-1 bg-muted/50 p-1 rounded-lg">
              <button
                onClick={() => setDisplayMode('list')}
                className={cn(
                  "p-2 rounded transition-all",
                  displayMode === 'list' ? "bg-background shadow-sm" : "hover:bg-muted"
                )}
                title="List View"
              >
                <List className="h-4 w-4" />
              </button>
              <button
                onClick={() => setDisplayMode('board')}
                className={cn(
                  "p-2 rounded transition-all",
                  displayMode === 'board' ? "bg-background shadow-sm" : "hover:bg-muted"
                )}
                title="Board View"
              >
                <Columns3 className="h-4 w-4" />
              </button>
            </div>

            {/* Search Input */}
            <form onSubmit={handleSearch} className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <input
                type="text"
                value={searchJql}
                onChange={(e) => setSearchJql(e.target.value)}
                placeholder="JQL or issue key..."
                className="pl-9 pr-4 py-2 w-48 lg:w-64 rounded-lg border bg-background text-sm focus:ring-2 focus:ring-blue-500/50 focus:border-blue-500"
              />
            </form>

            {/* Clear All Filters - Far right */}
            {(selectedProjects.length > 0 || selectedSprint || assigneeFilter !== 'me') && (
              <button
                onClick={handleClearFilters}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 border border-red-200 dark:border-red-800 transition-colors"
                title="Reset to default filters"
              >
                <X className="h-4 w-4" />
                <span>Clear</span>
              </button>
            )}
          </div>
        </div>
      </Card>

      {/* Main Content */}
      <div className="flex gap-6">
        {/* Issues List/Board */}
        <div className={cn("flex-1 min-w-0", selectedIssue && "lg:w-2/3")}>
          {isLoading ? (
            <Card className="p-12">
              <div className="flex flex-col items-center justify-center gap-3">
                <Loader2 className="h-8 w-8 animate-spin text-blue-500" />
                <p className="text-sm text-muted-foreground">Loading issues...</p>
              </div>
            </Card>
          ) : currentIssues.length === 0 ? (
            <Card className="p-12">
              <div className="text-center">
                <div className="w-16 h-16 bg-muted rounded-full flex items-center justify-center mx-auto mb-4">
                  <AlertCircle className="h-8 w-8 text-muted-foreground" />
                </div>
                <h3 className="text-lg font-medium mb-1">No issues found</h3>
                <p className="text-sm text-muted-foreground mb-4">
                  {searchJql
                    ? 'Try a different JQL query'
                    : 'No issues match the current filter'}
                </p>
                <button
                  onClick={() => setShowCreateModal(true)}
                  className="inline-flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700"
                >
                  <Plus className="h-4 w-4" />
                  Create Issue
                </button>
              </div>
            </Card>
          ) : displayMode === 'list' ? (
            <Card className="overflow-hidden">
              <div className="px-4 py-3 border-b bg-muted/30">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium">{currentIssues.length} issues</span>
                </div>
              </div>
              <div className="divide-y divide-border">
                {currentIssues.map((issue) => (
                  <IssueRow
                    key={issue.key}
                    issue={issue}
                    selected={selectedIssue === issue.key}
                    onClick={() => setSelectedIssue(issue.key)}
                  />
                ))}
              </div>
            </Card>
          ) : (
            /* Board View - 프로젝트별 행으로 구분 + 드래그 앤 드롭 */
            <DndContext
              sensors={sensors}
              collisionDetection={rectIntersection}
              onDragStart={handleDragStart}
              onDragEnd={handleDragEnd}
              onDragCancel={handleDragCancel}
            >
              <div className="space-y-6">
                {statusColumns.length === 0 ? (
                  <div className="flex-1 text-center py-12 text-muted-foreground">
                    No status columns to display
                  </div>
                ) : Object.keys(issuesByProject).length <= 1 ? (
                  /* 단일 프로젝트: 기존 레이아웃 */
                  <div className="flex gap-4 overflow-x-auto pb-4">
                    {statusColumns.map((status) => {
                      const issues = issuesByStatus[status] || []
                      return (
                        <StatusColumn
                          key={status}
                          status={status}
                          issues={issues}
                          selectedIssue={selectedIssue}
                          onIssueClick={setSelectedIssue}
                        />
                      )
                    })}
                  </div>
                ) : (
                /* 다중 프로젝트: 프로젝트별 행 */
                <>
                  {/* Status Column Headers - Sticky */}
                  <div className="flex gap-3 overflow-x-auto pb-2 sticky top-0 bg-background z-10 pt-1">
                    <div className="flex-shrink-0 w-32" /> {/* 프로젝트 라벨 공간 */}
                    {statusColumns.map((status) => {
                      const category = getStatusCategory(status)
                      const style = categoryStyles[category]
                      const totalCount = issuesByStatus[status]?.length || 0

                      return (
                        <div
                          key={status}
                          className={cn(
                            "flex-shrink-0 w-56 px-3 py-2 rounded-lg",
                            style.bg,
                            style.border,
                            "border"
                          )}
                        >
                          <div className="flex items-center justify-between">
                            <div className="flex items-center gap-2">
                              <style.icon className={cn("h-4 w-4", style.color)} />
                              <span className="font-medium text-sm">{status}</span>
                            </div>
                            <span className={cn(
                              "text-xs font-medium px-2 py-0.5 rounded-full",
                              style.bg,
                              style.color
                            )}>
                              {totalCount}
                            </span>
                          </div>
                        </div>
                      )
                    })}
                  </div>

                  {/* Project Rows */}
                  {Object.entries(issuesByProject).map(([projectKey, { issues: projectIssues }]) => (
                    <div key={projectKey} className="flex gap-3 overflow-x-auto">
                      {/* Project Label */}
                      <div className="flex-shrink-0 w-32 py-2">
                        <div className="flex items-center gap-2">
                          <div className="w-6 h-6 bg-gradient-to-br from-blue-400 to-purple-500 rounded flex items-center justify-center text-white text-xs font-bold">
                            {projectKey[0]}
                          </div>
                          <div>
                            <div className="font-semibold text-sm">{projectKey}</div>
                            <div className="text-xs text-muted-foreground">{projectIssues.length} issues</div>
                          </div>
                        </div>
                      </div>

                      {/* Status Columns for this project */}
                      {statusColumns.map((status) => {
                        const issues = issuesByProjectAndStatus[projectKey]?.[status] || []
                        return (
                          <StatusColumn
                            key={`${projectKey}-${status}`}
                            status={status}
                            issues={issues}
                            selectedIssue={selectedIssue}
                            onIssueClick={setSelectedIssue}
                            compact
                            droppableId={`${projectKey}-${status}`}
                          />
                        )
                      })}
                    </div>
                  ))}
                </>
              )}
              </div>

              {/* Drag Overlay */}
              <DragOverlay>
                {activeIssue && (
                  <div className="opacity-90 rotate-3 scale-105">
                    <IssueCard
                      issue={activeIssue}
                      selected={false}
                      onClick={() => {}}
                    />
                  </div>
                )}
              </DragOverlay>
            </DndContext>
          )}
        </div>

        {/* Issue Detail Panel */}
        {selectedIssue && (
          <div className="w-96 hidden lg:block flex-shrink-0">
            <IssueDetailPanel
              issue={issueDetails?.data}
              comments={comments?.data || []}
              isLoading={detailsLoading}
              newComment={newComment}
              onNewCommentChange={setNewComment}
              onAddComment={() => {
                if (newComment.trim()) {
                  addCommentMutation.mutate({ issueKey: selectedIssue, comment: newComment })
                }
              }}
              onTransition={(status) => transitionMutation.mutate({ issueKey: selectedIssue, status })}
              onClose={() => {
                setSelectedIssue(null)
                setAnalysisResult(null)
              }}
              onAnalyze={() => handleAnalyzeIssue(selectedIssue)}
              isAddingComment={addCommentMutation.isPending}
              isAnalyzing={isAnalyzing}
              analysisResult={analysisResult}
            />
          </div>
        )}
      </div>

      {/* Create Issue Modal */}
      {showCreateModal && (
        <CreateIssueModal
          projects={projects}
          defaultProject={selectedProjects.length > 0 ? selectedProjects[0].key : ''}
          onClose={() => setShowCreateModal(false)}
          onCreated={(issueKey) => {
            setShowCreateModal(false)
            setSelectedIssue(issueKey)
            queryClient.invalidateQueries({ queryKey: ['jira'] })
          }}
        />
      )}

      {/* Transition Selection Dialog */}
      {transitionDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/50"
            onClick={() => {
              setTransitionDialog(null)
              setTransitionDates({ startDate: '', dueDate: '' })
            }}
          />
          <div className="relative bg-background rounded-xl shadow-2xl border p-6 w-full max-w-md mx-4">
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold">상태 변경</h3>
              <button
                onClick={() => {
                  setTransitionDialog(null)
                  setTransitionDates({ startDate: '', dueDate: '' })
                }}
                className="p-1 hover:bg-muted rounded"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {transitionDialog.requiresFields ? (
              /* 필수 필드 입력 폼 */
              <>
                <div className="mb-4 p-3 bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg">
                  <div className="flex items-start gap-2">
                    <Clock className="h-5 w-5 text-blue-600 dark:text-blue-400 mt-0.5 flex-shrink-0" />
                    <div className="text-sm">
                      <p className="font-medium text-blue-800 dark:text-blue-200">
                        필수 정보 입력
                      </p>
                      <p className="text-blue-700 dark:text-blue-300 mt-1">
                        <span className="font-medium">{transitionDialog.issueKey}</span>를{' '}
                        <span className="font-medium">"{transitionDialog.selectedTransition}"</span>로
                        변경하려면 아래 정보가 필요합니다.
                      </p>
                    </div>
                  </div>
                </div>

                <div className="space-y-4 mb-6">
                  <div>
                    <label className="block text-sm font-medium mb-1.5">
                      시작일 <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="date"
                      value={transitionDates.startDate}
                      onChange={(e) => setTransitionDates(prev => ({ ...prev, startDate: e.target.value }))}
                      className="w-full px-3 py-2 border rounded-lg bg-background focus:outline-none focus:ring-2 focus:ring-blue-500 [color-scheme:dark]"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1.5">
                      기한 (마감일) <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="date"
                      value={transitionDates.dueDate}
                      onChange={(e) => setTransitionDates(prev => ({ ...prev, dueDate: e.target.value }))}
                      className="w-full px-3 py-2 border rounded-lg bg-background focus:outline-none focus:ring-2 focus:ring-blue-500 [color-scheme:dark]"
                    />
                  </div>
                </div>

                <div className="flex justify-end gap-2">
                  <button
                    onClick={() => {
                      setTransitionDialog(null)
                      setTransitionDates({ startDate: '', dueDate: '' })
                    }}
                    className="px-4 py-2 text-sm text-muted-foreground hover:bg-muted rounded-lg transition-colors"
                  >
                    취소
                  </button>
                  <button
                    onClick={() => {
                      if (transitionDialog.selectedTransition) {
                        transitionMutation.mutate({
                          issueKey: transitionDialog.issueKey,
                          status: transitionDialog.selectedTransition,
                          startDate: transitionDates.startDate || undefined,
                          dueDate: transitionDates.dueDate || undefined,
                        })
                      }
                    }}
                    disabled={transitionMutation.isPending || !transitionDates.startDate || !transitionDates.dueDate}
                    className={cn(
                      "px-4 py-2 text-sm font-medium rounded-lg transition-colors",
                      "bg-blue-600 text-white hover:bg-blue-700",
                      (transitionMutation.isPending || !transitionDates.startDate || !transitionDates.dueDate) &&
                        "opacity-50 cursor-not-allowed"
                    )}
                  >
                    {transitionMutation.isPending ? (
                      <span className="flex items-center gap-2">
                        <Loader2 className="h-4 w-4 animate-spin" />
                        처리 중...
                      </span>
                    ) : (
                      '상태 변경'
                    )}
                  </button>
                </div>
              </>
            ) : (
              /* 대체 상태 선택 */
              <>
                <div className="mb-4 p-3 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-lg">
                  <div className="flex items-start gap-2">
                    <AlertCircle className="h-5 w-5 text-amber-600 dark:text-amber-400 mt-0.5 flex-shrink-0" />
                    <div className="text-sm">
                      <p className="font-medium text-amber-800 dark:text-amber-200">
                        직접 전환 불가
                      </p>
                      <p className="text-amber-700 dark:text-amber-300 mt-1">
                        <span className="font-medium">{transitionDialog.issueKey}</span>를{' '}
                        <span className="font-medium">"{transitionDialog.currentStatus}"</span>에서{' '}
                        <span className="font-medium">"{transitionDialog.targetStatus}"</span>로
                        직접 변경할 수 없습니다.
                      </p>
                    </div>
                  </div>
                </div>

                <div className="mb-4">
                  <p className="text-sm text-muted-foreground mb-3">
                    아래 상태 중 하나로 먼저 변경할 수 있습니다:
                  </p>
                  <div className="space-y-2">
                    {transitionDialog.availableTransitions.map((status) => {
                      const category = getStatusCategory(status)
                      const style = categoryStyles[category]
                      return (
                        <button
                          key={status}
                          onClick={() => {
                            transitionMutation.mutate({
                              issueKey: transitionDialog.issueKey,
                              status,
                            })
                          }}
                          disabled={transitionMutation.isPending}
                          className={cn(
                            "w-full flex items-center gap-3 p-3 rounded-lg border transition-all",
                            "hover:shadow-md hover:scale-[1.02]",
                            style.border,
                            style.bg,
                            transitionMutation.isPending && "opacity-50 cursor-not-allowed"
                          )}
                        >
                          <style.icon className={cn("h-5 w-5", style.color)} />
                          <span className="font-medium">{status}</span>
                          <ChevronRight className="h-4 w-4 ml-auto text-muted-foreground" />
                        </button>
                      )
                    })}
                  </div>
                </div>

                <div className="flex justify-end">
                  <button
                    onClick={() => {
                      setTransitionDialog(null)
                      setTransitionDates({ startDate: '', dueDate: '' })
                    }}
                    className="px-4 py-2 text-sm text-muted-foreground hover:bg-muted rounded-lg transition-colors"
                  >
                    취소
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* Click outside to close project selector */}
      {showProjectSelector && (
        <div
          className="fixed inset-0 z-40"
          onClick={() => setShowProjectSelector(false)}
        />
      )}

      {/* Click outside to close sprint selector */}
      {showSprintSelector && (
        <div
          className="fixed inset-0 z-40"
          onClick={() => setShowSprintSelector(false)}
        />
      )}

      {/* Click outside to close assignee selector */}
      {showAssigneeSelector && (
        <div
          className="fixed inset-0 z-40"
          onClick={() => setShowAssigneeSelector(false)}
        />
      )}
    </div>
  )
}

// Issue Row Component (List View)
function IssueRow({
  issue,
  selected,
  onClick,
}: {
  issue: JiraIssueListItem
  selected: boolean
  onClick: () => void
}) {
  const category = getStatusCategory(issue.status)
  const style = categoryStyles[category]
  const StatusIcon = style.icon
  const TypeIcon = issueTypeIcons[issue.type || ''] || FileText
  const priority = priorityConfig[issue.priority || '']

  return (
    <div
      onClick={onClick}
      className={cn(
        "flex items-center gap-4 px-4 py-3 cursor-pointer transition-all group",
        selected
          ? "bg-blue-50 dark:bg-blue-900/20 border-l-2 border-blue-500"
          : "hover:bg-muted/50 border-l-2 border-transparent"
      )}
    >
      {/* Type Icon */}
      <TypeIcon className="h-4 w-4 text-muted-foreground flex-shrink-0" />

      {/* Key */}
      <span className="font-mono text-sm text-blue-600 dark:text-blue-400 font-medium flex-shrink-0 w-24">
        {issue.key}
      </span>

      {/* Summary */}
      <div className="flex-1 min-w-0">
        <span className="truncate block">{issue.summary}</span>
      </div>

      {/* Status Badge */}
      <div className={cn(
        "flex items-center gap-1.5 px-2 py-1 rounded-full text-xs font-medium flex-shrink-0",
        style.bg,
        style.color
      )}>
        <StatusIcon className="h-3 w-3" />
        {issue.status}
      </div>

      {/* Priority */}
      {priority && (
        <div className={cn("flex-shrink-0", priority.color)} title={priority.label}>
          <priority.icon className="h-4 w-4" />
        </div>
      )}

      {/* Assignee */}
      <div className="flex-shrink-0 w-24 text-right">
        {issue.assignee ? (
          <span className="text-sm text-muted-foreground truncate block">
            {issue.assignee.split(' ')[0]}
          </span>
        ) : (
          <span className="text-xs text-muted-foreground italic">Unassigned</span>
        )}
      </div>

      <ChevronRight className="h-4 w-4 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0" />
    </div>
  )
}

// Issue Card Component (Board View)
function IssueCard({
  issue,
  selected,
  onClick,
  compact = false,
}: {
  issue: JiraIssueListItem
  selected: boolean
  onClick: () => void
  compact?: boolean
}) {
  const TypeIcon = issueTypeIcons[issue.type || ''] || FileText
  const priority = priorityConfig[issue.priority || '']

  // Compact 모드: 다중 프로젝트 보드에서 사용
  if (compact) {
    return (
      <div
        onClick={onClick}
        className={cn(
          "bg-background rounded-md border p-2 cursor-pointer transition-all hover:shadow-sm group",
          selected && "ring-2 ring-blue-500 border-blue-500"
        )}
      >
        <div className="flex items-center gap-2 mb-1">
          <TypeIcon className="h-3 w-3 text-muted-foreground flex-shrink-0" />
          <span className="font-mono text-xs text-blue-600 dark:text-blue-400 font-medium">
            {issue.key.split('-')[1]}
          </span>
          {priority && (
            <priority.icon className={cn("h-3 w-3 ml-auto", priority.color)} />
          )}
        </div>
        <p className="text-xs line-clamp-2 leading-snug">{issue.summary}</p>
        {issue.assignee && (
          <div className="mt-1.5 flex items-center gap-1">
            <div className="w-4 h-4 bg-gradient-to-br from-blue-400 to-purple-500 rounded-full flex items-center justify-center text-white text-[8px] font-bold">
              {issue.assignee.charAt(0)}
            </div>
            <span className="text-[10px] text-muted-foreground truncate">{issue.assignee.split(' ')[0]}</span>
          </div>
        )}
      </div>
    )
  }

  return (
    <div
      onClick={onClick}
      className={cn(
        "bg-background rounded-lg border p-3 cursor-pointer transition-all hover:shadow-md group",
        selected && "ring-2 ring-blue-500 border-blue-500"
      )}
    >
      {/* Header: Key + Priority */}
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <TypeIcon className="h-3.5 w-3.5 text-muted-foreground" />
          <span className="font-mono text-xs text-blue-600 dark:text-blue-400 font-medium">
            {issue.key}
          </span>
        </div>
        {priority && (
          <priority.icon className={cn("h-4 w-4", priority.color)} />
        )}
      </div>

      {/* Summary */}
      <p className="text-sm line-clamp-2 mb-3 leading-snug">{issue.summary}</p>

      {/* Footer */}
      <div className="flex items-center justify-between text-xs">
        <span className="text-muted-foreground flex items-center gap-1">
          <Tag className="h-3 w-3" />
          {issue.type}
        </span>
        {issue.assignee ? (
          <div className="flex items-center gap-1 text-muted-foreground">
            <div className="w-5 h-5 bg-gradient-to-br from-blue-400 to-purple-500 rounded-full flex items-center justify-center text-white text-[10px] font-bold">
              {issue.assignee.charAt(0)}
            </div>
          </div>
        ) : (
          <span className="text-muted-foreground italic">Unassigned</span>
        )}
      </div>
    </div>
  )
}

// Issue Detail Panel Component
function IssueDetailPanel({
  issue,
  comments,
  isLoading,
  newComment,
  onNewCommentChange,
  onAddComment,
  onTransition,
  onClose,
  onAnalyze,
  isAddingComment,
  isAnalyzing,
  analysisResult,
}: {
  issue?: JiraIssue
  comments: JiraComment[]
  isLoading: boolean
  newComment: string
  onNewCommentChange: (value: string) => void
  onAddComment: () => void
  onTransition: (status: string) => void
  onClose: () => void
  onAnalyze: () => void
  isAddingComment: boolean
  isAnalyzing: boolean
  analysisResult: string | null
}) {
  const [activeTab, setActiveTab] = useState<'details' | 'comments' | 'ai'>('details')

  if (isLoading) {
    return (
      <Card className="sticky top-4 overflow-hidden">
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-6 w-6 animate-spin text-blue-500" />
        </div>
      </Card>
    )
  }

  if (!issue) {
    return (
      <Card className="sticky top-4 overflow-hidden">
        <div className="text-center py-12 text-muted-foreground">
          <AlertCircle className="h-8 w-8 mx-auto mb-2 opacity-50" />
          <p>Issue not found</p>
        </div>
      </Card>
    )
  }

  const category = getStatusCategory(issue.status)
  const style = categoryStyles[category]
  const StatusIcon = style.icon
  const TypeIcon = issueTypeIcons[issue.issuetype || ''] || FileText
  const priority = priorityConfig[issue.priority || '']

  return (
    <Card className="sticky top-4 overflow-hidden flex flex-col max-h-[calc(100vh-200px)]">
      {/* Header */}
      <div className="flex items-start justify-between p-4 border-b bg-muted/30 flex-shrink-0">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <TypeIcon className="h-4 w-4 text-muted-foreground" />
            <a
              href={issue.url}
              target="_blank"
              rel="noopener noreferrer"
              className="font-mono text-blue-600 dark:text-blue-400 hover:underline flex items-center gap-1 font-medium"
            >
              {issue.key}
              <ExternalLink className="h-3 w-3" />
            </a>
          </div>
          <h3 className="font-semibold text-sm leading-snug">{issue.summary}</h3>
        </div>
        <button
          onClick={onClose}
          className="p-1.5 hover:bg-muted rounded-lg transition-colors flex-shrink-0 ml-2"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      {/* Tab Navigation */}
      <div className="flex border-b flex-shrink-0">
        <button
          onClick={() => setActiveTab('details')}
          className={cn(
            "flex-1 px-4 py-2 text-sm font-medium transition-colors border-b-2",
            activeTab === 'details'
              ? "border-blue-500 text-blue-600"
              : "border-transparent text-muted-foreground hover:text-foreground"
          )}
        >
          Details
        </button>
        <button
          onClick={() => setActiveTab('comments')}
          className={cn(
            "flex-1 px-4 py-2 text-sm font-medium transition-colors border-b-2 flex items-center justify-center gap-1.5",
            activeTab === 'comments'
              ? "border-blue-500 text-blue-600"
              : "border-transparent text-muted-foreground hover:text-foreground"
          )}
        >
          <MessageSquare className="h-4 w-4" />
          {comments.length}
        </button>
        <button
          onClick={() => setActiveTab('ai')}
          className={cn(
            "flex-1 px-4 py-2 text-sm font-medium transition-colors border-b-2 flex items-center justify-center gap-1.5",
            activeTab === 'ai'
              ? "border-blue-500 text-blue-600"
              : "border-transparent text-muted-foreground hover:text-foreground"
          )}
        >
          <Sparkles className="h-4 w-4" />
          AI
        </button>
      </div>

      {/* Tab Content */}
      <div className="flex-1 overflow-y-auto">
        {activeTab === 'details' && (
          <div className="p-4 space-y-4">
            {/* Status & Actions */}
            <div className="flex items-center justify-between">
              <div className={cn(
                "flex items-center gap-2 px-3 py-1.5 rounded-full text-sm font-medium",
                style.bg,
                style.color
              )}>
                <StatusIcon className="h-4 w-4" />
                {issue.status}
              </div>
              <select
                onChange={(e) => onTransition(e.target.value)}
                className="text-sm border rounded-lg px-3 py-1.5 bg-background hover:bg-muted transition-colors cursor-pointer"
                defaultValue=""
              >
                <option value="" disabled>Move to...</option>
                <option value="To Do">To Do</option>
                <option value="In Progress">In Progress</option>
                <option value="In Review">In Review</option>
                <option value="Done">Done</option>
              </select>
            </div>

            {/* Fields Grid */}
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-1">
                <label className="text-xs text-muted-foreground uppercase tracking-wider">Type</label>
                <div className="flex items-center gap-2">
                  <TypeIcon className="h-4 w-4 text-muted-foreground" />
                  <span className="text-sm font-medium">{issue.issuetype}</span>
                </div>
              </div>
              <div className="space-y-1">
                <label className="text-xs text-muted-foreground uppercase tracking-wider">Priority</label>
                {priority ? (
                  <div className="flex items-center gap-2">
                    <priority.icon className={cn("h-4 w-4", priority.color)} />
                    <span className="text-sm font-medium">{priority.label}</span>
                  </div>
                ) : (
                  <span className="text-sm text-muted-foreground">-</span>
                )}
              </div>
              <div className="space-y-1">
                <label className="text-xs text-muted-foreground uppercase tracking-wider">Assignee</label>
                {issue.assignee ? (
                  <div className="flex items-center gap-2">
                    <div className="w-5 h-5 bg-gradient-to-br from-blue-400 to-purple-500 rounded-full flex items-center justify-center text-white text-[10px] font-bold">
                      {issue.assignee.charAt(0)}
                    </div>
                    <span className="text-sm font-medium truncate">{issue.assignee}</span>
                  </div>
                ) : (
                  <span className="text-sm text-muted-foreground italic">Unassigned</span>
                )}
              </div>
              <div className="space-y-1">
                <label className="text-xs text-muted-foreground uppercase tracking-wider">Reporter</label>
                {issue.reporter ? (
                  <div className="flex items-center gap-2">
                    <div className="w-5 h-5 bg-gradient-to-br from-green-400 to-teal-500 rounded-full flex items-center justify-center text-white text-[10px] font-bold">
                      {issue.reporter.charAt(0)}
                    </div>
                    <span className="text-sm font-medium truncate">{issue.reporter}</span>
                  </div>
                ) : (
                  <span className="text-sm text-muted-foreground">-</span>
                )}
              </div>
            </div>

            {/* Description */}
            {issue.description && (
              <div className="space-y-2">
                <label className="text-xs text-muted-foreground uppercase tracking-wider">Description</label>
                <div className="text-sm text-muted-foreground bg-muted/30 rounded-lg p-3 whitespace-pre-wrap">
                  {issue.description}
                </div>
              </div>
            )}

            {/* Labels */}
            {issue.labels && issue.labels.length > 0 && (
              <div className="space-y-2">
                <label className="text-xs text-muted-foreground uppercase tracking-wider">Labels</label>
                <div className="flex flex-wrap gap-1.5">
                  {issue.labels.map((label) => (
                    <span
                      key={label}
                      className="px-2 py-0.5 bg-muted text-xs rounded-full"
                    >
                      {label}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {activeTab === 'comments' && (
          <div className="p-4 space-y-4">
            {/* Comment List */}
            <div className="space-y-3">
              {comments.length === 0 ? (
                <div className="text-center py-8 text-muted-foreground">
                  <MessageSquare className="h-8 w-8 mx-auto mb-2 opacity-50" />
                  <p className="text-sm">No comments yet</p>
                </div>
              ) : (
                comments.map((comment) => (
                  <div key={comment.id} className="bg-muted/30 rounded-lg p-3">
                    <div className="flex items-center gap-2 mb-2">
                      <div className="w-6 h-6 bg-gradient-to-br from-blue-400 to-purple-500 rounded-full flex items-center justify-center text-white text-[10px] font-bold">
                        {comment.author.charAt(0)}
                      </div>
                      <span className="text-sm font-medium">{comment.author}</span>
                      <span className="text-xs text-muted-foreground flex items-center gap-1">
                        <Clock className="h-3 w-3" />
                        {new Date(comment.created).toLocaleDateString()}
                      </span>
                    </div>
                    <p className="text-sm text-muted-foreground whitespace-pre-wrap">
                      {comment.body}
                    </p>
                  </div>
                ))
              )}
            </div>

            {/* Add Comment */}
            <div className="flex gap-2 pt-2 border-t">
              <input
                type="text"
                value={newComment}
                onChange={(e) => onNewCommentChange(e.target.value)}
                placeholder="Add a comment..."
                className="flex-1 px-3 py-2 border rounded-lg text-sm bg-background focus:ring-2 focus:ring-blue-500/50"
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault()
                    onAddComment()
                  }
                }}
              />
              <button
                onClick={onAddComment}
                disabled={!newComment.trim() || isAddingComment}
                className="p-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
              >
                {isAddingComment ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Send className="h-4 w-4" />
                )}
              </button>
            </div>
          </div>
        )}

        {activeTab === 'ai' && (
          <div className="p-4 space-y-4">
            {/* AI Analysis Button */}
            <button
              onClick={onAnalyze}
              disabled={isAnalyzing}
              className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-gradient-to-r from-purple-500 to-blue-500 text-white rounded-lg hover:from-purple-600 hover:to-blue-600 disabled:opacity-50 transition-all"
            >
              {isAnalyzing ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  Analyzing...
                </>
              ) : (
                <>
                  <Brain className="h-4 w-4" />
                  Analyze with AI
                </>
              )}
            </button>

            {/* Quick Actions */}
            <div className="grid grid-cols-2 gap-2">
              <button className="flex items-center gap-2 px-3 py-2 text-sm bg-muted/50 rounded-lg hover:bg-muted transition-colors">
                <Wand2 className="h-4 w-4 text-purple-500" />
                Auto Label
              </button>
              <button className="flex items-center gap-2 px-3 py-2 text-sm bg-muted/50 rounded-lg hover:bg-muted transition-colors">
                <TrendingUp className="h-4 w-4 text-blue-500" />
                Suggest Priority
              </button>
            </div>

            {/* Analysis Result */}
            {analysisResult && (
              <div className="space-y-2">
                <label className="text-xs text-muted-foreground uppercase tracking-wider flex items-center gap-1.5">
                  <Sparkles className="h-3 w-3 text-purple-500" />
                  AI Analysis
                </label>
                <div className="bg-gradient-to-br from-purple-50 to-blue-50 dark:from-purple-900/20 dark:to-blue-900/20 rounded-lg p-4 text-sm whitespace-pre-wrap border border-purple-100 dark:border-purple-800">
                  {analysisResult}
                </div>
              </div>
            )}

            {!analysisResult && !isAnalyzing && (
              <div className="text-center py-8 text-muted-foreground">
                <Brain className="h-12 w-12 mx-auto mb-3 opacity-30" />
                <p className="text-sm">
                  Click "Analyze with AI" to get intelligent insights about this issue
                </p>
              </div>
            )}
          </div>
        )}
      </div>
    </Card>
  )
}

// Create Issue Modal
function CreateIssueModal({
  projects,
  defaultProject,
  onClose,
  onCreated,
}: {
  projects: JiraProject[]
  defaultProject?: string
  onClose: () => void
  onCreated: (issueKey: string) => void
}) {
  const [project, setProject] = useState(defaultProject || '')
  const [summary, setSummary] = useState('')
  const [description, setDescription] = useState('')
  const [issueType, setIssueType] = useState('Task')

  const createMutation = useMutation({
    mutationFn: () => jiraApi.createIssue(project, summary, description || undefined, issueType),
    onSuccess: (result) => {
      if (result.success && result.data?.key) {
        onCreated(result.data.key)
      }
    },
  })

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm">
      <div className="bg-background rounded-2xl shadow-2xl w-full max-w-lg mx-4 overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b bg-muted/30">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-blue-600 rounded-lg flex items-center justify-center">
              <Plus className="h-4 w-4 text-white" />
            </div>
            <h2 className="text-lg font-semibold">Create Issue</h2>
          </div>
          <button onClick={onClose} className="p-2 hover:bg-muted rounded-lg transition-colors">
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Form */}
        <div className="p-6 space-y-5">
          {/* Project */}
          <div className="space-y-2">
            <label className="text-sm font-medium">Project <span className="text-red-500">*</span></label>
            <select
              value={project}
              onChange={(e) => setProject(e.target.value)}
              className="w-full px-3 py-2.5 border rounded-lg bg-background focus:ring-2 focus:ring-blue-500/50 focus:border-blue-500"
            >
              <option value="">Select a project</option>
              {projects.map((p) => (
                <option key={p.key} value={p.key}>
                  {p.key} - {p.name}
                </option>
              ))}
            </select>
          </div>

          {/* Issue Type */}
          <div className="space-y-2">
            <label className="text-sm font-medium">Issue Type</label>
            <div className="grid grid-cols-4 gap-2">
              {[
                { value: 'Task', icon: FileText, label: 'Task' },
                { value: 'Bug', icon: Bug, label: 'Bug' },
                { value: 'Story', icon: Bookmark, label: 'Story' },
                { value: 'Epic', icon: Zap, label: 'Epic' },
              ].map((type) => (
                <button
                  key={type.value}
                  type="button"
                  onClick={() => setIssueType(type.value)}
                  className={cn(
                    "flex flex-col items-center gap-1.5 px-3 py-3 rounded-lg border transition-all",
                    issueType === type.value
                      ? "bg-blue-50 dark:bg-blue-900/20 border-blue-500 text-blue-600"
                      : "hover:bg-muted border-border"
                  )}
                >
                  <type.icon className="h-5 w-5" />
                  <span className="text-xs font-medium">{type.label}</span>
                </button>
              ))}
            </div>
          </div>

          {/* Summary */}
          <div className="space-y-2">
            <label className="text-sm font-medium">Summary <span className="text-red-500">*</span></label>
            <input
              type="text"
              value={summary}
              onChange={(e) => setSummary(e.target.value)}
              className="w-full px-3 py-2.5 border rounded-lg bg-background focus:ring-2 focus:ring-blue-500/50 focus:border-blue-500"
              placeholder="What needs to be done?"
            />
          </div>

          {/* Description */}
          <div className="space-y-2">
            <label className="text-sm font-medium">Description</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="w-full px-3 py-2.5 border rounded-lg bg-background focus:ring-2 focus:ring-blue-500/50 focus:border-blue-500 h-28 resize-none"
              placeholder="Add a description..."
            />
          </div>
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-3 px-6 py-4 border-t bg-muted/30">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium hover:bg-muted rounded-lg transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={() => createMutation.mutate()}
            disabled={!project || !summary || createMutation.isPending}
            className="px-5 py-2 text-sm font-medium bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors flex items-center gap-2"
          >
            {createMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
            Create Issue
          </button>
        </div>

        {createMutation.isError && (
          <div className="px-6 pb-4">
            <p className="text-sm text-red-500 flex items-center gap-2">
              <AlertCircle className="h-4 w-4" />
              Failed to create issue. Please try again.
            </p>
          </div>
        )}
      </div>
    </div>
  )
}

// Draggable Issue Card Component
function DraggableIssueCard({
  issue,
  selected,
  onClick,
  compact = false,
}: {
  issue: JiraIssueListItem
  selected: boolean
  onClick: () => void
  compact?: boolean
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: issue.key })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  }

  const TypeIcon = issueTypeIcons[issue.type || ''] || FileText
  const priority = priorityConfig[issue.priority || '']

  if (compact) {
    return (
      <div
        ref={setNodeRef}
        style={style}
        className={cn(
          "bg-background rounded-md border p-2 cursor-grab active:cursor-grabbing transition-all hover:shadow-sm group",
          selected && "ring-2 ring-blue-500 border-blue-500",
          isDragging && "shadow-lg ring-2 ring-blue-500"
        )}
        {...attributes}
        {...listeners}
      >
        <div className="flex items-center gap-2 mb-1">
          <GripVertical className="h-3 w-3 text-muted-foreground opacity-0 group-hover:opacity-100 flex-shrink-0" />
          <TypeIcon className="h-3 w-3 text-muted-foreground flex-shrink-0" />
          <span
            className="font-mono text-xs text-blue-600 dark:text-blue-400 font-medium cursor-pointer hover:underline"
            onClick={(e) => { e.stopPropagation(); onClick(); }}
          >
            {issue.key.split('-')[1]}
          </span>
          {priority && (
            <priority.icon className={cn("h-3 w-3 ml-auto", priority.color)} />
          )}
        </div>
        <p className="text-xs line-clamp-2 leading-snug">{issue.summary}</p>
        {issue.assignee && (
          <div className="mt-1.5 flex items-center gap-1">
            <div className="w-4 h-4 bg-gradient-to-br from-blue-400 to-purple-500 rounded-full flex items-center justify-center text-white text-[8px] font-bold">
              {issue.assignee.charAt(0)}
            </div>
            <span className="text-[10px] text-muted-foreground truncate">{issue.assignee.split(' ')[0]}</span>
          </div>
        )}
      </div>
    )
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        "bg-background rounded-lg border p-3 cursor-grab active:cursor-grabbing transition-all hover:shadow-md group",
        selected && "ring-2 ring-blue-500 border-blue-500",
        isDragging && "shadow-xl ring-2 ring-blue-500"
      )}
      {...attributes}
      {...listeners}
    >
      {/* Header: Drag handle + Key + Priority */}
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <GripVertical className="h-4 w-4 text-muted-foreground opacity-0 group-hover:opacity-100" />
          <TypeIcon className="h-3.5 w-3.5 text-muted-foreground" />
          <span
            className="font-mono text-xs text-blue-600 dark:text-blue-400 font-medium cursor-pointer hover:underline"
            onClick={(e) => { e.stopPropagation(); onClick(); }}
          >
            {issue.key}
          </span>
        </div>
        {priority && (
          <priority.icon className={cn("h-4 w-4", priority.color)} />
        )}
      </div>

      {/* Summary */}
      <p className="text-sm line-clamp-2 mb-3 leading-snug">{issue.summary}</p>

      {/* Footer */}
      <div className="flex items-center justify-between text-xs">
        <span className="text-muted-foreground flex items-center gap-1">
          <Tag className="h-3 w-3" />
          {issue.type}
        </span>
        {issue.assignee ? (
          <div className="flex items-center gap-1 text-muted-foreground">
            <div className="w-5 h-5 bg-gradient-to-br from-blue-400 to-purple-500 rounded-full flex items-center justify-center text-white text-[10px] font-bold">
              {issue.assignee.charAt(0)}
            </div>
          </div>
        ) : (
          <span className="text-muted-foreground italic">Unassigned</span>
        )}
      </div>
    </div>
  )
}

// Status Column with Droppable area
function StatusColumn({
  status,
  issues,
  selectedIssue,
  onIssueClick,
  compact = false,
  droppableId,
}: {
  status: string
  issues: JiraIssueListItem[]
  selectedIssue: string | null
  onIssueClick: (key: string) => void
  compact?: boolean
  droppableId?: string
}) {
  // droppableId가 제공되면 그것을 사용, 아니면 status를 사용
  const { setNodeRef, isOver } = useDroppable({
    id: droppableId || status,
    data: { status }, // 실제 상태값은 data로 전달
  })

  const category = getStatusCategory(status)
  const style = categoryStyles[category]

  return (
    <div
      ref={setNodeRef}
      className={cn(
        compact ? "flex-shrink-0 w-56 rounded-lg border" : "flex-shrink-0 w-72 rounded-xl border-2",
        style.border,
        compact ? "bg-muted/30" : style.bg,
        isOver && "ring-2 ring-blue-500 ring-offset-2 bg-blue-50/50 dark:bg-blue-900/20 transition-all"
      )}
    >
      {!compact && (
        <div className="p-3 border-b border-inherit">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <style.icon className={cn("h-4 w-4", style.color)} />
              <span className="font-medium text-sm">{status}</span>
            </div>
            <span className={cn(
              "text-xs font-medium px-2 py-0.5 rounded-full",
              style.bg,
              style.color
            )}>
              {issues.length}
            </span>
          </div>
        </div>
      )}
      <SortableContext
        items={issues.map(i => i.key)}
        strategy={verticalListSortingStrategy}
      >
        <div className={cn(
          "p-2 space-y-2",
          compact ? "min-h-[120px] max-h-[250px]" : "min-h-[200px] max-h-[calc(100vh-400px)]",
          "overflow-y-auto"
        )}>
          {issues.map((issue) => (
            <DraggableIssueCard
              key={issue.key}
              issue={issue}
              selected={selectedIssue === issue.key}
              onClick={() => onIssueClick(issue.key)}
              compact={compact}
            />
          ))}
          {issues.length === 0 && (
            <div className={cn(
              "text-center text-muted-foreground border-2 border-dashed rounded-lg transition-all",
              compact ? "py-4 text-xs" : "py-8 text-sm",
              isOver && "border-blue-500 bg-blue-50 dark:bg-blue-900/20 text-blue-600"
            )}>
              {isOver ? "Drop here" : compact ? "-" : "No issues"}
            </div>
          )}
        </div>
      </SortableContext>
    </div>
  )
}

export default Jira
