import { NavLink } from 'react-router-dom'
import {
  LayoutDashboard,
  History,
  Bot,
  BarChart3,
  Settings,
  Zap,
  ThumbsUp,
  Cpu,
  AlertTriangle,
  Target,
  Clock,
  Puzzle,
  Users,
  Workflow,
  ScrollText,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { ThemeToggle } from './ThemeToggle'

const navigation = [
  { name: 'Dashboard', href: '/', icon: LayoutDashboard },
  { name: 'Executions', href: '/executions', icon: History },
  { name: 'Live Logs', href: '/logs', icon: ScrollText },
  { name: 'History', href: '/history', icon: Clock },
  { name: 'Agents', href: '/agents', icon: Bot },
  { name: 'Classify', href: '/classify', icon: Target },
  { name: 'Analytics', href: '/analytics', icon: BarChart3 },
  { name: 'Feedback', href: '/feedback', icon: ThumbsUp },
  { name: 'Models', href: '/models', icon: Cpu },
  { name: 'Errors', href: '/errors', icon: AlertTriangle },
  { name: 'Plugins', href: '/plugins', icon: Puzzle },
  { name: 'Users', href: '/users', icon: Users },
  { name: 'Workflows', href: '/workflows', icon: Workflow },
]

export function Sidebar() {
  return (
    <aside className="fixed left-0 top-0 z-40 h-screen w-64 border-r border-border bg-card">
      <div className="flex h-full flex-col">
        {/* Logo */}
        <div className="flex h-16 items-center gap-3 border-b border-border px-6">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <Zap className="h-5 w-5" />
          </div>
          <div>
            <h1 className="text-lg font-bold">Claude Flow</h1>
            <p className="text-xs text-muted-foreground">AI Agent Platform</p>
          </div>
        </div>

        {/* Navigation */}
        <nav className="flex-1 space-y-1 p-4">
          {navigation.map((item) => (
            <NavLink
              key={item.name}
              to={item.href}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-primary/10 text-primary'
                    : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                )
              }
            >
              <item.icon className="h-5 w-5" />
              {item.name}
            </NavLink>
          ))}
        </nav>

        {/* Footer */}
        <div className="border-t border-border p-4 space-y-4">
          <ThemeToggle />
          <div className="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-muted cursor-pointer transition-colors">
            <Settings className="h-5 w-5 text-muted-foreground" />
            <span className="text-sm font-medium text-muted-foreground">Settings</span>
          </div>
          <div className="px-3 py-2">
            <p className="text-xs text-muted-foreground">
              Version 1.0.0
            </p>
          </div>
        </div>
      </div>
    </aside>
  )
}
