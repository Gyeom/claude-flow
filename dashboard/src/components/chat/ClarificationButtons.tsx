import type { ClarificationRequest, ClarificationOption } from '../../types'

interface ClarificationButtonsProps {
  clarification: ClarificationRequest
  onSelect: (option: ClarificationOption) => void
  disabled?: boolean
}

/**
 * Clarification 버튼 UI
 * 프로젝트 선택 등 사용자 확인이 필요한 경우 버튼을 렌더링
 */
export function ClarificationButtons({ clarification, onSelect, disabled }: ClarificationButtonsProps) {
  // 타입에 따른 아이콘 선택
  const getTypeIcon = () => {
    switch (clarification.type) {
      case 'project_selection':
        return '📂'
      case 'confirmation':
        return '❓'
      default:
        return '🔘'
    }
  }

  return (
    <div className="mt-3 space-y-2">
      {/* 옵션 버튼들 */}
      <div className="flex flex-wrap gap-2">
        {clarification.options.map((option, index) => (
          <button
            key={option.id}
            onClick={() => onSelect(option)}
            disabled={disabled}
            className={`
              group relative flex items-center gap-2 px-4 py-2.5
              bg-gradient-to-r from-blue-500/10 to-purple-500/10
              hover:from-blue-500/20 hover:to-purple-500/20
              border border-blue-500/30 hover:border-blue-500/50
              rounded-lg transition-all duration-200
              text-sm font-medium text-foreground
              disabled:opacity-50 disabled:cursor-not-allowed
              hover:shadow-md hover:shadow-blue-500/10
              active:scale-[0.98]
            `}
          >
            {/* 번호 뱃지 */}
            <span className="flex items-center justify-center w-5 h-5 rounded-full bg-blue-500/20 text-xs font-bold text-blue-400">
              {index + 1}
            </span>

            {/* 아이콘 (있는 경우) */}
            {option.icon && (
              <span className="text-base">{option.icon}</span>
            )}

            {/* 라벨 */}
            <span>{option.label}</span>

            {/* 설명 툴팁 (있는 경우) */}
            {option.description && (
              <span className="hidden group-hover:block absolute bottom-full left-1/2 -translate-x-1/2 mb-2 px-2 py-1 text-xs bg-popover border border-border rounded shadow-lg whitespace-nowrap z-10">
                {option.description}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* 안내 메시지 */}
      <p className="text-xs text-muted-foreground flex items-center gap-1">
        <span>{getTypeIcon()}</span>
        <span>위 버튼 중 하나를 클릭하세요</span>
      </p>
    </div>
  )
}
