import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { policiesApi } from '../api/policies'
import { useToast } from '../components/Toast'
import { Button, ErrorState, Skeleton, StatusDot } from '../components/Ui'
import { conditionsSummary, configSummary } from '../utils/format'

export function PolicyDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: ['policy', id],
    queryFn: () => policiesApi.get(id!),
    enabled: Boolean(id),
  })
  const toggle = useMutation({
    mutationFn: (enabled: boolean) => policiesApi.setEnabled(id!, enabled),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policy', id] })
      toast.push('Policy status updated.')
    },
  })

  if (query.isLoading) return <Skeleton className="h-80" />
  if (query.isError || !query.data) {
    return <ErrorState message="Unable to load policy." onRetry={() => query.refetch()} />
  }
  const policy = query.data
  return (
    <div className="max-w-3xl space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <h2 className="text-2xl font-semibold">{policy.name}</h2>
          <p className="font-mono text-sm text-mute">{policy.id}</p>
        </div>
        <StatusDot status={policy.metadata.enabled ? 'UP' : 'DOWN'} />
      </div>
      <section className="grid grid-cols-2 gap-4 rounded-lg border border-line bg-panel p-5">
        <Fact label="Scope" value={policy.matching.scope} />
        <Fact label="Conditions" value={conditionsSummary(policy)} />
        <Fact label="Algorithm" value={policy.strategy.algorithm} />
        <Fact label="Configuration" value={configSummary(policy)} />
        <Fact label="Priority" value={String(policy.metadata.priority)} />
        <Fact label="Updated" value={policy.metadata.updatedAt ?? '—'} />
      </section>
      <p className="text-sm text-mute">
        Per-policy live traffic is only shown when the backend exposes it. Use Analytics for cluster-wide
        series and Playground to test this policy.
      </p>
      <div className="flex gap-3">
        <Button to={`/policies/${policy.id}/edit`}>Edit</Button>
        <Button onClick={() => toggle.mutate(!policy.metadata.enabled)}>
          {policy.metadata.enabled ? 'Disable' : 'Enable'}
        </Button>
        <Link to="/playground" className="rounded-md border border-line px-3 py-1.5 text-sm">
          Test in Playground
        </Link>
        <Button onClick={() => navigate('/policies')}>Back</Button>
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
