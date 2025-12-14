import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatNumber(num: number): string {
  if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`
  if (num >= 1000) return `${(num / 1000).toFixed(1)}K`
  return num.toString()
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${(ms / 60000).toFixed(1)}m`
}

export function formatCost(cost: number): string {
  return `$${cost.toFixed(4)}`
}

export function formatPercent(value: number): string {
  return `${(value * 100).toFixed(1)}%`
}

export function getSuccessRateColor(rate: number): string {
  if (rate >= 0.95) return 'text-green-500'
  if (rate >= 0.8) return 'text-yellow-500'
  return 'text-red-500'
}

export function getSatisfactionColor(score: number): string {
  if (score >= 50) return 'text-green-500'
  if (score >= 0) return 'text-yellow-500'
  return 'text-red-500'
}

export function getStatusColor(status: string): string {
  switch (status.toLowerCase()) {
    case 'success':
      return 'bg-green-500/10 text-green-500 border-green-500/20'
    case 'error':
    case 'failed':
      return 'bg-red-500/10 text-red-500 border-red-500/20'
    case 'running':
    case 'pending':
      return 'bg-yellow-500/10 text-yellow-500 border-yellow-500/20'
    default:
      return 'bg-muted text-muted-foreground border-border'
  }
}
