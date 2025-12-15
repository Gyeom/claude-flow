import { Routes, Route } from 'react-router-dom'
import { Layout } from '@/components/Layout'
import { Dashboard } from '@/pages/Dashboard'
import { Executions } from '@/pages/Executions'
import { Agents } from '@/pages/Agents'
import { Analytics } from '@/pages/Analytics'
import { Feedback } from '@/pages/Feedback'
import { Models } from '@/pages/Models'
import { Errors } from '@/pages/Errors'
import { Classify } from '@/pages/Classify'
import { History } from '@/pages/History'
import { Plugins } from '@/pages/Plugins'
import { Users } from '@/pages/Users'
import { Workflows } from '@/pages/Workflows'

function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/executions" element={<Executions />} />
        <Route path="/agents" element={<Agents />} />
        <Route path="/analytics" element={<Analytics />} />
        <Route path="/feedback" element={<Feedback />} />
        <Route path="/models" element={<Models />} />
        <Route path="/errors" element={<Errors />} />
        <Route path="/classify" element={<Classify />} />
        <Route path="/history" element={<History />} />
        <Route path="/plugins" element={<Plugins />} />
        <Route path="/users" element={<Users />} />
        <Route path="/workflows" element={<Workflows />} />
      </Route>
    </Routes>
  )
}

export default App
