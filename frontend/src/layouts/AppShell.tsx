import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { systemApi } from '../api/system'
import { StatusDot } from '../components/Ui'

const nav = [
  { to: '/', label: 'Overview', end: true },
  { to: '/policies', label: 'All Policies' },
  { to: '/policies/new', label: 'Create Policy' },
  { to: '/instances', label: 'Instances' },
  { to: '/analytics', label: 'Analytics' },
  { to: '/playground', label: 'Playground' },
  { to: '/system', label: 'System' },
]

const titles: Record<string, string> = {
  '/': 'Overview',
  '/policies': 'Policies',
  '/policies/new': 'Create Policy',
  '/instances': 'Instances',
  '/analytics': 'Analytics',
  '/playground': 'Playground',
  '/system': 'System',
}

export function AppShell() {
  const location = useLocation()
  const health = useQuery({
    queryKey: ['system-health'],
    queryFn: systemApi.health,
    refetchInterval: 8000,
  })
  const info = useQuery({ queryKey: ['system-info'], queryFn: systemApi.info })
  const title =
    titles[location.pathname] ??
    (location.pathname.startsWith('/policies/') ? 'Policy' : 'Throttl')

  return (
    <div className="grid min-h-screen grid-cols-[240px_1fr]">
      <aside className="border-r border-line bg-panel px-4 py-5">
        <div className="mb-8 px-2">
          <div className="font-mono text-xs tracking-[0.25em] text-accent">THROTTL</div>
          <div className="mt-1 text-sm text-mute">Rate limiter console</div>
        </div>
        <nav className="flex flex-col gap-1">
          <NavLink
            to="/"
            end
            className={({ isActive }) =>
              `rounded-md px-3 py-2 text-sm ${isActive ? 'bg-raised text-text' : 'text-mute hover:text-text'}`
            }
          >
            Overview
          </NavLink>
          <div className="px-3 pt-4 pb-1 text-[11px] uppercase tracking-wider text-mute">Policies</div>
          {nav.slice(1, 3).map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `rounded-md px-3 py-2 text-sm ${isActive ? 'bg-raised text-text' : 'text-mute hover:text-text'}`
              }
            >
              {item.label}
            </NavLink>
          ))}
          <div className="px-3 pt-4 pb-1 text-[11px] uppercase tracking-wider text-mute">Operate</div>
          {nav.slice(3).map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `rounded-md px-3 py-2 text-sm ${isActive ? 'bg-raised text-text' : 'text-mute hover:text-text'}`
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="mt-10 px-3 text-xs text-mute">
          <StatusDot status={health.data?.status ?? 'UNKNOWN'} />
        </div>
      </aside>
      <div className="flex min-w-0 flex-col">
        <header className="flex items-center justify-between border-b border-line px-8 py-4">
          <div>
            <h1 className="text-xl font-semibold">{title}</h1>
            <p className="text-xs text-mute">
              {info.data?.environment ?? 'LOCAL'} · {info.data?.instanceId ?? 'cluster'}
            </p>
          </div>
          <StatusDot status={health.data?.status ?? 'UNKNOWN'} />
        </header>
        <main className="min-w-0 flex-1 px-8 py-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
