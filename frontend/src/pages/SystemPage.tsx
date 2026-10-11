import { useQuery } from '@tanstack/react-query'
import { systemApi } from '../api/system'
import { ErrorState, Skeleton, StatusDot } from '../components/Ui'

export function SystemPage() {
  const health = useQuery({
    queryKey: ['system-health'],
    queryFn: systemApi.health,
    refetchInterval: 8000,
  })
  const info = useQuery({ queryKey: ['system-info'], queryFn: systemApi.info })
  if (health.isLoading || info.isLoading) return <Skeleton className="h-96" />
  if (health.isError) return <ErrorState message="Unable to load system health." onRetry={() => health.refetch()} />

  const components = health.data?.components ?? {}
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-3 gap-4">
        <Card title="Rate Limiter" status={health.data?.status ?? 'UNKNOWN'} body={`${info.data?.configuredInstances ?? 0} configured instances`} />
        <Card title="Redis" status={components.redis ?? 'UNKNOWN'} body={info.data?.redisVersion ?? ''} />
        <Card title="PostgreSQL" status={components.db ?? 'UNKNOWN'} body={info.data?.postgresVersion ?? ''} />
        <Card title="Prometheus" status={health.data?.prometheus?.status ?? 'UNKNOWN'} />
        <Card title="Grafana" status={health.data?.grafana?.status ?? 'UNKNOWN'} />
      </div>
      <section className="rounded-lg border border-line bg-panel p-5">
        <h2 className="mb-4 text-sm uppercase tracking-wider text-mute">Configuration</h2>
        <dl className="grid grid-cols-2 gap-3 font-mono text-sm">
          <Row label="Environment" value={info.data?.environment} />
          <Row label="Instance" value={info.data?.instanceId} />
          <Row label="Policy cache TTL" value={`${info.data?.cacheTtlSeconds} seconds`} />
          <Row label="Redis timeout" value={`${info.data?.redisTimeoutMs} ms`} />
          <Row label="Default failure mode" value={info.data?.failClosed ? 'FAIL_CLOSED' : 'FAIL_OPEN'} />
          <Row label="Redis circuit" value={info.data?.redisCircuitOpen ? 'OPEN (degraded)' : 'CLOSED'} />
          <Row label="Application" value={`${info.data?.application} ${info.data?.version}`} />
          <Row label="Java" value={info.data?.javaVersion} />
          <Row label="Spring Boot" value={info.data?.springBootVersion} />
          <Row label="Uptime" value={`${info.data?.uptimeSeconds} s`} />
        </dl>
      </section>
    </div>
  )
}

function Card({ title, status, body }: { title: string; status: string; body?: string }) {
  return (
    <div className="rounded-lg border border-line bg-panel p-4">
      <div className="text-sm text-mute">{title}</div>
      <div className="mt-3">
        <StatusDot status={status} />
      </div>
      {body ? <div className="mt-2 font-mono text-xs text-mute">{body}</div> : null}
    </div>
  )
}

function Row({ label, value }: { label: string; value?: string }) {
  return (
    <>
      <dt className="text-mute">{label}</dt>
      <dd>{value ?? '—'}</dd>
    </>
  )
}
