import { Suspense, lazy } from 'react'
import { Routes, Route } from 'react-router-dom'
import { Layout } from '@/components/Layout'

// Lazy load pages for code splitting
const Dashboard = lazy(() => import('@/pages/Dashboard').then(m => ({ default: m.Dashboard })))
const Executions = lazy(() => import('@/pages/Executions').then(m => ({ default: m.Executions })))
const Agents = lazy(() => import('@/pages/Agents').then(m => ({ default: m.Agents })))
const Analytics = lazy(() => import('@/pages/Analytics').then(m => ({ default: m.Analytics })))
const Feedback = lazy(() => import('@/pages/Feedback').then(m => ({ default: m.Feedback })))
const Models = lazy(() => import('@/pages/Models').then(m => ({ default: m.Models })))
const Errors = lazy(() => import('@/pages/Errors').then(m => ({ default: m.Errors })))
const Classify = lazy(() => import('@/pages/Classify').then(m => ({ default: m.Classify })))
const History = lazy(() => import('@/pages/History').then(m => ({ default: m.History })))
const Plugins = lazy(() => import('@/pages/Plugins').then(m => ({ default: m.Plugins })))
const Users = lazy(() => import('@/pages/Users').then(m => ({ default: m.Users })))
const Workflows = lazy(() => import('@/pages/Workflows').then(m => ({ default: m.Workflows })))
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
        <Route path="/executions" element={
          <Suspense fallback={<PageLoader />}>
            <Executions />
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
        <Route path="/classify" element={
          <Suspense fallback={<PageLoader />}>
            <Classify />
          </Suspense>
        } />
        <Route path="/history" element={
          <Suspense fallback={<PageLoader />}>
            <History />
          </Suspense>
        } />
        <Route path="/plugins" element={
          <Suspense fallback={<PageLoader />}>
            <Plugins />
          </Suspense>
        } />
        <Route path="/users" element={
          <Suspense fallback={<PageLoader />}>
            <Users />
          </Suspense>
        } />
        <Route path="/workflows" element={
          <Suspense fallback={<PageLoader />}>
            <Workflows />
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
