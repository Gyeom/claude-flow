import { memo, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter'
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism'
import { Copy, Check } from 'lucide-react'
import { cn } from '@/lib/utils'

interface MarkdownRendererProps {
  content: string
  className?: string
}

// 코드 블록 복사 버튼
function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    await navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <button
      onClick={handleCopy}
      className="absolute top-2 right-2 p-1.5 rounded-md bg-white/10 hover:bg-white/20 transition-colors opacity-0 group-hover:opacity-100"
      title="Copy code"
    >
      {copied ? (
        <Check className="h-4 w-4 text-green-400" />
      ) : (
        <Copy className="h-4 w-4 text-gray-400" />
      )}
    </button>
  )
}

// 언어 배지
function LanguageBadge({ language }: { language: string }) {
  return (
    <span className="absolute top-2 left-3 text-xs text-gray-400 font-mono uppercase">
      {language}
    </span>
  )
}

export const MarkdownRenderer = memo(function MarkdownRenderer({
  content,
  className
}: MarkdownRendererProps) {
  return (
    <ReactMarkdown
      className={cn('markdown-body', className)}
      remarkPlugins={[remarkGfm]}
      components={{
        // 코드 블록
        code({ className, children, ...props }) {
          const match = /language-(\w+)/.exec(className || '')
          const language = match ? match[1] : ''
          const codeString = String(children).replace(/\n$/, '')

          // 인라인 코드 vs 코드 블록 구분
          const isInline = !className && !codeString.includes('\n')

          if (isInline) {
            return (
              <code
                className="px-1.5 py-0.5 rounded bg-muted font-mono text-sm text-primary"
                {...props}
              >
                {children}
              </code>
            )
          }

          return (
            <div className="relative group my-4 rounded-lg overflow-hidden">
              {language && <LanguageBadge language={language} />}
              <CopyButton text={codeString} />
              <SyntaxHighlighter
                style={oneDark}
                language={language || 'text'}
                PreTag="div"
                customStyle={{
                  margin: 0,
                  padding: '2.5rem 1rem 1rem 1rem',
                  borderRadius: '0.5rem',
                  fontSize: '0.875rem',
                  lineHeight: '1.5',
                }}
              >
                {codeString}
              </SyntaxHighlighter>
            </div>
          )
        },

        // 헤딩
        h1: ({ children }) => (
          <h1 className="text-2xl font-bold mt-6 mb-4 pb-2 border-b border-border">
            {children}
          </h1>
        ),
        h2: ({ children }) => (
          <h2 className="text-xl font-semibold mt-5 mb-3 pb-1 border-b border-border/50">
            {children}
          </h2>
        ),
        h3: ({ children }) => (
          <h3 className="text-lg font-semibold mt-4 mb-2">{children}</h3>
        ),
        h4: ({ children }) => (
          <h4 className="text-base font-semibold mt-3 mb-2">{children}</h4>
        ),

        // 단락
        p: ({ children }) => (
          <p className="my-3 leading-relaxed">{children}</p>
        ),

        // 리스트
        ul: ({ children }) => (
          <ul className="my-3 ml-4 space-y-1.5 list-disc list-outside">
            {children}
          </ul>
        ),
        ol: ({ children }) => (
          <ol className="my-3 ml-4 space-y-1.5 list-decimal list-outside">
            {children}
          </ol>
        ),
        li: ({ children }) => (
          <li className="leading-relaxed pl-1">{children}</li>
        ),

        // 인용문
        blockquote: ({ children }) => (
          <blockquote className="my-4 pl-4 border-l-4 border-primary/50 text-muted-foreground italic">
            {children}
          </blockquote>
        ),

        // 링크
        a: ({ href, children }) => (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className="text-primary hover:underline"
          >
            {children}
          </a>
        ),

        // 구분선
        hr: () => <hr className="my-6 border-border" />,

        // 강조
        strong: ({ children }) => (
          <strong className="font-semibold text-foreground">{children}</strong>
        ),
        em: ({ children }) => (
          <em className="italic text-foreground/90">{children}</em>
        ),

        // 테이블
        table: ({ children }) => (
          <div className="my-4 overflow-x-auto rounded-xl border border-border/50 bg-muted/20">
            <table className="w-full text-sm border-collapse">{children}</table>
          </div>
        ),
        thead: ({ children }) => (
          <thead className="bg-muted/70 border-b border-border">{children}</thead>
        ),
        tbody: ({ children }) => (
          <tbody className="divide-y divide-border/30">{children}</tbody>
        ),
        tr: ({ children }) => (
          <tr className="hover:bg-muted/30 transition-colors">{children}</tr>
        ),
        th: ({ children }) => (
          <th className="px-4 py-3 text-left font-semibold text-foreground whitespace-nowrap">
            {children}
          </th>
        ),
        td: ({ children }) => (
          <td className="px-4 py-3 text-muted-foreground">{children}</td>
        ),

        // 이미지
        img: ({ src, alt }) => (
          <img
            src={src}
            alt={alt}
            className="my-4 rounded-lg max-w-full h-auto"
          />
        ),
      }}
    >
      {content}
    </ReactMarkdown>
  )
})
