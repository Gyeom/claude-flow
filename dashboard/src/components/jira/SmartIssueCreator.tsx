import { useState, useCallback, useEffect, useMemo } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  X,
  Sparkles,
  FileText,
  Bug,
  Bookmark,
  Zap,
  Loader2,
  AlertCircle,
  ChevronDown,
  User,
  Users,
  Flag,
  Tag,
  Calendar,
  Clock,
  Wand2,
  CheckCircle2,
  Link2,
  Layers,
  Target,
  GitBranch,
  FolderKanban,
  IterationCcw,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { jiraApi, type JiraProject } from '@/lib/api'
import { SearchableSelect, AsyncSearchableSelect, type SearchableOption } from './SearchableSelect'

interface SmartIssueCreatorProps {
  projects: JiraProject[]
  defaultProject?: string
  onClose: () => void
  onCreated: (issueKey: string) => void
}

// 이슈 타입 정의
const ISSUE_TYPES = [
  { value: 'Task', icon: FileText, label: 'Task', color: 'text-blue-500', bg: 'bg-blue-50 dark:bg-blue-900/20' },
  { value: 'Bug', icon: Bug, label: 'Bug', color: 'text-red-500', bg: 'bg-red-50 dark:bg-red-900/20' },
  { value: 'Story', icon: Bookmark, label: 'Story', color: 'text-green-500', bg: 'bg-green-50 dark:bg-green-900/20' },
  { value: 'Epic', icon: Zap, label: 'Epic', color: 'text-purple-500', bg: 'bg-purple-50 dark:bg-purple-900/20' },
  { value: 'Sub-task', icon: GitBranch, label: 'Sub-task', color: 'text-cyan-500', bg: 'bg-cyan-50 dark:bg-cyan-900/20' },
]

// 우선순위 정의
const PRIORITIES = [
  { value: 'Highest', label: '긴급', color: 'text-red-600', bg: 'bg-red-100 dark:bg-red-900/30', icon: '🔴' },
  { value: 'High', label: '높음', color: 'text-orange-500', bg: 'bg-orange-100 dark:bg-orange-900/30', icon: '🟠' },
  { value: 'Medium', label: '보통', color: 'text-yellow-500', bg: 'bg-yellow-100 dark:bg-yellow-900/30', icon: '🟡' },
  { value: 'Low', label: '낮음', color: 'text-blue-500', bg: 'bg-blue-100 dark:bg-blue-900/30', icon: '🔵' },
  { value: 'Lowest', label: '최저', color: 'text-gray-400', bg: 'bg-gray-100 dark:bg-gray-900/30', icon: '⚪' },
]

export function SmartIssueCreator({
  projects,
  defaultProject,
  onClose,
  onCreated,
}: SmartIssueCreatorProps) {
  // AI 관련 상태
  const [naturalInput, setNaturalInput] = useState('')
  const [aiProcessing, setAiProcessing] = useState(false)
  const [aiApplied, setAiApplied] = useState(false)

  // 기본 필드
  const [project, setProject] = useState(defaultProject || '')
  const [issueType, setIssueType] = useState('Task')
  const [summary, setSummary] = useState('')
  const [description, setDescription] = useState('')
  const [priority, setPriority] = useState('Medium')

  // 관계 필드
  const [parentIssue, setParentIssue] = useState('')
  const [epicLink, setEpicLink] = useState('')

  // 담당자 필드
  const [assignee, setAssignee] = useState('')
  const [reporter, setReporter] = useState('')

  // 분류 필드
  const [labels, setLabels] = useState<string[]>([])
  const [labelInput, setLabelInput] = useState('')
  const [components, setComponents] = useState<string[]>([])
  const [componentInput, setComponentInput] = useState('')

  // 추정/계획 필드
  const [storyPoints, setStoryPoints] = useState('')
  const [originalEstimate, setOriginalEstimate] = useState('')
  const [startDate, setStartDate] = useState('')
  const [dueDate, setDueDate] = useState('')

  // 스프린트 필드
  const [selectedBoard, setSelectedBoard] = useState<number | null>(null)
  const [selectedSprint, setSelectedSprint] = useState<number | null>(null)

  // 드롭다운 상태
  const [showPriorityDropdown, setShowPriorityDropdown] = useState(false)
  const [showIssueTypeDropdown, setShowIssueTypeDropdown] = useState(false)

  // 프로젝트 옵션 생성
  const projectOptions: SearchableOption[] = useMemo(() =>
    projects.map(p => ({
      value: p.key,
      label: p.key,
      sublabel: p.name,
      icon: <FolderKanban className="h-4 w-4 text-blue-500" />,
    })),
    [projects]
  )

  // 보드 목록 조회 (프로젝트 변경 시)
  const { data: boardsData, isLoading: boardsLoading } = useQuery({
    queryKey: ['jira-boards', project],
    queryFn: async () => {
      if (!project) return { success: false, data: [] }
      return jiraApi.getBoards(project)
    },
    enabled: !!project,
  })

  const boards = boardsData?.data || []

  // 스프린트 목록 조회 (보드 선택 시)
  const { data: sprintsData, isLoading: sprintsLoading } = useQuery({
    queryKey: ['jira-sprints', selectedBoard],
    queryFn: async () => {
      if (!selectedBoard) return { success: false, data: [] }
      return jiraApi.getSprints(selectedBoard)
    },
    enabled: !!selectedBoard,
  })

  const sprints = sprintsData?.data || []

  // 프로젝트 변경 시 보드/스프린트 초기화
  useEffect(() => {
    setSelectedBoard(null)
    setSelectedSprint(null)
  }, [project])

  // 보드 변경 시 스프린트 초기화
  useEffect(() => {
    setSelectedSprint(null)
  }, [selectedBoard])

  // 보드 옵션 생성
  const boardOptions: SearchableOption[] = useMemo(() =>
    boards.map(b => ({
      value: b.id.toString(),
      label: b.name,
      sublabel: b.type,
      icon: <IterationCcw className="h-4 w-4 text-cyan-500" />,
    })),
    [boards]
  )

  // 스프린트 옵션 생성 (active 스프린트 우선, future 포함, closed 제외)
  const sprintOptions: SearchableOption[] = useMemo(() =>
    sprints
      .filter(s => s.state !== 'closed')
      .sort((a, b) => {
        if (a.state === 'active' && b.state !== 'active') return -1
        if (a.state !== 'active' && b.state === 'active') return 1
        return 0
      })
      .map(s => ({
        value: s.id.toString(),
        label: s.name,
        sublabel: s.state === 'active' ? '진행 중' : '예정',
        icon: s.state === 'active'
          ? <IterationCcw className="h-4 w-4 text-green-500" />
          : <IterationCcw className="h-4 w-4 text-gray-400" />,
      })),
    [sprints]
  )

  // 사용자 검색 함수
  const searchUsers = useCallback(async (query: string): Promise<SearchableOption[]> => {
    if (!query || query.length < 2) return []
    try {
      const response = await jiraApi.searchUsers(query, project || undefined)
      if (response.success && response.data) {
        return response.data.map(user => ({
          value: user.accountId,
          label: user.displayName,
          sublabel: user.emailAddress,
          icon: <User className="h-4 w-4 text-gray-500" />,
        }))
      }
    } catch (error) {
      console.error('User search failed:', error)
    }
    return []
  }, [project])

  // 이슈 검색 함수 (상위 이슈용)
  const searchIssues = useCallback(async (query: string): Promise<SearchableOption[]> => {
    if (!query || query.length < 2) return []
    try {
      // 프로젝트가 선택된 경우 해당 프로젝트 내에서만 검색
      const jql = project
        ? `project = ${project} AND (summary ~ "${query}*" OR key ~ "${query}") ORDER BY updated DESC`
        : `summary ~ "${query}*" OR key ~ "${query}" ORDER BY updated DESC`
      const response = await jiraApi.searchIssues(jql)
      if (response.success && response.data) {
        return response.data.slice(0, 20).map(issue => ({
          value: issue.key,
          label: issue.key,
          sublabel: issue.summary,
          icon: issue.type === 'Epic'
            ? <Zap className="h-4 w-4 text-purple-500" />
            : <FileText className="h-4 w-4 text-blue-500" />,
        }))
      }
    } catch (error) {
      console.error('Issue search failed:', error)
    }
    return []
  }, [project])

  // 에픽 검색 함수 (에픽 링크용 - 에픽 타입만 검색)
  const searchEpics = useCallback(async (query: string): Promise<SearchableOption[]> => {
    if (!query || query.length < 2) return []
    try {
      // 에픽 타입만 검색 (type = Epic)
      const jql = project
        ? `project = ${project} AND type = Epic AND (summary ~ "${query}*" OR key ~ "${query}") ORDER BY updated DESC`
        : `type = Epic AND (summary ~ "${query}*" OR key ~ "${query}") ORDER BY updated DESC`
      const response = await jiraApi.searchIssues(jql)
      if (response.success && response.data) {
        return response.data.slice(0, 20).map(issue => ({
          value: issue.key,
          label: issue.key,
          sublabel: issue.summary,
          icon: <Zap className="h-4 w-4 text-purple-500" />,
        }))
      }
    } catch (error) {
      console.error('Epic search failed:', error)
    }
    return []
  }, [project])

  // 이슈 생성 뮤테이션
  const createMutation = useMutation({
    mutationFn: async () => {
      // 모든 필드를 API에 전달
      return jiraApi.createIssue(project, summary, description || undefined, issueType, {
        priority: priority !== 'Medium' ? priority : undefined,
        parentIssue: parentIssue || undefined,
        epicLink: epicLink || undefined,
        assignee: assignee || undefined,
        reporter: reporter || undefined,
        labels: labels.length > 0 ? labels : undefined,
        components: components.length > 0 ? components : undefined,
        storyPoints: storyPoints ? parseInt(storyPoints) : undefined,
        originalEstimate: originalEstimate || undefined,
        startDate: startDate || undefined,
        dueDate: dueDate || undefined,
        sprintId: selectedSprint || undefined,
      })
    },
    onSuccess: (result) => {
      if (result.success && result.data?.key) {
        onCreated(result.data.key)
      }
    },
  })

  // AI로 이슈 분석
  const analyzeWithAI = useCallback(async () => {
    if (!naturalInput.trim() || naturalInput.length < 5) return

    setAiProcessing(true)

    try {
      const response = await jiraApi.analyzeIssueFromText(naturalInput)
      if (response.success && response.data) {
        // AI 결과를 필드에 적용
        setSummary(response.data.summary || naturalInput.slice(0, 100))
        setDescription(response.data.description || '')
        setIssueType(response.data.issueType || 'Task')
        setPriority(response.data.priority || 'Medium')
        if (response.data.labels) {
          setLabels(response.data.labels)
        }
        setAiApplied(true)
      } else {
        // 기본 파싱
        setSummary(naturalInput.slice(0, 100))
        setDescription(naturalInput.length > 100 ? naturalInput : '')
        setIssueType(naturalInput.toLowerCase().includes('버그') || naturalInput.toLowerCase().includes('bug') ? 'Bug' : 'Task')
        setPriority(naturalInput.includes('긴급') || naturalInput.includes('urgent') ? 'High' : 'Medium')
        setAiApplied(true)
      }
    } catch (error) {
      console.error('AI analysis failed:', error)
      // 폴백: 기본 파싱
      setSummary(naturalInput.slice(0, 100))
      setDescription(naturalInput.length > 100 ? naturalInput : '')
      setIssueType('Task')
      setPriority('Medium')
      setAiApplied(true)
    } finally {
      setAiProcessing(false)
    }
  }, [naturalInput])

  // 라벨 추가
  const addLabel = useCallback(() => {
    if (labelInput.trim() && !labels.includes(labelInput.trim())) {
      setLabels([...labels, labelInput.trim()])
      setLabelInput('')
    }
  }, [labelInput, labels])

  // 컴포넌트 추가
  const addComponent = useCallback(() => {
    if (componentInput.trim() && !components.includes(componentInput.trim())) {
      setComponents([...components, componentInput.trim()])
      setComponentInput('')
    }
  }, [componentInput, components])

  // 현재 우선순위/이슈타입 정보
  const currentPriority = PRIORITIES.find(p => p.value === priority)
  const currentIssueType = ISSUE_TYPES.find(t => t.value === issueType)

  // 유효성 검사
  const isValid = project && summary.trim().length > 0

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm">
      <div className="bg-background rounded-2xl shadow-2xl w-full max-w-3xl mx-4 max-h-[90vh] overflow-hidden flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b bg-gradient-to-r from-purple-500/10 via-blue-500/10 to-cyan-500/10">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-blue-600 rounded-xl flex items-center justify-center shadow-lg">
              <Sparkles className="h-5 w-5 text-white" />
            </div>
            <div>
              <h2 className="text-lg font-semibold">새 이슈 만들기</h2>
              <p className="text-xs text-muted-foreground">AI가 자동으로 필드를 채워드립니다</p>
            </div>
          </div>
          <button onClick={onClose} className="p-2 hover:bg-muted rounded-lg transition-colors">
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto">
          {/* AI 도우미 섹션 */}
          <div className="px-6 py-4 bg-gradient-to-br from-purple-50/50 via-blue-50/50 to-cyan-50/50 dark:from-purple-900/10 dark:via-blue-900/10 dark:to-cyan-900/10 border-b">
            <div className="flex items-center gap-2 mb-3">
              <Wand2 className="h-4 w-4 text-purple-500" />
              <span className="text-sm font-medium">AI 도우미</span>
              {aiApplied && (
                <span className="text-xs bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-300 px-2 py-0.5 rounded-full flex items-center gap-1">
                  <CheckCircle2 className="h-3 w-3" />
                  적용됨
                </span>
              )}
            </div>
            <div className="flex gap-2">
              <input
                type="text"
                value={naturalInput}
                onChange={(e) => setNaturalInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault()
                    analyzeWithAI()
                  }
                }}
                placeholder="만들고 싶은 이슈를 자연어로 설명하세요 (예: 로그인 버튼이 안 눌려요, 긴급하게 수정 필요)"
                className="flex-1 px-4 py-2.5 border rounded-xl bg-white dark:bg-background focus:ring-2 focus:ring-purple-500/50 focus:border-purple-500"
              />
              <button
                onClick={analyzeWithAI}
                disabled={aiProcessing || naturalInput.trim().length < 5}
                className="px-4 py-2.5 bg-gradient-to-r from-purple-600 to-blue-600 hover:from-purple-700 hover:to-blue-700 text-white font-medium rounded-xl transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 shadow-lg"
              >
                {aiProcessing ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Sparkles className="h-4 w-4" />
                )}
                분석
              </button>
            </div>
          </div>

          {/* 필드 섹션 */}
          <div className="p-6 space-y-6">
            {/* 기본 정보 그룹 */}
            <div className="grid grid-cols-2 gap-4">
              {/* 프로젝트 */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground">
                  프로젝트 <span className="text-red-500">*</span>
                </label>
                <SearchableSelect
                  value={project}
                  onChange={setProject}
                  options={projectOptions}
                  placeholder="프로젝트 선택"
                  searchPlaceholder="프로젝트 검색..."
                  icon={<FolderKanban className="h-4 w-4" />}
                />
              </div>

              {/* 이슈 타입 */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground">이슈 타입</label>
                <div className="relative">
                  <button
                    onClick={() => setShowIssueTypeDropdown(!showIssueTypeDropdown)}
                    className="w-full px-3 py-2 border rounded-lg flex items-center justify-between bg-background hover:bg-muted/50 transition-colors"
                  >
                    <span className="flex items-center gap-2">
                      {currentIssueType && <currentIssueType.icon className={cn("h-4 w-4", currentIssueType.color)} />}
                      <span>{currentIssueType?.label || issueType}</span>
                    </span>
                    <ChevronDown className="h-4 w-4 text-muted-foreground" />
                  </button>
                  {showIssueTypeDropdown && (
                    <>
                      <div className="fixed inset-0 z-10" onClick={() => setShowIssueTypeDropdown(false)} />
                      <div className="absolute top-full left-0 right-0 mt-1 bg-background border rounded-lg shadow-lg z-20 overflow-hidden">
                        {ISSUE_TYPES.map((type) => (
                          <button
                            key={type.value}
                            onClick={() => {
                              setIssueType(type.value)
                              setShowIssueTypeDropdown(false)
                            }}
                            className={cn(
                              "w-full px-3 py-2 flex items-center gap-2 hover:bg-muted transition-colors",
                              issueType === type.value && type.bg
                            )}
                          >
                            <type.icon className={cn("h-4 w-4", type.color)} />
                            <span>{type.label}</span>
                          </button>
                        ))}
                      </div>
                    </>
                  )}
                </div>
              </div>
            </div>

            {/* 제목 */}
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-muted-foreground">
                제목 <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={summary}
                onChange={(e) => setSummary(e.target.value)}
                className={cn(
                  "w-full px-3 py-2.5 border rounded-lg bg-background focus:ring-2 focus:ring-purple-500/50 focus:border-purple-500",
                  aiApplied && summary && "ring-2 ring-green-500/30 border-green-500"
                )}
                placeholder="이슈 제목"
              />
            </div>

            {/* 관계 필드 */}
            <div className="grid grid-cols-2 gap-4">
              {/* Parent Issue */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <GitBranch className="h-3.5 w-3.5" />
                  상위 이슈 (Parent)
                </label>
                <AsyncSearchableSelect
                  value={parentIssue}
                  onChange={setParentIssue}
                  fetchOptions={searchIssues}
                  placeholder="이슈 검색..."
                  searchPlaceholder="이슈 키 또는 제목 검색..."
                  icon={<GitBranch className="h-4 w-4" />}
                  allowCustomValue
                  emptyMessage="2글자 이상 입력하세요"
                />
              </div>

              {/* Epic Link */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <Link2 className="h-3.5 w-3.5" />
                  에픽 링크
                </label>
                <AsyncSearchableSelect
                  value={epicLink}
                  onChange={setEpicLink}
                  fetchOptions={searchEpics}
                  placeholder="에픽 검색..."
                  searchPlaceholder="에픽 키 또는 제목 검색..."
                  icon={<Zap className="h-4 w-4" />}
                  allowCustomValue
                  emptyMessage="2글자 이상 입력하세요"
                />
              </div>
            </div>

            {/* 스프린트 */}
            <div className="grid grid-cols-2 gap-4">
              {/* Board */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <IterationCcw className="h-3.5 w-3.5" />
                  보드
                </label>
                <SearchableSelect
                  value={selectedBoard?.toString() || ''}
                  onChange={(v) => setSelectedBoard(v ? parseInt(v) : null)}
                  options={boardOptions}
                  placeholder={boardsLoading ? "로딩 중..." : "보드 선택"}
                  searchPlaceholder="보드 검색..."
                  loading={boardsLoading}
                  disabled={!project || boardsLoading}
                  icon={<IterationCcw className="h-4 w-4" />}
                  emptyMessage={!project ? "프로젝트를 먼저 선택하세요" : "보드가 없습니다"}
                />
              </div>

              {/* Sprint */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <IterationCcw className="h-3.5 w-3.5" />
                  스프린트
                </label>
                <SearchableSelect
                  value={selectedSprint?.toString() || ''}
                  onChange={(v) => setSelectedSprint(v ? parseInt(v) : null)}
                  options={sprintOptions}
                  placeholder={sprintsLoading ? "로딩 중..." : "스프린트 선택"}
                  searchPlaceholder="스프린트 검색..."
                  loading={sprintsLoading}
                  disabled={!selectedBoard || sprintsLoading}
                  icon={<IterationCcw className="h-4 w-4" />}
                  emptyMessage={!selectedBoard ? "보드를 먼저 선택하세요" : "스프린트가 없습니다"}
                />
              </div>
            </div>

            {/* 우선순위 */}
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                <Flag className="h-3.5 w-3.5" />
                우선순위
              </label>
              <div className="relative">
                <button
                  onClick={() => setShowPriorityDropdown(!showPriorityDropdown)}
                  className={cn(
                    "w-full px-3 py-2 border rounded-lg flex items-center justify-between transition-colors",
                    currentPriority?.bg
                  )}
                >
                  <span className="flex items-center gap-2">
                    <span>{currentPriority?.icon}</span>
                    <span className={currentPriority?.color}>{currentPriority?.label}</span>
                  </span>
                  <ChevronDown className="h-4 w-4 text-muted-foreground" />
                </button>
                {showPriorityDropdown && (
                  <>
                    <div className="fixed inset-0 z-10" onClick={() => setShowPriorityDropdown(false)} />
                    <div className="absolute top-full left-0 right-0 mt-1 bg-background border rounded-lg shadow-lg z-20 overflow-hidden">
                      {PRIORITIES.map((p) => (
                        <button
                          key={p.value}
                          onClick={() => {
                            setPriority(p.value)
                            setShowPriorityDropdown(false)
                          }}
                          className={cn(
                            "w-full px-3 py-2 flex items-center gap-2 hover:bg-muted transition-colors",
                            priority === p.value && p.bg
                          )}
                        >
                          <span>{p.icon}</span>
                          <span className={p.color}>{p.label}</span>
                        </button>
                      ))}
                    </div>
                  </>
                )}
              </div>
            </div>

            {/* 담당자 */}
            <div className="grid grid-cols-2 gap-4">
              {/* Assignee */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <User className="h-3.5 w-3.5" />
                  담당자 (Assignee)
                </label>
                <AsyncSearchableSelect
                  value={assignee}
                  onChange={setAssignee}
                  fetchOptions={searchUsers}
                  placeholder="담당자 검색..."
                  searchPlaceholder="이름 또는 이메일로 검색..."
                  icon={<User className="h-4 w-4" />}
                  allowCustomValue
                  emptyMessage="2글자 이상 입력하세요"
                />
              </div>

              {/* Reporter */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <Users className="h-3.5 w-3.5" />
                  보고자 (Reporter)
                </label>
                <AsyncSearchableSelect
                  value={reporter}
                  onChange={setReporter}
                  fetchOptions={searchUsers}
                  placeholder="보고자 검색..."
                  searchPlaceholder="이름 또는 이메일로 검색..."
                  icon={<Users className="h-4 w-4" />}
                  allowCustomValue
                  emptyMessage="2글자 이상 입력하세요"
                />
              </div>
            </div>

            {/* 라벨 & 컴포넌트 */}
            <div className="grid grid-cols-2 gap-4">
              {/* Labels */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <Tag className="h-3.5 w-3.5" />
                  라벨
                </label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={labelInput}
                    onChange={(e) => setLabelInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault()
                        addLabel()
                      }
                    }}
                    className="flex-1 px-3 py-2 border rounded-lg bg-background focus:ring-2 focus:ring-purple-500/50"
                    placeholder="라벨 추가"
                  />
                  <button
                    onClick={addLabel}
                    className="px-3 py-2 bg-muted hover:bg-muted/80 rounded-lg transition-colors text-sm"
                  >
                    추가
                  </button>
                </div>
                {labels.length > 0 && (
                  <div className="flex flex-wrap gap-1.5 mt-2">
                    {labels.map((label) => (
                      <span
                        key={label}
                        className="inline-flex items-center gap-1 px-2 py-0.5 bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 text-xs rounded-full"
                      >
                        {label}
                        <button onClick={() => setLabels(labels.filter(l => l !== label))} className="hover:text-red-500">
                          <X className="h-3 w-3" />
                        </button>
                      </span>
                    ))}
                  </div>
                )}
              </div>

              {/* Components */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <Layers className="h-3.5 w-3.5" />
                  컴포넌트
                </label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={componentInput}
                    onChange={(e) => setComponentInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault()
                        addComponent()
                      }
                    }}
                    className="flex-1 px-3 py-2 border rounded-lg bg-background focus:ring-2 focus:ring-purple-500/50"
                    placeholder="컴포넌트 추가"
                  />
                  <button
                    onClick={addComponent}
                    className="px-3 py-2 bg-muted hover:bg-muted/80 rounded-lg transition-colors text-sm"
                  >
                    추가
                  </button>
                </div>
                {components.length > 0 && (
                  <div className="flex flex-wrap gap-1.5 mt-2">
                    {components.map((comp) => (
                      <span
                        key={comp}
                        className="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 text-xs rounded-full"
                      >
                        {comp}
                        <button onClick={() => setComponents(components.filter(c => c !== comp))} className="hover:text-red-500">
                          <X className="h-3 w-3" />
                        </button>
                      </span>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* 추정/계획 필드 */}
            <div className="grid grid-cols-4 gap-4">
              {/* Story Points */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <Target className="h-3.5 w-3.5" />
                  스토리 포인트
                </label>
                <input
                  type="number"
                  value={storyPoints}
                  onChange={(e) => setStoryPoints(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg bg-background focus:ring-2 focus:ring-purple-500/50"
                  placeholder="0"
                  min="0"
                />
              </div>

              {/* Original Estimate */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <Clock className="h-3.5 w-3.5" />
                  예상 시간
                </label>
                <input
                  type="text"
                  value={originalEstimate}
                  onChange={(e) => setOriginalEstimate(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg bg-background focus:ring-2 focus:ring-purple-500/50"
                  placeholder="예: 2h, 1d"
                />
              </div>

              {/* Start Date */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <Calendar className="h-3.5 w-3.5" />
                  시작일
                </label>
                <input
                  type="date"
                  value={startDate}
                  onChange={(e) => setStartDate(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg bg-background focus:ring-2 focus:ring-purple-500/50 [color-scheme:dark]"
                />
              </div>

              {/* Due Date */}
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-muted-foreground flex items-center gap-1">
                  <Calendar className="h-3.5 w-3.5" />
                  마감일
                </label>
                <input
                  type="date"
                  value={dueDate}
                  onChange={(e) => setDueDate(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg bg-background focus:ring-2 focus:ring-purple-500/50 [color-scheme:dark]"
                />
              </div>
            </div>

            {/* 설명 */}
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-muted-foreground flex items-center justify-between">
                <span className="flex items-center gap-1">
                  <FileText className="h-3.5 w-3.5" />
                  설명
                </span>
                <span className="text-xs">Markdown 지원</span>
              </label>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className={cn(
                  "w-full px-3 py-2.5 border rounded-lg bg-background focus:ring-2 focus:ring-purple-500/50 focus:border-purple-500 h-32 resize-none font-mono text-sm",
                  aiApplied && description && "ring-2 ring-green-500/30 border-green-500"
                )}
                placeholder="이슈에 대한 자세한 설명..."
              />
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between px-6 py-4 border-t bg-muted/30">
          <div className="text-xs text-muted-foreground">
            {isValid ? (
              <span className="flex items-center gap-1 text-green-600">
                <CheckCircle2 className="h-3.5 w-3.5" />
                생성 준비 완료
              </span>
            ) : (
              <span className="flex items-center gap-1 text-amber-600">
                <AlertCircle className="h-3.5 w-3.5" />
                프로젝트와 제목을 입력하세요
              </span>
            )}
          </div>
          <div className="flex gap-3">
            <button
              onClick={onClose}
              className="px-4 py-2 text-sm font-medium hover:bg-muted rounded-lg transition-colors"
            >
              취소
            </button>
            <button
              onClick={() => createMutation.mutate()}
              disabled={!isValid || createMutation.isPending}
              className="px-6 py-2 text-sm font-medium bg-gradient-to-r from-purple-600 to-blue-600 text-white rounded-lg hover:from-purple-700 hover:to-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-all flex items-center gap-2 shadow-lg"
            >
              {createMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              이슈 생성
            </button>
          </div>
        </div>

        {/* Error */}
        {createMutation.isError && (
          <div className="px-6 pb-4">
            <p className="text-sm text-red-500 flex items-center gap-2">
              <AlertCircle className="h-4 w-4" />
              이슈 생성에 실패했습니다. 다시 시도해주세요.
            </p>
          </div>
        )}
      </div>
    </div>
  )
}
