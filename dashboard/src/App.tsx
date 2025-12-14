import { Routes, Route } from 'react-router-dom'
import { Layout } from '@/components/Layout'
import { Dashboard } from '@/pages/Dashboard'
import { Executions } from '@/pages/Executions'
import { Agents } from '@/pages/Agents'
import { Analytics } from '@/pages/Analytics'

function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/executions" element={<Executions />} />
        <Route path="/agents" element={<Agents />} />
        <Route path="/analytics" element={<Analytics />} />
      </Route>
    </Routes>
  )
}

export default App
