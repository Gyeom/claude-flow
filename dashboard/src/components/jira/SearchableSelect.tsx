import { useState, useRef, useEffect, useCallback } from 'react'
import { Search, X, ChevronDown, Loader2, Check } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface SearchableOption {
  value: string
  label: string
  sublabel?: string
  icon?: React.ReactNode
}

interface SearchableSelectProps {
  value: string
  onChange: (value: string) => void
  options: SearchableOption[]
  placeholder?: string
  searchPlaceholder?: string
  loading?: boolean
  disabled?: boolean
  className?: string
  icon?: React.ReactNode
  allowCustomValue?: boolean
  onSearch?: (query: string) => void
  emptyMessage?: string
}

export function SearchableSelect({
  value,
  onChange,
  options,
  placeholder = '선택하세요',
  searchPlaceholder = '검색...',
  loading = false,
  disabled = false,
  className,
  icon,
  allowCustomValue = false,
  onSearch,
  emptyMessage = '결과가 없습니다',
}: SearchableSelectProps) {
  const [isOpen, setIsOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  // 현재 선택된 옵션 찾기
  const selectedOption = options.find(opt => opt.value === value)

  // 필터링된 옵션
  const filteredOptions = options.filter(opt => {
    const query = searchQuery.toLowerCase()
    return (
      opt.value.toLowerCase().includes(query) ||
      opt.label.toLowerCase().includes(query) ||
      opt.sublabel?.toLowerCase().includes(query)
    )
  })

  // 외부 클릭 감지
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpen(false)
        setSearchQuery('')
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  // 열릴 때 입력 필드에 포커스
  useEffect(() => {
    if (isOpen && inputRef.current) {
      inputRef.current.focus()
    }
  }, [isOpen])

  // 검색어 변경 시 외부 검색 콜백 호출
  useEffect(() => {
    if (onSearch && searchQuery) {
      const debounce = setTimeout(() => {
        onSearch(searchQuery)
      }, 300)
      return () => clearTimeout(debounce)
    }
  }, [searchQuery, onSearch])

  // 옵션 선택
  const handleSelect = useCallback((optionValue: string) => {
    onChange(optionValue)
    setIsOpen(false)
    setSearchQuery('')
  }, [onChange])

  // 키보드 핸들링
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && allowCustomValue && searchQuery && filteredOptions.length === 0) {
      e.preventDefault()
      onChange(searchQuery)
      setIsOpen(false)
      setSearchQuery('')
    } else if (e.key === 'Escape') {
      setIsOpen(false)
      setSearchQuery('')
    }
  }

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      {/* 선택 버튼 */}
      <button
        type="button"
        onClick={() => !disabled && setIsOpen(!isOpen)}
        disabled={disabled}
        className={cn(
          "w-full px-3 py-2 border rounded-lg flex items-center justify-between bg-background transition-colors",
          "hover:bg-muted/50 focus:ring-2 focus:ring-purple-500/50 focus:border-purple-500",
          disabled && "opacity-50 cursor-not-allowed",
          isOpen && "ring-2 ring-purple-500/50 border-purple-500"
        )}
      >
        <span className="flex items-center gap-2 text-left truncate">
          {icon && <span className="text-muted-foreground flex-shrink-0">{icon}</span>}
          {selectedOption ? (
            <span className="flex items-center gap-2">
              {selectedOption.icon}
              <span>{selectedOption.label}</span>
              {selectedOption.sublabel && (
                <span className="text-xs text-muted-foreground">({selectedOption.sublabel})</span>
              )}
            </span>
          ) : value ? (
            <span>{value}</span>
          ) : (
            <span className="text-muted-foreground">{placeholder}</span>
          )}
        </span>
        <ChevronDown className={cn(
          "h-4 w-4 text-muted-foreground transition-transform flex-shrink-0",
          isOpen && "rotate-180"
        )} />
      </button>

      {/* 드롭다운 */}
      {isOpen && (
        <div className="absolute top-full left-0 right-0 mt-1 bg-background border rounded-lg shadow-lg z-50 overflow-hidden">
          {/* 검색 입력 */}
          <div className="p-2 border-b">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <input
                ref={inputRef}
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={searchPlaceholder}
                className="w-full pl-9 pr-8 py-2 text-sm border rounded-md bg-background focus:ring-2 focus:ring-purple-500/50 focus:border-purple-500"
              />
              {searchQuery && (
                <button
                  type="button"
                  onClick={() => setSearchQuery('')}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  <X className="h-4 w-4" />
                </button>
              )}
            </div>
          </div>

          {/* 옵션 목록 */}
          <div className="max-h-60 overflow-y-auto relative">
            {/* 로딩 오버레이 - 기존 콘텐츠 위에 표시 */}
            {loading && filteredOptions.length > 0 && (
              <div className="absolute inset-0 bg-background/60 flex items-center justify-center z-10">
                <div className="flex items-center gap-2 text-muted-foreground">
                  <Loader2 className="h-4 w-4 animate-spin" />
                  <span className="text-sm">검색 중...</span>
                </div>
              </div>
            )}
            {/* 로딩 중이고 옵션이 없을 때만 전체 로딩 표시 */}
            {loading && filteredOptions.length === 0 ? (
              <div className="flex items-center justify-center py-6 text-muted-foreground">
                <Loader2 className="h-5 w-5 animate-spin mr-2" />
                <span className="text-sm">검색 중...</span>
              </div>
            ) : filteredOptions.length > 0 ? (
              filteredOptions.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  onClick={() => handleSelect(option.value)}
                  className={cn(
                    "w-full px-3 py-2 flex items-center gap-2 text-left hover:bg-muted",
                    value === option.value && "bg-purple-50 dark:bg-purple-900/20"
                  )}
                >
                  {option.icon && (
                    <span className="flex-shrink-0">{option.icon}</span>
                  )}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-medium truncate">{option.label}</span>
                      {value === option.value && (
                        <Check className="h-4 w-4 text-purple-600 flex-shrink-0" />
                      )}
                    </div>
                    {option.sublabel && (
                      <span className="text-xs text-muted-foreground truncate block">{option.sublabel}</span>
                    )}
                  </div>
                </button>
              ))
            ) : !loading ? (
              <div className="py-6 text-center text-muted-foreground text-sm">
                {allowCustomValue && searchQuery ? (
                  <span>Enter를 눌러 "{searchQuery}" 사용</span>
                ) : (
                  emptyMessage
                )}
              </div>
            ) : null}
          </div>

          {/* 선택된 값 지우기 */}
          {value && (
            <div className="p-2 border-t">
              <button
                type="button"
                onClick={() => handleSelect('')}
                className="w-full px-3 py-1.5 text-sm text-muted-foreground hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-md transition-colors"
              >
                선택 해제
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// 사용자 검색 select (비동기 검색 지원)
interface AsyncSearchableSelectProps extends Omit<SearchableSelectProps, 'options'> {
  fetchOptions: (query: string) => Promise<SearchableOption[]>
  initialOptions?: SearchableOption[]
}

export function AsyncSearchableSelect({
  fetchOptions,
  initialOptions = [],
  ...props
}: AsyncSearchableSelectProps) {
  const [options, setOptions] = useState<SearchableOption[]>(initialOptions)
  const [loading, setLoading] = useState(false)
  const abortControllerRef = useRef<AbortController | null>(null)
  const lastQueryRef = useRef<string>('')

  const handleSearch = useCallback(async (query: string) => {
    // 이전 요청 취소
    if (abortControllerRef.current) {
      abortControllerRef.current.abort()
    }

    if (!query || query.length < 2) {
      // 쿼리가 짧으면 초기 옵션으로 (로딩 표시 없이)
      if (lastQueryRef.current !== '') {
        setOptions(initialOptions)
        lastQueryRef.current = ''
      }
      return
    }

    // 같은 쿼리면 스킵
    if (query === lastQueryRef.current) {
      return
    }

    lastQueryRef.current = query
    abortControllerRef.current = new AbortController()

    // 로딩 상태 설정 (이전 옵션은 유지)
    setLoading(true)

    try {
      const results = await fetchOptions(query)
      // 최신 쿼리 결과인지 확인
      if (query === lastQueryRef.current) {
        setOptions(results)
      }
    } catch (error) {
      // AbortError가 아닌 경우에만 에러 처리
      if (error instanceof Error && error.name !== 'AbortError') {
        console.error('Search failed:', error)
        if (query === lastQueryRef.current) {
          setOptions([])
        }
      }
    } finally {
      if (query === lastQueryRef.current) {
        setLoading(false)
      }
    }
  }, [fetchOptions, initialOptions])

  // 컴포넌트 언마운트 시 정리
  useEffect(() => {
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort()
      }
    }
  }, [])

  return (
    <SearchableSelect
      {...props}
      options={options}
      loading={loading}
      onSearch={handleSearch}
    />
  )
}
