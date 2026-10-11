import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { instancesApi } from '../api/instances'
import { ErrorState, Skeleton, StatusDot } from '../components/Ui'
import { formatNumber } from '../utils/format'

export function InstancesPage() {
  const query = useQuery({
    queryKey: ['instances'],
    queryFn: instancesApi.list,
    refetchInterval: 8000,
  })
  if (query.isLoading) return <Skeleton className="h-64" />
  if (query.isError) return <ErrorState message="Unable to load instances." onRetry={() => query.refetch()} />

  return (
    <div className="overflow-hidden rounded-lg border border-line">
      <table className="w-full text-left text-sm">
        <thead className="bg-raised text-mute">
          <tr>
            {['Instance', 'Status', 'Checks', 'P95', 'Address'].map((h) => (
              <th key={h} className="px-4 py-2 font-medium">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {(query.data ?? []).map((instance) => (
            <tr key={instance.id} className="border-t border-line">
              <td className="px-4 py-3">
                <Link to={`/instances/${instance.id}`} className="font-mono hover:text-accent">
                  {instance.id}
                </Link>
              </td>
              <td className="px-4 py-3">
                <StatusDot status={instance.status} />
              </td>
              <td className="px-4 py-3 font-mono">{formatNumber(instance.checks, 0)}</td>
              <td className="px-4 py-3 font-mono">{formatNumber(instance.p95LatencyMs, 2)} ms</td>
              <td className="px-4 py-3 font-mono text-xs text-mute">{instance.url}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
