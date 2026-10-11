import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { metricsApi } from '../api/metrics'
import { Sparkline } from '../components/Sparkline'
import { ErrorState, Skeleton, StatusDot } from '../components/Ui'
import { formatNumber } from '../utils/format'

function Kpi({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-lg border border-line bg-panel px-4 py-4">
      <div className="text-xs uppercase tracking-wider text-mute">{label}</div>
      <div className="mt-2 font-mono text-2xl">{value}</div>
      {hint ? <div className="mt-1 text-xs text-mute">{hint}</div> : null}
    </div>
  )
}

export function OverviewPage() {
  const overview = useQuery({
    queryKey: ['overview'],
    queryFn: metricsApi.overview,
    refetchInterval: 8000,
  })
  const analytics = useQuery({
    queryKey: ['analytics', '1h'],
    queryFn: () => metricsApi.analytics('1h'),
    refetchInterval: 8000,
  })

  if (overview.isLoading) {
    return <Skeleton className="h-96" />
  }
  if (overview.isError) {
    return <ErrorState message="Unable to load traffic." onRetry={() => overview.refetch()} />
  }

  const data = overview.data
  const lastThroughput = analytics.data?.throughput?.at(-1)?.v
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-4 gap-4">
        <Kpi label="Requests/s" value={formatNumber(lastThroughput ?? data?.requestsPerSecond ?? 0)} />
        <Kpi label="Allowed" value={formatNumber(data?.allowed, 0)} hint="since process start" />
        <Kpi label="Rejected" value={formatNumber(data?.rejected, 0)} />
        <Kpi label="P95 latency" value={`${formatNumber(data?.p95LatencyMs, 2)} ms`} />
      </div>
      <div className="grid grid-cols-3 gap-4">
        <Kpi
          label="Cache hit rate"
          value={`${((data?.cacheHitRate ?? 0) * 100).toFixed(1)}%`}
        />
        <Kpi
          label="Healthy instances"
          value={`${data?.healthyInstances ?? 0}/${data?.totalInstances ?? 0}`}
        />
        <Kpi label="Checks" value={formatNumber(data?.checks, 0)} />
      </div>
      {analytics.data && !analytics.data.available ? (
        <ErrorState message="Historical metrics unavailable. Live counters still shown." />
      ) : null}
      <section className="rounded-lg border border-line bg-panel p-4">
        <h2 className="mb-3 text-sm text-mute">Request traffic</h2>
        <Sparkline data={analytics.data?.throughput} />
      </section>
      <div className="grid grid-cols-2 gap-4">
        <section className="rounded-lg border border-line bg-panel p-4">
          <h2 className="mb-3 text-sm text-mute">Allowed</h2>
          <Sparkline data={analytics.data?.allowed} color="#3dd68c" />
        </section>
        <section className="rounded-lg border border-line bg-panel p-4">
          <h2 className="mb-3 text-sm text-mute">Rejected</h2>
          <Sparkline data={analytics.data?.rejected} color="#ff5d5d" />
        </section>
      </div>
      <section className="rounded-lg border border-line bg-panel p-4">
        <h2 className="mb-3 text-sm text-mute">Instance health</h2>
        <div className="divide-y divide-line">
          {(data?.instances ?? []).map((instance) => (
            <Link
              key={instance.id}
              to={`/instances/${instance.id}`}
              className="flex items-center justify-between py-3 hover:text-accent"
            >
              <span className="font-mono text-sm">{instance.id}</span>
              <StatusDot status={instance.status} />
            </Link>
          ))}
        </div>
      </section>
    </div>
  )
}
