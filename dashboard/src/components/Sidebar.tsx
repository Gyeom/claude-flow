import { NavLink } from 'react-router-dom'
import {
  LayoutDashboard,
  Settings,
  Zap,
  Workflow,
  ScrollText,
  MessageSquare,
  Ticket,
  BookOpen,
  Activity,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { ThemeToggle } from './ThemeToggle'

const navigation = [
  { name: 'Dashboard', href: '/', icon: LayoutDashboard },
  { name: 'Jira', href: '/jira', icon: Ticket },
  { name: 'Chat', href: '/chat', icon: MessageSquare },
  { name: 'Activity', href: '/interactions', icon: Activity },
  { name: 'Live Logs', href: '/logs', icon: ScrollText },
  { name: 'Knowledge', href: '/knowledge', icon: BookOpen },
  { name: 'Workflows', href: '/workflows', icon: Workflow },
]

export function Sidebar() {
  return (
    <aside className="fixed left-0 top-0 z-40 h-screen w-64 border-r border-border bg-card">
      <div className="flex h-full flex-col">
        {/* Logo */}
        <div className="flex h-20 items-center gap-4 border-b border-border px-6">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <Zap className="h-6 w-6" />
          </div>
          <div>
            <h1 className="text-xl font-bold">Claude Flow</h1>
            <p className="text-sm text-muted-foreground">AI Agent Platform</p>
          </div>
        </div>

        {/* Navigation */}
        <nav className="flex-1 space-y-2 p-4">
          {navigation.map((item) => (
            <NavLink
              key={item.name}
              to={item.href}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-4 rounded-xl px-4 py-3.5 text-base font-medium transition-colors',
                  isActive
                    ? 'bg-primary/10 text-primary'
                    : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                )
              }
            >
              <item.icon className="h-6 w-6" />
              {item.name}
            </NavLink>
          ))}
        </nav>

        {/* Footer */}
        <div className="border-t border-border p-4 space-y-3">
          <ThemeToggle />
          <NavLink
            to="/settings"
            className={({ isActive }) =>
              cn(
                'flex items-center gap-4 px-4 py-3 rounded-xl transition-colors',
                isActive
                  ? 'bg-primary/10 text-primary'
                  : 'text-muted-foreground hover:bg-muted hover:text-foreground'
              )
            }
          >
            <Settings className="h-6 w-6" />
            <span className="text-base font-medium">Settings</span>
          </NavLink>
          <div className="px-4 py-2">
            <p className="text-xs text-muted-foreground">
              Version 1.0.0
            </p>
          </div>
        </div>
      </div>
    </aside>
  )
}
