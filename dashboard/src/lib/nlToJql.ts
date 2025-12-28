/**
 * 자연어 → JQL 변환 유틸리티
 *
 * 사용자의 자연어 검색을 JQL로 변환합니다.
 * 빠른 응답을 위해 패턴 매칭 기반으로 동작합니다.
 */

export interface JqlConversionResult {
  jql: string
  parts: JqlPart[]
  confidence: number // 0-1
  suggestions?: string[]
}

export interface JqlPart {
  type: 'project' | 'status' | 'assignee' | 'reporter' | 'priority' | 'type' | 'date' | 'text' | 'label'
  original: string
  jql: string
}

// 상태 매핑 (자연어 → JQL 상태)
const STATUS_PATTERNS: Record<string, string[]> = {
  'Backlog': ['백로그', 'backlog', '대기', '대기중'],
  'To Do': ['할일', '할 일', '해야할', 'todo', 'to do', '해야 할'],
  'In Progress': ['진행', '진행중', '진행 중', '작업중', '작업 중', 'in progress', 'working', '시작'],
  'In Review': ['리뷰', '검토', '검토중', '검토 중', 'review', 'in review', 'qa', 'QA'],
  'Done': ['완료', '끝난', '종료', 'done', 'closed', '해결', 'resolved'],
}

// 우선순위 매핑
const PRIORITY_PATTERNS: Record<string, string[]> = {
  'Highest': ['최고', '긴급', 'highest', 'critical', 'p1'],
  'High': ['높음', '높은', 'high', 'p2'],
  'Medium': ['중간', '보통', 'medium', 'normal', 'p3'],
  'Low': ['낮음', '낮은', 'low', 'p4'],
  'Lowest': ['최저', 'lowest', 'p5'],
}

// 이슈 타입 매핑
const TYPE_PATTERNS: Record<string, string[]> = {
  'Bug': ['버그', 'bug', '오류', '에러', 'error'],
  'Story': ['스토리', 'story', '기능'],
  'Task': ['작업', 'task', '태스크'],
  'Epic': ['에픽', 'epic'],
  'Sub-task': ['하위작업', '서브태스크', 'subtask', 'sub-task'],
}

// 날짜 패턴
const DATE_PATTERNS = [
  { pattern: /오늘/g, jql: 'created >= startOfDay()' },
  { pattern: /이번\s*주/g, jql: 'created >= startOfWeek()' },
  { pattern: /이번\s*달/g, jql: 'created >= startOfMonth()' },
  { pattern: /지난\s*주/g, jql: 'created >= -7d' },
  { pattern: /지난\s*달/g, jql: 'created >= -30d' },
  { pattern: /최근\s*(\d+)\s*일/g, jql: (days: string) => `created >= -${days}d` },
  { pattern: /(\d+)\s*일\s*(이내|내)/g, jql: (days: string) => `created >= -${days}d` },
  { pattern: /today/gi, jql: 'created >= startOfDay()' },
  { pattern: /this\s*week/gi, jql: 'created >= startOfWeek()' },
  { pattern: /this\s*month/gi, jql: 'created >= startOfMonth()' },
  { pattern: /last\s*(\d+)\s*days?/gi, jql: (days: string) => `created >= -${days}d` },
]

// 담당자 패턴
const ASSIGNEE_PATTERNS = [
  { pattern: /내\s*(이슈|것|거)/g, jql: 'assignee = currentUser()' },
  { pattern: /나한테\s*할당/g, jql: 'assignee = currentUser()' },
  { pattern: /내가\s*(담당|작업)/g, jql: 'assignee = currentUser()' },
  { pattern: /미할당/g, jql: 'assignee is EMPTY' },
  { pattern: /담당자\s*없/g, jql: 'assignee is EMPTY' },
  { pattern: /unassigned/gi, jql: 'assignee is EMPTY' },
  { pattern: /my\s*issues?/gi, jql: 'assignee = currentUser()' },
  { pattern: /assigned\s*to\s*me/gi, jql: 'assignee = currentUser()' },
]

// 리포터 패턴
const REPORTER_PATTERNS = [
  { pattern: /내가\s*(만든|생성|등록)/g, jql: 'reporter = currentUser()' },
  { pattern: /내가\s*보고/g, jql: 'reporter = currentUser()' },
  { pattern: /i\s*(created|reported)/gi, jql: 'reporter = currentUser()' },
]

/**
 * 자연어를 JQL로 변환
 */
export function convertNlToJql(input: string, projectKeys?: string[]): JqlConversionResult {
  const parts: JqlPart[] = []
  let remainingInput = input.trim()
  let confidence = 0

  // 1. 프로젝트 키 추출 (대문자 2-10자 + 선택적으로 이슈 번호)
  const projectPattern = /\b([A-Z]{2,10})(?:-\d+)?\b/g
  let projectMatch
  const detectedProjects: string[] = []

  while ((projectMatch = projectPattern.exec(input)) !== null) {
    const projectKey = projectMatch[1]
    // 알려진 프로젝트 키인지 확인 (제공된 경우)
    if (!projectKeys || projectKeys.includes(projectKey)) {
      if (!detectedProjects.includes(projectKey)) {
        detectedProjects.push(projectKey)
      }
    }
  }

  if (detectedProjects.length > 0) {
    const jql = detectedProjects.length === 1
      ? `project = ${detectedProjects[0]}`
      : `project IN (${detectedProjects.join(', ')})`
    parts.push({
      type: 'project',
      original: detectedProjects.join(', '),
      jql,
    })
    confidence += 0.2
  }

  // 2. 상태 추출
  for (const [status, patterns] of Object.entries(STATUS_PATTERNS)) {
    for (const pattern of patterns) {
      if (remainingInput.toLowerCase().includes(pattern.toLowerCase())) {
        parts.push({
          type: 'status',
          original: pattern,
          jql: `status = "${status}"`,
        })
        remainingInput = remainingInput.replace(new RegExp(pattern, 'gi'), '')
        confidence += 0.2
        break
      }
    }
  }

  // 3. 우선순위 추출
  for (const [priority, patterns] of Object.entries(PRIORITY_PATTERNS)) {
    for (const pattern of patterns) {
      if (remainingInput.toLowerCase().includes(pattern.toLowerCase())) {
        parts.push({
          type: 'priority',
          original: pattern,
          jql: `priority = "${priority}"`,
        })
        remainingInput = remainingInput.replace(new RegExp(pattern, 'gi'), '')
        confidence += 0.15
        break
      }
    }
  }

  // 4. 이슈 타입 추출
  for (const [type, patterns] of Object.entries(TYPE_PATTERNS)) {
    for (const pattern of patterns) {
      if (remainingInput.toLowerCase().includes(pattern.toLowerCase())) {
        parts.push({
          type: 'type',
          original: pattern,
          jql: `issuetype = "${type}"`,
        })
        remainingInput = remainingInput.replace(new RegExp(pattern, 'gi'), '')
        confidence += 0.15
        break
      }
    }
  }

  // 5. 담당자 패턴 추출
  for (const { pattern, jql } of ASSIGNEE_PATTERNS) {
    if (pattern.test(remainingInput)) {
      parts.push({
        type: 'assignee',
        original: remainingInput.match(pattern)?.[0] || '',
        jql,
      })
      remainingInput = remainingInput.replace(pattern, '')
      confidence += 0.2
      break
    }
  }

  // 6. 리포터 패턴 추출
  for (const { pattern, jql } of REPORTER_PATTERNS) {
    if (pattern.test(remainingInput)) {
      parts.push({
        type: 'reporter',
        original: remainingInput.match(pattern)?.[0] || '',
        jql,
      })
      remainingInput = remainingInput.replace(pattern, '')
      confidence += 0.15
      break
    }
  }

  // 7. 날짜 패턴 추출
  for (const { pattern, jql } of DATE_PATTERNS) {
    const match = remainingInput.match(pattern)
    if (match) {
      const jqlStr = typeof jql === 'function' ? jql(match[1]) : jql
      parts.push({
        type: 'date',
        original: match[0],
        jql: jqlStr,
      })
      remainingInput = remainingInput.replace(pattern, '')
      confidence += 0.15
      break
    }
  }

  // 8. 텍스트 검색 (남은 의미있는 텍스트)
  const cleanedText = remainingInput
    .replace(/[,.\s]+/g, ' ')
    .replace(/보여줘|검색|찾아|이슈|조회/g, '')
    .trim()

  if (cleanedText.length >= 2) {
    parts.push({
      type: 'text',
      original: cleanedText,
      jql: `text ~ "${cleanedText}"`,
    })
    confidence += 0.1
  }

  // JQL 조합
  const jqlParts = parts.map(p => p.jql)
  const jql = jqlParts.length > 0
    ? `${jqlParts.join(' AND ')} ORDER BY updated DESC`
    : 'ORDER BY updated DESC'

  // 신뢰도 정규화
  confidence = Math.min(confidence, 1)

  // 제안 생성
  const suggestions: string[] = []
  if (parts.length === 0) {
    suggestions.push('"내 이슈 보여줘"')
    suggestions.push('"PROJ 진행중인 버그"')
    suggestions.push('"이번주 생성된 이슈"')
  }

  return {
    jql,
    parts,
    confidence,
    suggestions: suggestions.length > 0 ? suggestions : undefined,
  }
}

/**
 * JQL 파트를 한글 설명으로 변환
 */
export function describeJqlParts(parts: JqlPart[]): string {
  if (parts.length === 0) return '모든 이슈'

  const descriptions: string[] = []

  for (const part of parts) {
    switch (part.type) {
      case 'project':
        descriptions.push(`프로젝트: ${part.original}`)
        break
      case 'status':
        descriptions.push(`상태: ${part.original}`)
        break
      case 'assignee':
        if (part.jql.includes('currentUser')) {
          descriptions.push('내게 할당된')
        } else if (part.jql.includes('EMPTY')) {
          descriptions.push('미할당')
        }
        break
      case 'reporter':
        descriptions.push('내가 등록한')
        break
      case 'priority':
        descriptions.push(`우선순위: ${part.original}`)
        break
      case 'type':
        descriptions.push(`타입: ${part.original}`)
        break
      case 'date':
        descriptions.push(`기간: ${part.original}`)
        break
      case 'text':
        descriptions.push(`검색어: "${part.original}"`)
        break
    }
  }

  return descriptions.join(', ')
}

/**
 * 최근 검색 기록 관리
 */
const RECENT_SEARCHES_KEY = 'jira-recent-searches'
const MAX_RECENT_SEARCHES = 10

export interface RecentSearch {
  id: string
  naturalQuery: string
  jql: string
  timestamp: number
}

export function saveRecentSearch(naturalQuery: string, jql: string): void {
  const searches = getRecentSearches()

  // 중복 제거
  const filtered = searches.filter(s => s.naturalQuery !== naturalQuery)

  // 새 검색 추가
  const newSearch: RecentSearch = {
    id: Date.now().toString(),
    naturalQuery,
    jql,
    timestamp: Date.now(),
  }

  filtered.unshift(newSearch)

  // 최대 개수 유지
  const trimmed = filtered.slice(0, MAX_RECENT_SEARCHES)

  localStorage.setItem(RECENT_SEARCHES_KEY, JSON.stringify(trimmed))
}

export function getRecentSearches(): RecentSearch[] {
  try {
    const stored = localStorage.getItem(RECENT_SEARCHES_KEY)
    return stored ? JSON.parse(stored) : []
  } catch {
    return []
  }
}

export function clearRecentSearches(): void {
  localStorage.removeItem(RECENT_SEARCHES_KEY)
}

export function removeRecentSearch(id: string): void {
  const searches = getRecentSearches().filter(s => s.id !== id)
  localStorage.setItem(RECENT_SEARCHES_KEY, JSON.stringify(searches))
}

/**
 * 즐겨찾기 검색 관리
 */
const FAVORITE_SEARCHES_KEY = 'jira-favorite-searches'

export interface FavoriteSearch {
  id: string
  name: string
  naturalQuery: string
  jql: string
  createdAt: number
}

export function saveFavoriteSearch(name: string, naturalQuery: string, jql: string): void {
  const favorites = getFavoriteSearches()

  const newFavorite: FavoriteSearch = {
    id: Date.now().toString(),
    name,
    naturalQuery,
    jql,
    createdAt: Date.now(),
  }

  favorites.push(newFavorite)
  localStorage.setItem(FAVORITE_SEARCHES_KEY, JSON.stringify(favorites))
}

export function getFavoriteSearches(): FavoriteSearch[] {
  try {
    const stored = localStorage.getItem(FAVORITE_SEARCHES_KEY)
    return stored ? JSON.parse(stored) : []
  } catch {
    return []
  }
}

export function removeFavoriteSearch(id: string): void {
  const favorites = getFavoriteSearches().filter(f => f.id !== id)
  localStorage.setItem(FAVORITE_SEARCHES_KEY, JSON.stringify(favorites))
}
