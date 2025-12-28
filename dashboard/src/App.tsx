import { Suspense, lazy } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { Layout } from '@/components/Layout'

// Lazy load pages for code splitting
const Dashboard = lazy(() => import('@/pages/Dashboard').then(m => ({ default: m.Dashboard })))
const Chat = lazy(() => import('@/pages/Chat').then(m => ({ default: m.Chat })))
const Interactions = lazy(() => import('@/pages/Interactions').then(m => ({ default: m.Interactions })))
const Workflows = lazy(() => import('@/pages/Workflows').then(m => ({ default: m.Workflows })))
const Jira = lazy(() => import('@/pages/Jira').then(m => ({ default: m.Jira })))
const Knowledge = lazy(() => import('@/pages/Knowledge').then(m => ({ default: m.Knowledge })))
const Logs = lazy(() => import('@/pages/Logs'))
const Settings = lazy(() => import('@/pages/Settings'))

// Loading fallback component
function PageLoader() {
  return (
    <div className="flex items-center justify-center h-64">
      <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
    </div>
  )
}

function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={
          <Suspense fallback={<PageLoader />}>
            <Dashboard />
          </Suspense>
        } />
        <Route path="/chat" element={
          <Suspense fallback={<PageLoader />}>
            <Chat />
          </Suspense>
        } />
        <Route path="/interactions" element={
          <Suspense fallback={<PageLoader />}>
            <Interactions />
          </Suspense>
        } />
        {/* Redirects for old routes */}
        <Route path="/history" element={<Navigate to="/interactions" replace />} />
        <Route path="/gitlab-reviews" element={<Navigate to="/interactions" replace />} />
        <Route path="/workflows" element={
          <Suspense fallback={<PageLoader />}>
            <Workflows />
          </Suspense>
        } />
        <Route path="/projects" element={<Navigate to="/settings" replace />} />
        <Route path="/jira" element={
          <Suspense fallback={<PageLoader />}>
            <Jira />
          </Suspense>
        } />
        <Route path="/knowledge" element={
          <Suspense fallback={<PageLoader />}>
            <Knowledge />
          </Suspense>
        } />
        {/* Admin Feedback merged into Interactions with admin mode toggle */}
        <Route path="/admin-feedback" element={<Navigate to="/interactions" replace />} />
        <Route path="/logs" element={
          <Suspense fallback={<PageLoader />}>
            <Logs />
          </Suspense>
        } />
        <Route path="/settings" element={
          <Suspense fallback={<PageLoader />}>
            <Settings />
          </Suspense>
        } />
      </Route>
    </Routes>
  )
}

export default App
