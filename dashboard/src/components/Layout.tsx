import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { FloatingChat } from './chat'

export function Layout() {
  const location = useLocation()

  // /chat 페이지에서는 플로팅 버튼 숨김 (전체 화면 Chat 사용)
  const showFloatingChat = location.pathname !== '/chat'

  return (
    <div className="min-h-screen bg-background">
      <Sidebar />
      <main className="pl-64">
        <div className="p-8">
          <Outlet />
        </div>
      </main>
      {showFloatingChat && <FloatingChat />}
    </div>
  )
}
