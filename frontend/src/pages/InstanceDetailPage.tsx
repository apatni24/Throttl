import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import { instancesApi } from '../api/instances'
import { ErrorState, Skeleton, StatusDot } from '../components/Ui'
import { formatNumber } from '../utils/format'

export function InstanceDetailPage() {
  const { id } = useParams()
  const query = useQuery({
    queryKey: ['instance', id],
    queryFn: () => instancesApi.get(id!),
    refetchInterval: 8000,
    enabled: Boolean(id),
  })
  if (query.isLoading) return <Skeleton className="h-80" />
  if (query.isError || !query.data) {
    return <ErrorState message="Unable to load instance." onRetry={() => query.refetch()} />
  }
  const instance = query.data
  const cacheTotal = (instance.cacheHits ?? 0) + (instance.cacheMisses ?? 0)
  const hitRate = cacheTotal === 0 ? 0 : (instance.cacheHits ?? 0) / cacheTotal
  return (
    <div className="max-w-3xl space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="font-mono text-2xl">{instance.id}</h2>
        <StatusDot status={instance.status} />
      </div>
      <div className="grid grid-cols-2 gap-4 rounded-lg border border-line bg-panel p-5">
        <Fact label="Address" value={instance.url ?? '—'} />
        <Fact label="Checks" value={formatNumber(instance.checks, 0)} />
        <Fact label="Allowed" value={formatNumber(instance.allowed, 0)} />
        <Fact label="Rejected" value={formatNumber(instance.rejected, 0)} />
        <Fact label="P95" value={`${formatNumber(instance.p95LatencyMs, 2)} ms`} />
        <Fact label="P99" value={`${formatNumber(instance.p99LatencyMs, 2)} ms`} />
        <Fact label="Redis P95" value={`${formatNumber(instance.redisP95Ms, 2)} ms`} />
        <Fact label="Cache hit rate" value={`${(hitRate * 100).toFixed(1)}%`} />
      </div>
    </div>
  )
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-xs text-mute">{label}</div>
      <div className="mt-1 font-mono text-sm">{value}</div>
    </div>
  )
}
