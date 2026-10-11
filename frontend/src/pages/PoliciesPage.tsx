import { useMemo } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { policiesApi } from '../api/policies'
import { useToast } from '../components/Toast'
import { Button, EmptyState, ErrorState, Skeleton } from '../components/Ui'
import { conditionsSummary, configSummary } from '../utils/format'
import type { Policy, Scope } from '../types/models'

export function PoliciesPage() {
  const [params, setParams] = useSearchParams()
  const search = params.get('q') ?? ''
  const scope = (params.get('scope') ?? 'ALL') as Scope | 'ALL'
  const status = params.get('status') ?? 'ALL'
  const toast = useToast()
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['policies'], queryFn: policiesApi.list })
  const toggle = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      policiesApi.setEnabled(id, enabled),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies'] })
      toast.push('Policy status updated.')
    },
    onError: (error: Error) => toast.push(error.message, 'bad'),
  })
  const remove = useMutation({
    mutationFn: policiesApi.remove,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies'] })
      toast.push('Policy deleted.')
    },
    onError: (error: Error) => toast.push(error.message, 'bad'),
  })

  const rows = useMemo(() => {
    return (query.data ?? []).filter((policy) => {
      const matchesSearch =
        !search ||
        policy.id.toLowerCase().includes(search.toLowerCase()) ||
        policy.name.toLowerCase().includes(search.toLowerCase())
      const matchesScope = scope === 'ALL' || policy.matching.scope === scope
      const matchesStatus =
        status === 'ALL' ||
        (status === 'ACTIVE' ? policy.metadata.enabled : !policy.metadata.enabled)
      return matchesSearch && matchesScope && matchesStatus
    })
  }, [query.data, search, scope, status])

  if (query.isLoading) return <Skeleton className="h-80" />
  if (query.isError) return <ErrorState message="Unable to load policies." onRetry={() => query.refetch()} />
  if (!query.data?.length) {
    return (
      <EmptyState
        title="No rate-limit policies yet."
        body="Create a policy to start making shared decisions across limiter instances."
        action={<Button kind="primary" to="/policies/new">Create Policy</Button>}
      />
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex gap-2">
          <input
            value={search}
            onChange={(event) => {
              params.set('q', event.target.value)
              setParams(params)
            }}
            placeholder="Search"
            className="w-56 rounded-md border border-line bg-ink px-3 py-1.5 text-sm"
          />
          <select
            value={scope}
            onChange={(event) => {
              params.set('scope', event.target.value)
              setParams(params)
            }}
            className="rounded-md border border-line bg-ink px-3 py-1.5 text-sm"
          >
            <option value="ALL">All scopes</option>
            {['USER', 'TENANT', 'API', 'IP'].map((value) => (
              <option key={value}>{value}</option>
            ))}
          </select>
          <select
            value={status}
            onChange={(event) => {
              params.set('status', event.target.value)
              setParams(params)
            }}
            className="rounded-md border border-line bg-ink px-3 py-1.5 text-sm"
          >
            <option value="ALL">All status</option>
            <option value="ACTIVE">Active</option>
            <option value="DISABLED">Disabled</option>
          </select>
        </div>
        <Button kind="primary" to="/policies/new">
          Create Policy
        </Button>
      </div>
      <div className="overflow-hidden rounded-lg border border-line">
        <table className="w-full text-left text-sm">
          <thead className="bg-raised text-mute">
            <tr>
              {['Name', 'Scope', 'Conditions', 'Algorithm', 'Limit', 'Priority', 'Status', ''].map((h) => (
                <th key={h} className="px-4 py-2 font-medium">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((policy: Policy) => (
              <tr key={policy.id} className="border-t border-line">
                <td className="px-4 py-3">
                  <Link to={`/policies/${policy.id}`} className="font-medium hover:text-accent">
                    {policy.name}
                  </Link>
                  <div className="font-mono text-xs text-mute">{policy.id}</div>
                </td>
                <td className="px-4 py-3 font-mono text-xs">{policy.matching.scope}</td>
                <td className="px-4 py-3 text-mute">{conditionsSummary(policy)}</td>
                <td className="px-4 py-3 font-mono text-xs">{policy.strategy.algorithm}</td>
                <td className="px-4 py-3 text-mute">{configSummary(policy)}</td>
                <td className="px-4 py-3">{policy.metadata.priority}</td>
                <td className="px-4 py-3">{policy.metadata.enabled ? 'Active' : 'Disabled'}</td>
                <td className="px-4 py-3 text-right">
                  <div className="flex justify-end gap-2">
                    <Link to={`/policies/${policy.id}/edit`} className="text-xs text-mute hover:text-text">
                      Edit
                    </Link>
                    <button
                      className="text-xs text-mute hover:text-text"
                      onClick={() => toggle.mutate({ id: policy.id, enabled: !policy.metadata.enabled })}
                    >
                      {policy.metadata.enabled ? 'Disable' : 'Enable'}
                    </button>
                    <button
                      className="text-xs text-bad"
                      onClick={() => {
                        if (window.confirm(`Delete ${policy.id}?`)) remove.mutate(policy.id)
                      }}
                    >
                      Delete
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
