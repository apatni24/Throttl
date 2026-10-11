import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { metricsApi } from '../api/metrics'
import { Sparkline } from '../components/Sparkline'
import { ErrorState, Skeleton } from '../components/Ui'

export function AnalyticsPage() {
  const [range, setRange] = useState('1h')
  const query = useQuery({
    queryKey: ['analytics', range],
    queryFn: () => metricsApi.analytics(range),
    refetchInterval: 15000,
  })
  if (query.isLoading) return <Skeleton className="h-96" />
  if (query.isError) return <ErrorState message="Unable to load analytics." onRetry={() => query.refetch()} />
  const data = query.data
  return (
    <div className="space-y-6">
      <select
        value={range}
        onChange={(event) => setRange(event.target.value)}
        className="rounded-md border border-line bg-ink px-3 py-1.5 text-sm"
      >
        <option value="15m">Last 15 minutes</option>
        <option value="1h">Last 1 hour</option>
        <option value="6h">Last 6 hours</option>
        <option value="24h">Last 24 hours</option>
      </select>
      {!data?.available ? (
        <ErrorState message={data?.error ?? 'Metrics backend unavailable.'} onRetry={() => query.refetch()} />
      ) : null}
      <Chart title="Throughput" data={data?.throughput} />
      <div className="grid grid-cols-2 gap-4">
        <Chart title="Allowed" data={data?.allowed} color="#3dd68c" />
        <Chart title="Rejected" data={data?.rejected} color="#ff5d5d" />
      </div>
      <div className="grid grid-cols-3 gap-4">
        <Chart title="P50 latency (s)" data={data?.latencyP50} />
        <Chart title="P95 latency (s)" data={data?.latencyP95} />
        <Chart title="P99 latency (s)" data={data?.latencyP99} />
      </div>
      <Chart title="Redis P95 (s)" data={data?.redisP95} />
      <div className="grid grid-cols-2 gap-4">
        <Chart title="Cache hits" data={data?.cacheHits} color="#3dd68c" />
        <Chart title="Cache misses" data={data?.cacheMisses} color="#e6a23c" />
      </div>
    </div>
  )
}

function Chart({
  title,
  data,
  color,
}: {
  title: string
  data?: { t: number; v: number }[]
  color?: string
}) {
  return (
    <section className="rounded-lg border border-line bg-panel p-4">
      <h2 className="mb-3 text-sm text-mute">{title}</h2>
      <Sparkline data={data} color={color} />
    </section>
  )
}
