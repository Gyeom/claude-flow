import { Suspense, lazy } from 'react'
import { Routes, Route } from 'react-router-dom'
import { Layout } from '@/components/Layout'

// Lazy load pages for code splitting
const Dashboard = lazy(() => import('@/pages/Dashboard').then(m => ({ default: m.Dashboard })))
const Chat = lazy(() => import('@/pages/Chat').then(m => ({ default: m.Chat })))
const Agents = lazy(() => import('@/pages/Agents').then(m => ({ default: m.Agents })))
const Analytics = lazy(() => import('@/pages/Analytics').then(m => ({ default: m.Analytics })))
const Feedback = lazy(() => import('@/pages/Feedback').then(m => ({ default: m.Feedback })))
const Models = lazy(() => import('@/pages/Models').then(m => ({ default: m.Models })))
const Errors = lazy(() => import('@/pages/Errors').then(m => ({ default: m.Errors })))
const History = lazy(() => import('@/pages/History').then(m => ({ default: m.History })))
const Workflows = lazy(() => import('@/pages/Workflows').then(m => ({ default: m.Workflows })))
const Projects = lazy(() => import('@/pages/Projects').then(m => ({ default: m.Projects })))
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
        <Route path="/agents" element={
          <Suspense fallback={<PageLoader />}>
            <Agents />
          </Suspense>
        } />
        <Route path="/analytics" element={
          <Suspense fallback={<PageLoader />}>
            <Analytics />
          </Suspense>
        } />
        <Route path="/feedback" element={
          <Suspense fallback={<PageLoader />}>
            <Feedback />
          </Suspense>
        } />
        <Route path="/models" element={
          <Suspense fallback={<PageLoader />}>
            <Models />
          </Suspense>
        } />
        <Route path="/errors" element={
          <Suspense fallback={<PageLoader />}>
            <Errors />
          </Suspense>
        } />
        <Route path="/history" element={
          <Suspense fallback={<PageLoader />}>
            <History />
          </Suspense>
        } />
        <Route path="/workflows" element={
          <Suspense fallback={<PageLoader />}>
            <Workflows />
          </Suspense>
        } />
        <Route path="/projects" element={
          <Suspense fallback={<PageLoader />}>
            <Projects />
          </Suspense>
        } />
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
