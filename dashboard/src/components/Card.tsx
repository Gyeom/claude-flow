import { cn } from '@/lib/utils'
import type { ReactNode, MouseEventHandler } from 'react'

interface CardProps {
  children: ReactNode
  className?: string
  onClick?: MouseEventHandler<HTMLDivElement>
}

export function Card({ children, className, onClick }: CardProps) {
  return (
    <div
      className={cn(
        'rounded-xl border border-border bg-card p-6 shadow-sm',
        className
      )}
      onClick={onClick}
    >
      {children}
    </div>
  )
}

interface CardHeaderProps {
  title: string
  description?: string
  action?: ReactNode
}

export function CardHeader({ title, description, action }: CardHeaderProps) {
  return (
    <div className="flex items-start justify-between mb-4">
      <div>
        <h3 className="text-lg font-semibold text-card-foreground">{title}</h3>
        {description && (
          <p className="text-sm text-muted-foreground mt-1">{description}</p>
        )}
      </div>
      {action}
    </div>
  )
}

interface StatCardProps {
  title: string
  value: string | number
  subtitle?: string
  change?: {
    value: number
    label: string
  }
  icon?: ReactNode
  trend?: 'up' | 'down' | 'neutral'
  className?: string
}

export function StatCard({ title, value, subtitle, change, icon, trend, className }: StatCardProps) {
  return (
    <Card className={cn('relative overflow-hidden', className)}>
      <div className="flex items-start justify-between">
        <div className="space-y-2">
          <p className="text-sm font-medium text-muted-foreground">{title}</p>
          <p className="text-3xl font-bold tracking-tight">{value}</p>
          {subtitle && (
            <p className="text-xs text-muted-foreground truncate max-w-[180px]">{subtitle}</p>
          )}
          {change && (
            <p
              className={cn(
                'text-sm font-medium flex items-center gap-1',
                trend === 'up' && 'text-green-500',
                trend === 'down' && 'text-red-500',
                trend === 'neutral' && 'text-muted-foreground'
              )}
            >
              {trend === 'up' && '↑'}
              {trend === 'down' && '↓'}
              {change.value > 0 ? '+' : ''}{change.value}% {change.label}
            </p>
          )}
        </div>
        {icon && (
          <div className="p-3 rounded-lg bg-primary/10 text-primary">
            {icon}
          </div>
        )}
      </div>
    </Card>
  )
}
