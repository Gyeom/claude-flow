import { FormEvent, KeyboardEvent, useRef, useEffect } from 'react'
import { Send, Loader2, Paperclip, Mic } from 'lucide-react'
import { cn } from '@/lib/utils'

interface ChatInputProps {
  input: string
  onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => void
  onSubmit: (e: FormEvent<HTMLFormElement>) => void
  isLoading: boolean
  disabled?: boolean
}

export function ChatInput({ input, onChange, onSubmit, isLoading, disabled }: ChatInputProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // 입력 내용에 따라 높이 자동 조절
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 200)}px`
    }
  }, [input])

  // 포커스 유지
  useEffect(() => {
    if (!isLoading && textareaRef.current) {
      textareaRef.current.focus()
    }
  }, [isLoading])

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    // Enter로 전송, Shift+Enter로 줄바꿈
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      if (input.trim() && !isLoading && !disabled) {
        onSubmit(e as unknown as FormEvent<HTMLFormElement>)
      }
    }
  }

  const charCount = input.length
  const maxChars = 10000
  const isNearLimit = charCount > maxChars * 0.8

  return (
    <div className="border-t border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <form onSubmit={onSubmit} className="p-4">
        <div className="relative">
          {/* 메인 입력 영역 */}
          <div className={cn(
            'flex items-end gap-2 rounded-2xl border bg-muted/30 transition-all duration-200',
            'focus-within:border-primary/50 focus-within:ring-2 focus-within:ring-primary/20',
            disabled && 'opacity-50'
          )}>
            {/* 첨부 버튼 (비활성) */}
            <button
              type="button"
              disabled
              className="p-3 text-muted-foreground/50 cursor-not-allowed"
              title="파일 첨부 (준비 중)"
            >
              <Paperclip className="h-5 w-5" />
            </button>

            {/* 텍스트 입력 */}
            <textarea
              ref={textareaRef}
              value={input}
              onChange={onChange}
              onKeyDown={handleKeyDown}
              placeholder="메시지를 입력하세요..."
              disabled={isLoading || disabled}
              className={cn(
                'flex-1 resize-none bg-transparent py-3 pr-2',
                'focus:outline-none',
                'placeholder:text-muted-foreground/60',
                'disabled:cursor-not-allowed',
                'min-h-[24px] max-h-[200px]',
                'text-base leading-relaxed'
              )}
              rows={1}
            />

            {/* 음성 버튼 (비활성) */}
            <button
              type="button"
              disabled
              className="p-3 text-muted-foreground/50 cursor-not-allowed"
              title="음성 입력 (준비 중)"
            >
              <Mic className="h-5 w-5" />
            </button>

            {/* 전송 버튼 */}
            <button
              type="submit"
              disabled={!input.trim() || isLoading || disabled}
              className={cn(
                'flex items-center justify-center rounded-xl m-1.5 p-2.5',
                'transition-all duration-200',
                input.trim() && !isLoading && !disabled
                  ? 'bg-primary text-primary-foreground hover:bg-primary/90 shadow-md hover:shadow-lg'
                  : 'bg-muted text-muted-foreground cursor-not-allowed'
              )}
            >
              {isLoading ? (
                <Loader2 className="h-5 w-5 animate-spin" />
              ) : (
                <Send className="h-5 w-5" />
              )}
            </button>
          </div>

          {/* 하단 정보 */}
          <div className="flex items-center justify-between mt-2 px-2">
            <p className="text-xs text-muted-foreground">
              <kbd className="px-1.5 py-0.5 rounded bg-muted text-[10px] font-mono">Enter</kbd>
              {' '}전송 {'·'}
              <kbd className="px-1.5 py-0.5 rounded bg-muted text-[10px] font-mono">Shift + Enter</kbd>
              {' '}줄바꿈
            </p>
            <span className={cn(
              'text-xs transition-colors',
              isNearLimit ? 'text-yellow-500' : 'text-muted-foreground/50'
            )}>
              {charCount > 0 && `${charCount.toLocaleString()} / ${maxChars.toLocaleString()}`}
            </span>
          </div>
        </div>
      </form>
    </div>
  )
}
