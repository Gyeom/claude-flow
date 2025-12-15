import { Fragment } from 'react'
import { cn } from '@/lib/utils'
import type { ReactNode } from 'react'

interface Column<T> {
  key: keyof T | string
  header: string
  render?: (item: T) => ReactNode
  className?: string
}

interface DataTableProps<T> {
  data: T[]
  columns: Column<T>[]
  onRowClick?: (item: T) => void
  emptyMessage?: string
  className?: string
  expandedId?: string | null
  renderExpanded?: (item: T) => ReactNode
}

export function DataTable<T extends { id?: string }>({
  data,
  columns,
  onRowClick,
  emptyMessage = 'No data available',
  className,
  expandedId,
  renderExpanded,
}: DataTableProps<T>) {
  if (data.length === 0) {
    return (
      <div className="flex items-center justify-center py-12 text-muted-foreground">
        {emptyMessage}
      </div>
    )
  }

  return (
    <div className={cn('overflow-x-auto', className)}>
      <table className="w-full">
        <thead>
          <tr className="border-b border-border">
            {columns.map((column) => (
              <th
                key={column.key as string}
                className={cn(
                  'px-4 py-3 text-left text-sm font-medium text-muted-foreground',
                  column.className
                )}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map((item, index) => {
            const isExpanded = expandedId === item.id
            const rowKey = item.id || String(index)
            return (
              <Fragment key={rowKey}>
                <tr
                  onClick={() => onRowClick?.(item)}
                  className={cn(
                    'border-b border-border transition-colors',
                    onRowClick && 'cursor-pointer hover:bg-muted/50',
                    isExpanded && 'bg-muted/30'
                  )}
                >
                  {columns.map((column) => (
                    <td
                      key={column.key as string}
                      className={cn('px-4 py-3 text-sm', column.className)}
                    >
                      {column.render
                        ? column.render(item)
                        : String(item[column.key as keyof T] ?? '-')}
                    </td>
                  ))}
                </tr>
                {isExpanded && renderExpanded && (
                  <tr>
                    <td colSpan={columns.length} className="p-0">
                      <div className="border-b border-border p-6 bg-muted/30">
                        {renderExpanded(item)}
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

// Status Badge Component
export function StatusBadge({ status }: { status: string }) {
  const statusStyles: Record<string, string> = {
    success: 'bg-green-500/10 text-green-500 border-green-500/20',
    error: 'bg-red-500/10 text-red-500 border-red-500/20',
    failed: 'bg-red-500/10 text-red-500 border-red-500/20',
    running: 'bg-yellow-500/10 text-yellow-500 border-yellow-500/20',
    pending: 'bg-blue-500/10 text-blue-500 border-blue-500/20',
  }

  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-1 rounded-full text-xs font-medium border',
        statusStyles[status.toLowerCase()] || 'bg-muted text-muted-foreground border-border'
      )}
    >
      {status}
    </span>
  )
}

// Priority Badge Component
export function PriorityBadge({ priority }: { priority: number }) {
  let color = 'bg-muted text-muted-foreground'
  let label = 'Low'

  if (priority >= 200) {
    color = 'bg-red-500/10 text-red-500'
    label = 'High'
  } else if (priority >= 100) {
    color = 'bg-yellow-500/10 text-yellow-500'
    label = 'Medium'
  }

  return (
    <span className={cn('inline-flex items-center px-2 py-1 rounded-full text-xs font-medium', color)}>
      {label} ({priority})
    </span>
  )
}

// Keyword Badge
export function KeywordBadge({ keyword }: { keyword: string }) {
  return (
    <span className="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium bg-primary/10 text-primary mr-1 mb-1">
      {keyword}
    </span>
  )
}
