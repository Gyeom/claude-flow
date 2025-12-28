import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import {
  Search,
  X,
  Clock,
  Star,
  Copy,
  Sparkles,
  History,
  Bookmark,
  Trash2,
  Edit3,
  Loader2,
  Bot,
  Wand2,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { jiraApi } from '@/lib/api'
import {
  getRecentSearches,
  saveRecentSearch,
  removeRecentSearch,
  clearRecentSearches,
  getFavoriteSearches,
  saveFavoriteSearch,
  removeFavoriteSearch,
  type RecentSearch,
  type FavoriteSearch,
} from '@/lib/nlToJql'

interface SmartSearchProps {
  onSearch: (jql: string) => void
  projectKeys?: string[]
  placeholder?: string
  className?: string
}

type ViewMode = 'search' | 'recent' | 'favorites'

export function SmartSearch({
  onSearch,
  projectKeys,
  placeholder = "자연어로 검색하세요... (예: 내 이슈 중 진행중인 버그)",
  className,
}: SmartSearchProps) {
  const [input, setInput] = useState('')
  const [isFocused, setIsFocused] = useState(false)
  const [viewMode, setViewMode] = useState<ViewMode>('search')
  const [jqlResult, setJqlResult] = useState<{
    jql: string
    explanation?: string
    warnings?: string[]
  } | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [isEditing, setIsEditing] = useState(false)
  const [editedJql, setEditedJql] = useState('')
  const [lastConvertedInput, setLastConvertedInput] = useState('') // 마지막으로 변환한 입력 추적
  const [recentSearches, setRecentSearches] = useState<RecentSearch[]>([])
  const [favoriteSearches, setFavoriteSearches] = useState<FavoriteSearch[]>([])
  const [saveFavoriteModalOpen, setSaveFavoriteModalOpen] = useState(false)
  const [favoriteName, setFavoriteName] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  // 최근 검색 및 즐겨찾기 로드
  useEffect(() => {
    setRecentSearches(getRecentSearches())
    setFavoriteSearches(getFavoriteSearches())
  }, [])

  // Claude AI를 통한 JQL 변환
  const convertToJql = useCallback(async () => {
    if (!input.trim() || input.length < 2) return

    setIsLoading(true)
    setJqlResult(null)

    try {
      const response = await jiraApi.nlToJql(input, true)
      if (response.success && response.jql) {
        setJqlResult({
          jql: response.jql,
          explanation: response.explanation,
          warnings: response.warnings,
        })
        setEditedJql(response.jql)
        setLastConvertedInput(input.trim()) // 변환 성공 시 마지막 입력 저장
      } else {
        // 실패 시 기본 텍스트 검색
        const fallbackJql = `text ~ "${input}" ORDER BY updated DESC`
        setJqlResult({
          jql: fallbackJql,
          explanation: '텍스트 검색으로 대체됨',
        })
        setEditedJql(fallbackJql)
        setLastConvertedInput(input.trim())
      }
    } catch (error) {
      console.error('JQL conversion failed:', error)
      // 에러 시 기본 텍스트 검색
      const fallbackJql = `text ~ "${input}" ORDER BY updated DESC`
      setJqlResult({
        jql: fallbackJql,
        explanation: 'API 오류로 텍스트 검색으로 대체됨',
      })
      setEditedJql(fallbackJql)
      setLastConvertedInput(input.trim())
    } finally {
      setIsLoading(false)
    }
  }, [input])

  // 입력이 변경되었는지 확인 (재변환 필요 여부)
  const inputChanged = useMemo(() => {
    return jqlResult && input.trim() !== lastConvertedInput
  }, [jqlResult, input, lastConvertedInput])

  // 외부 클릭 감지
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsFocused(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  // 현재 사용할 JQL
  const getCurrentJql = useCallback(() => {
    if (isEditing) return editedJql
    return jqlResult?.jql
  }, [isEditing, editedJql, jqlResult])

  // 검색 실행
  const handleSearch = useCallback(() => {
    const jql = getCurrentJql()
    if (jql && input.trim()) {
      saveRecentSearch(input.trim(), jql)
      setRecentSearches(getRecentSearches())
      onSearch(jql)
      setIsFocused(false)
    }
  }, [input, getCurrentJql, onSearch])

  // 최근 검색 선택
  const handleSelectRecent = useCallback((search: RecentSearch) => {
    setInput(search.naturalQuery)
    onSearch(search.jql)
    setIsFocused(false)
  }, [onSearch])

  // 즐겨찾기 선택
  const handleSelectFavorite = useCallback((favorite: FavoriteSearch) => {
    setInput(favorite.naturalQuery)
    onSearch(favorite.jql)
    setIsFocused(false)
  }, [onSearch])

  // 즐겨찾기 저장
  const handleSaveFavorite = useCallback(() => {
    const jql = getCurrentJql()
    if (favoriteName.trim() && jql) {
      saveFavoriteSearch(favoriteName.trim(), input, jql)
      setFavoriteSearches(getFavoriteSearches())
      setSaveFavoriteModalOpen(false)
      setFavoriteName('')
    }
  }, [favoriteName, input, getCurrentJql])

  // JQL 복사
  const handleCopyJql = useCallback(async () => {
    const jql = getCurrentJql()
    if (jql) {
      await navigator.clipboard.writeText(jql)
    }
  }, [getCurrentJql])

  // 최근 검색 삭제
  const handleRemoveRecent = useCallback((id: string, e: React.MouseEvent) => {
    e.stopPropagation()
    removeRecentSearch(id)
    setRecentSearches(getRecentSearches())
  }, [])

  // 즐겨찾기 삭제
  const handleRemoveFavorite = useCallback((id: string, e: React.MouseEvent) => {
    e.stopPropagation()
    removeFavoriteSearch(id)
    setFavoriteSearches(getFavoriteSearches())
  }, [])

  // 추천 검색어
  const suggestions = useMemo((): Array<{ label: string; query: string }> => {
    const defaultSuggestions = [
      { label: '내 이슈', query: '내 이슈 보여줘' },
      { label: '진행중', query: '진행중인 이슈' },
      { label: '이번주 생성', query: '이번주 생성된 이슈' },
      { label: '긴급', query: '긴급 우선순위' },
    ]

    // 프로젝트 키가 있으면 프로젝트별 제안 추가
    if (projectKeys && projectKeys.length > 0) {
      return [
        ...projectKeys.slice(0, 3).map(key => ({
          label: key,
          query: `${key} 프로젝트`,
        })),
        ...defaultSuggestions.slice(0, 3),
      ]
    }

    return defaultSuggestions
  }, [projectKeys])

  const showDropdown = isFocused && (input.trim() || recentSearches.length > 0 || favoriteSearches.length > 0)

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      {/* 검색 입력 영역 */}
      <div className={cn(
        "flex items-center gap-2 border rounded-lg transition-all",
        isFocused ? "ring-2 ring-purple-500/50 border-purple-500" : "border-border",
        "bg-background"
      )}>
        <div className="flex items-center gap-2 px-3 py-2.5 flex-1">
          <Sparkles className={cn(
            "h-4 w-4 flex-shrink-0 transition-colors",
            isFocused ? "text-purple-500" : "text-muted-foreground"
          )} />
          <input
            ref={inputRef}
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onFocus={() => setIsFocused(true)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                handleSearch()
              }
              if (e.key === 'Escape') {
                setIsFocused(false)
              }
            }}
            placeholder={placeholder}
            className="flex-1 bg-transparent text-sm focus:outline-none placeholder:text-muted-foreground"
          />
          {input && (
            <button
              onClick={() => {
                setInput('')
                inputRef.current?.focus()
              }}
              className="p-1 hover:bg-muted rounded transition-colors"
            >
              <X className="h-4 w-4 text-muted-foreground" />
            </button>
          )}
        </div>
      </div>

      {/* 드롭다운 패널 */}
      {showDropdown && (
        <div className="absolute top-full left-0 right-0 mt-2 bg-background border rounded-xl shadow-xl z-50 overflow-hidden">
          {/* 탭 네비게이션 */}
          {!input.trim() && (
            <div className="flex border-b">
              <button
                onClick={() => setViewMode('recent')}
                className={cn(
                  "flex-1 px-4 py-2.5 text-sm font-medium transition-colors flex items-center justify-center gap-2",
                  viewMode === 'recent'
                    ? "text-purple-600 border-b-2 border-purple-600 bg-purple-50 dark:bg-purple-900/20"
                    : "text-muted-foreground hover:text-foreground hover:bg-muted/50"
                )}
              >
                <History className="h-4 w-4" />
                최근 검색
              </button>
              <button
                onClick={() => setViewMode('favorites')}
                className={cn(
                  "flex-1 px-4 py-2.5 text-sm font-medium transition-colors flex items-center justify-center gap-2",
                  viewMode === 'favorites'
                    ? "text-purple-600 border-b-2 border-purple-600 bg-purple-50 dark:bg-purple-900/20"
                    : "text-muted-foreground hover:text-foreground hover:bg-muted/50"
                )}
              >
                <Bookmark className="h-4 w-4" />
                즐겨찾기
              </button>
            </div>
          )}

          {/* 검색어 입력 중: JQL 변환 UI */}
          {input.trim() && (
            <div className="p-4 space-y-3">
              {/* JQL 변환 전: 변환 버튼 */}
              {!jqlResult && !isLoading && (
                <div className="text-center py-4">
                  <button
                    onClick={convertToJql}
                    className="inline-flex items-center gap-2 px-6 py-3 bg-gradient-to-r from-purple-600 to-blue-600 hover:from-purple-700 hover:to-blue-700 text-white font-medium rounded-xl transition-all shadow-lg hover:shadow-xl"
                  >
                    <Wand2 className="h-5 w-5" />
                    AI로 JQL 변환하기
                  </button>
                  <p className="text-xs text-muted-foreground mt-2">
                    Claude가 자연어를 분석하여 JQL로 변환합니다
                  </p>
                </div>
              )}

              {/* 로딩 중 */}
              {isLoading && (
                <div className="text-center py-6">
                  <Loader2 className="h-8 w-8 animate-spin text-purple-500 mx-auto" />
                  <p className="text-sm text-muted-foreground mt-2">
                    Claude가 분석 중입니다...
                  </p>
                </div>
              )}

              {/* JQL 변환 결과 */}
              {jqlResult && !isLoading && (
                <>
                  {/* 입력 변경 시 재변환 버튼 */}
                  {inputChanged && (
                    <div className="flex items-center gap-3 p-3 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-lg">
                      <div className="flex-1">
                        <p className="text-sm font-medium text-amber-800 dark:text-amber-200">검색어가 변경되었습니다</p>
                        <p className="text-xs text-amber-600 dark:text-amber-400">새로운 JQL로 다시 변환하시겠습니까?</p>
                      </div>
                      <button
                        onClick={convertToJql}
                        className="px-4 py-2 bg-amber-500 hover:bg-amber-600 text-white text-sm font-medium rounded-lg transition-colors flex items-center gap-2"
                      >
                        <Wand2 className="h-4 w-4" />
                        다시 변환
                      </button>
                    </div>
                  )}

                  {/* 분석 결과 설명 */}
                  {jqlResult.explanation && !inputChanged && (
                    <div className="flex items-start gap-2 text-sm bg-purple-50 dark:bg-purple-900/20 px-3 py-2 rounded-lg">
                      <Bot className="h-4 w-4 text-purple-500 flex-shrink-0 mt-0.5" />
                      <span className="text-purple-700 dark:text-purple-300">{jqlResult.explanation}</span>
                    </div>
                  )}

                  {/* 경고 메시지 */}
                  {jqlResult.warnings && jqlResult.warnings.length > 0 && (
                    <div className="text-xs text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 px-3 py-2 rounded-lg">
                      {jqlResult.warnings.join(', ')}
                    </div>
                  )}

                  {/* JQL 미리보기 */}
                  <div className="relative">
                    <div className="text-xs text-muted-foreground mb-1.5 flex items-center justify-between">
                      <span className="flex items-center gap-1">
                        <Bot className="h-3 w-3 text-purple-500" />
                        변환된 JQL:
                      </span>
                      <div className="flex items-center gap-1">
                        <button
                          onClick={() => {
                            setJqlResult(null)
                            setEditedJql('')
                          }}
                          className="p-1 hover:bg-muted rounded transition-colors text-muted-foreground"
                          title="다시 변환"
                        >
                          <Wand2 className="h-3.5 w-3.5" />
                        </button>
                        <button
                          onClick={() => setIsEditing(!isEditing)}
                          className={cn(
                            "p-1 rounded transition-colors",
                            isEditing
                              ? "bg-purple-100 text-purple-600 dark:bg-purple-900/30"
                              : "hover:bg-muted text-muted-foreground"
                          )}
                          title="JQL 직접 편집"
                        >
                          <Edit3 className="h-3.5 w-3.5" />
                        </button>
                        <button
                          onClick={handleCopyJql}
                          className="p-1 hover:bg-muted rounded transition-colors text-muted-foreground"
                          title="JQL 복사"
                        >
                          <Copy className="h-3.5 w-3.5" />
                        </button>
                        <button
                          onClick={() => setSaveFavoriteModalOpen(true)}
                          className="p-1 hover:bg-muted rounded transition-colors text-muted-foreground"
                          title="즐겨찾기에 저장"
                        >
                          <Star className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </div>
                    {isEditing ? (
                      <textarea
                        value={editedJql}
                        onChange={(e) => setEditedJql(e.target.value)}
                        className="w-full p-3 text-xs font-mono bg-slate-900 text-emerald-400 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500/50 resize-none"
                        rows={3}
                      />
                    ) : (
                      <pre className="p-3 text-xs font-mono bg-gradient-to-br from-slate-900 to-purple-900/50 text-emerald-400 rounded-lg overflow-x-auto whitespace-pre-wrap break-all">
                        {jqlResult.jql}
                      </pre>
                    )}
                  </div>

                  {/* 검색 실행 버튼 */}
                  <div className="flex items-center gap-2 pt-2 border-t">
                    <button
                      onClick={handleSearch}
                      className="flex-1 py-2.5 bg-purple-600 hover:bg-purple-700 text-white text-sm font-medium rounded-lg transition-colors flex items-center justify-center gap-2"
                    >
                      <Search className="h-4 w-4" />
                      검색 실행
                    </button>
                  </div>
                </>
              )}

              {/* 예시 (변환 전에만 표시) */}
              {!jqlResult && !isLoading && (
                <div className="text-xs text-muted-foreground text-center">
                  <span>예시: </span>
                  {['내 이슈 보여줘', 'PROJ 진행중인 버그', '이번주 완료된 이슈'].map((example, i) => (
                    <span key={i}>
                      {i > 0 && ', '}
                      <button
                        onClick={() => setInput(example)}
                        className="text-purple-600 hover:underline"
                      >
                        "{example}"
                      </button>
                    </span>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* 최근 검색 목록 */}
          {!input.trim() && viewMode === 'recent' && (
            <div className="max-h-64 overflow-y-auto">
              {recentSearches.length === 0 ? (
                <div className="p-6 text-center text-muted-foreground text-sm">
                  <Clock className="h-8 w-8 mx-auto mb-2 opacity-50" />
                  <p>최근 검색 기록이 없습니다</p>
                </div>
              ) : (
                <>
                  <div className="p-2 border-b flex items-center justify-between">
                    <span className="text-xs text-muted-foreground px-2">최근 검색 {recentSearches.length}개</span>
                    <button
                      onClick={() => {
                        clearRecentSearches()
                        setRecentSearches([])
                      }}
                      className="text-xs text-muted-foreground hover:text-foreground px-2"
                    >
                      전체 삭제
                    </button>
                  </div>
                  {recentSearches.map((search) => (
                    <button
                      key={search.id}
                      onClick={() => handleSelectRecent(search)}
                      className="w-full px-4 py-3 text-left hover:bg-muted transition-colors flex items-center gap-3 group"
                    >
                      <Clock className="h-4 w-4 text-muted-foreground flex-shrink-0" />
                      <div className="flex-1 min-w-0">
                        <p className="text-sm truncate">{search.naturalQuery}</p>
                        <p className="text-xs text-muted-foreground font-mono truncate">{search.jql}</p>
                      </div>
                      <button
                        onClick={(e) => handleRemoveRecent(search.id, e)}
                        className="p-1 opacity-0 group-hover:opacity-100 hover:bg-red-100 dark:hover:bg-red-900/30 rounded transition-all"
                      >
                        <Trash2 className="h-3.5 w-3.5 text-red-500" />
                      </button>
                    </button>
                  ))}
                </>
              )}
            </div>
          )}

          {/* 즐겨찾기 목록 */}
          {!input.trim() && viewMode === 'favorites' && (
            <div className="max-h-64 overflow-y-auto">
              {favoriteSearches.length === 0 ? (
                <div className="p-6 text-center text-muted-foreground text-sm">
                  <Star className="h-8 w-8 mx-auto mb-2 opacity-50" />
                  <p>저장된 즐겨찾기가 없습니다</p>
                  <p className="text-xs mt-1">검색 후 별표를 클릭하여 저장하세요</p>
                </div>
              ) : (
                <>
                  <div className="p-2 border-b">
                    <span className="text-xs text-muted-foreground px-2">즐겨찾기 {favoriteSearches.length}개</span>
                  </div>
                  {favoriteSearches.map((favorite) => (
                    <button
                      key={favorite.id}
                      onClick={() => handleSelectFavorite(favorite)}
                      className="w-full px-4 py-3 text-left hover:bg-muted transition-colors flex items-center gap-3 group"
                    >
                      <Star className="h-4 w-4 text-yellow-500 flex-shrink-0" />
                      <div className="flex-1 min-w-0">
                        <p className="text-sm font-medium">{favorite.name}</p>
                        <p className="text-xs text-muted-foreground truncate">{favorite.naturalQuery}</p>
                      </div>
                      <button
                        onClick={(e) => handleRemoveFavorite(favorite.id, e)}
                        className="p-1 opacity-0 group-hover:opacity-100 hover:bg-red-100 dark:hover:bg-red-900/30 rounded transition-all"
                      >
                        <Trash2 className="h-3.5 w-3.5 text-red-500" />
                      </button>
                    </button>
                  ))}
                </>
              )}
            </div>
          )}

          {/* 추천 검색어 (입력이 없을 때) */}
          {!input.trim() && (
            <div className="p-3 border-t bg-muted/30">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-xs text-muted-foreground">추천:</span>
                {suggestions.map((s, idx) => (
                  <button
                    key={idx}
                    onClick={() => setInput(s.query)}
                    className="text-xs px-2.5 py-1 bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300 rounded-full hover:bg-purple-200 dark:hover:bg-purple-900/50 transition-colors"
                  >
                    {s.label}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* 즐겨찾기 저장 모달 */}
      {saveFavoriteModalOpen && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/50">
          <div className="bg-background rounded-xl shadow-2xl p-6 w-full max-w-md mx-4">
            <h3 className="text-lg font-semibold mb-4">즐겨찾기에 저장</h3>
            <div className="space-y-4">
              <div>
                <label className="text-sm text-muted-foreground">이름</label>
                <input
                  type="text"
                  value={favoriteName}
                  onChange={(e) => setFavoriteName(e.target.value)}
                  placeholder="예: 내 진행중인 버그"
                  className="w-full mt-1 px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-purple-500/50"
                  autoFocus
                />
              </div>
              <div>
                <label className="text-sm text-muted-foreground">검색어</label>
                <p className="mt-1 text-sm">{input}</p>
              </div>
              <div>
                <label className="text-sm text-muted-foreground">JQL</label>
                <pre className="mt-1 p-2 text-xs font-mono bg-slate-100 dark:bg-slate-800 rounded overflow-x-auto">
                  {getCurrentJql()}
                </pre>
              </div>
            </div>
            <div className="flex gap-2 mt-6">
              <button
                onClick={() => {
                  setSaveFavoriteModalOpen(false)
                  setFavoriteName('')
                }}
                className="flex-1 py-2 border rounded-lg text-sm hover:bg-muted transition-colors"
              >
                취소
              </button>
              <button
                onClick={handleSaveFavorite}
                disabled={!favoriteName.trim()}
                className="flex-1 py-2 bg-purple-600 hover:bg-purple-700 disabled:bg-gray-300 disabled:cursor-not-allowed text-white rounded-lg text-sm transition-colors"
              >
                저장
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
