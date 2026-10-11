import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './layouts/AppShell'
import { AnalyticsPage } from './pages/AnalyticsPage'
import { InstanceDetailPage } from './pages/InstanceDetailPage'
import { InstancesPage } from './pages/InstancesPage'
import { OverviewPage } from './pages/OverviewPage'
import { PlaygroundPage } from './pages/PlaygroundPage'
import { PoliciesPage } from './pages/PoliciesPage'
import { PolicyDetailPage } from './pages/PolicyDetailPage'
import { PolicyFormPage } from './pages/PolicyFormPage'
import { SystemPage } from './pages/SystemPage'

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<OverviewPage />} />
        <Route path="/policies" element={<PoliciesPage />} />
        <Route path="/policies/new" element={<PolicyFormPage />} />
        <Route path="/policies/:id" element={<PolicyDetailPage />} />
        <Route path="/policies/:id/edit" element={<PolicyFormPage />} />
        <Route path="/instances" element={<InstancesPage />} />
        <Route path="/instances/:id" element={<InstanceDetailPage />} />
        <Route path="/analytics" element={<AnalyticsPage />} />
        <Route path="/playground" element={<PlaygroundPage />} />
        <Route path="/system" element={<SystemPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}
