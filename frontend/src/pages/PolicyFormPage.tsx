import { useEffect, useState, type ReactNode } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { policiesApi } from '../api/policies'
import { useToast } from '../components/Toast'
import { Button, ErrorState, Skeleton } from '../components/Ui'
import type {
  Algorithm,
  Policy,
  RefillPeriod,
  Scope,
  SlidingWindowConfiguration,
  Strategy,
  TokenBucketConfiguration,
} from '../types/models'
import { validatePolicy } from '../utils/format'

const PERIODS: RefillPeriod[] = ['SECOND', 'MINUTE', 'HOUR']

function defaultStrategy(algorithm: Algorithm): Strategy {
  return algorithm === 'SLIDING_WINDOW'
    ? { algorithm, configuration: { limit: 100, window: 'MINUTE' } }
    : { algorithm, configuration: { capacity: 100, refillRate: 10, refillPeriod: 'SECOND' } }
}

const emptyPolicy = (): Policy => ({
  id: '',
  name: '',
  matching: { scope: 'USER', conditions: {} },
  strategy: defaultStrategy('TOKEN_BUCKET'),
  metadata: { enabled: true, priority: 10 },
})

export function PolicyFormPage() {
  const { id } = useParams()
  const editing = Boolean(id)
  const navigate = useNavigate()
  const toast = useToast()
  const existing = useQuery({
    queryKey: ['policy', id],
    queryFn: () => policiesApi.get(id!),
    enabled: editing,
  })
  const [policy, setPolicy] = useState<Policy>(emptyPolicy())
  const [errors, setErrors] = useState<string[]>([])

  useEffect(() => {
    if (existing.data) setPolicy(existing.data)
  }, [existing.data])

  const save = useMutation({
    mutationFn: () => {
      const payload = sanitizePolicy(policy)
      return editing ? policiesApi.update(id!, payload) : policiesApi.create(payload)
    },
    onSuccess: () => {
      toast.push(editing ? 'Policy updated successfully.' : 'Policy created successfully.')
      navigate('/policies')
    },
    onError: (error: Error) => toast.push(error.message, 'bad'),
  })

  if (editing && existing.isLoading) return <Skeleton className="h-96" />
  if (editing && existing.isError) {
    return <ErrorState message="Unable to load policy." onRetry={() => existing.refetch()} />
  }

  const apiCondition = policy.matching.conditions?.api ?? ''

  return (
    <form
      className="max-w-2xl space-y-8"
      onSubmit={(event) => {
        event.preventDefault()
        const nextErrors = validatePolicy(policy)
        setErrors(nextErrors)
        if (nextErrors.length === 0) save.mutate()
      }}
    >
      {editing ? (
        <p className="rounded-md border border-warn/40 bg-panel px-4 py-3 text-sm text-warn">
          Changing the rate-limit configuration resets existing runtime bucket state for this policy.
        </p>
      ) : null}
      <section className="space-y-3">
        <h2 className="text-sm uppercase tracking-wider text-mute">Identity</h2>
        <Field label="Policy ID">
          <input
            disabled={editing}
            value={policy.id}
            onChange={(e) => setPolicy({ ...policy, id: e.target.value })}
            className="w-full rounded-md border border-line bg-ink px-3 py-2 font-mono text-sm"
          />
        </Field>
        <Field label="Name">
          <input
            value={policy.name}
            onChange={(e) => setPolicy({ ...policy, name: e.target.value })}
            className="w-full rounded-md border border-line bg-ink px-3 py-2 text-sm"
          />
        </Field>
      </section>
      <section className="space-y-3">
        <h2 className="text-sm uppercase tracking-wider text-mute">Matching</h2>
        <Field label="Scope">
          <select
            value={policy.matching.scope}
            onChange={(e) =>
              setPolicy({
                ...policy,
                matching: { ...policy.matching, scope: e.target.value as Scope },
              })
            }
            className="w-full rounded-md border border-line bg-ink px-3 py-2 text-sm"
          >
            {['USER', 'TENANT', 'API', 'IP'].map((scope) => (
              <option key={scope}>{scope}</option>
            ))}
          </select>
        </Field>
        <Field label="API condition (optional)">
          <input
            value={apiCondition}
            placeholder="Leave blank to match every request for this scope"
            onChange={(e) =>
              setPolicy({
                ...policy,
                matching: {
                  ...policy.matching,
                  conditions: e.target.value.trim() ? { api: e.target.value.trim() } : {},
                },
              })
            }
            className="w-full rounded-md border border-line bg-ink px-3 py-2 font-mono text-sm"
          />
        </Field>
      </section>
      <section className="space-y-3">
        <h2 className="text-sm uppercase tracking-wider text-mute">Limiting strategy</h2>
        <Field label="Algorithm">
          <select
            value={policy.strategy.algorithm}
            onChange={(e) =>
              setPolicy({ ...policy, strategy: defaultStrategy(e.target.value as Algorithm) })
            }
            className="w-full rounded-md border border-line bg-ink px-3 py-2 text-sm"
          >
            <option value="TOKEN_BUCKET">TOKEN_BUCKET</option>
            <option value="SLIDING_WINDOW">SLIDING_WINDOW</option>
          </select>
        </Field>
        {policy.strategy.algorithm === 'TOKEN_BUCKET' ? (
          <TokenBucketFields
            configuration={policy.strategy.configuration}
            onChange={(configuration) =>
              setPolicy({ ...policy, strategy: { algorithm: 'TOKEN_BUCKET', configuration } })
            }
          />
        ) : (
          <SlidingWindowFields
            configuration={policy.strategy.configuration}
            onChange={(configuration) =>
              setPolicy({ ...policy, strategy: { algorithm: 'SLIDING_WINDOW', configuration } })
            }
          />
        )}
      </section>
      <section className="space-y-3">
        <h2 className="text-sm uppercase tracking-wider text-mute">Metadata</h2>
        <Field label="Priority">
          <input
            type="number"
            value={policy.metadata.priority}
            onChange={(e) =>
              setPolicy({
                ...policy,
                metadata: { ...policy.metadata, priority: Number(e.target.value) },
              })
            }
            className="w-full rounded-md border border-line bg-ink px-3 py-2 font-mono text-sm"
          />
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={policy.metadata.enabled}
            onChange={(e) =>
              setPolicy({
                ...policy,
                metadata: { ...policy.metadata, enabled: e.target.checked },
              })
            }
          />
          Enabled
        </label>
        {editing && policy.metadata.updatedAt ? (
          <p className="text-xs text-mute">Updated {policy.metadata.updatedAt}</p>
        ) : null}
      </section>
      {errors.length > 0 ? (
        <ul className="text-sm text-bad">
          {errors.map((error) => (
            <li key={error}>{error}</li>
          ))}
        </ul>
      ) : null}
      <div className="flex gap-3">
        <Button onClick={() => navigate('/policies')}>Cancel</Button>
        <Button type="submit" kind="primary" disabled={save.isPending}>
          {editing ? 'Save Policy' : 'Create Policy'}
        </Button>
      </div>
    </form>
  )
}

function sanitizePolicy(policy: Policy): Policy {
  const conditions: Record<string, string> = {}
  for (const [key, value] of Object.entries(policy.matching.conditions ?? {})) {
    if (key.trim() && value.trim()) {
      conditions[key.trim()] = value.trim()
    }
  }
  return {
    ...policy,
    matching: { ...policy.matching, conditions },
  }
}

function TokenBucketFields({
  configuration,
  onChange,
}: {
  configuration: TokenBucketConfiguration
  onChange: (next: TokenBucketConfiguration) => void
}) {
  return (
    <div className="grid grid-cols-3 gap-3">
      <NumberField
        label="Capacity"
        value={configuration.capacity}
        onChange={(capacity) => onChange({ ...configuration, capacity })}
      />
      <NumberField
        label="Refill rate"
        value={configuration.refillRate}
        onChange={(refillRate) => onChange({ ...configuration, refillRate })}
      />
      <PeriodField
        label="Refill period"
        value={configuration.refillPeriod}
        onChange={(refillPeriod) => onChange({ ...configuration, refillPeriod })}
      />
    </div>
  )
}

function SlidingWindowFields({
  configuration,
  onChange,
}: {
  configuration: SlidingWindowConfiguration
  onChange: (next: SlidingWindowConfiguration) => void
}) {
  return (
    <div className="space-y-2">
      <div className="grid grid-cols-2 gap-3">
        <NumberField
          label="Limit"
          value={configuration.limit}
          onChange={(limit) => onChange({ ...configuration, limit })}
        />
        <PeriodField
          label="Window"
          value={configuration.window}
          onChange={(window) => onChange({ ...configuration, window })}
        />
      </div>
      <p className="text-xs text-mute">
        At most {configuration.limit} requests in any trailing {configuration.window.toLowerCase()}, with
        no burst at window boundaries.
      </p>
    </div>
  )
}

function NumberField({
  label,
  value,
  onChange,
}: {
  label: string
  value: number
  onChange: (next: number) => void
}) {
  return (
    <Field label={label}>
      <input
        type="number"
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="w-full rounded-md border border-line bg-ink px-3 py-2 font-mono text-sm"
      />
    </Field>
  )
}

function PeriodField({
  label,
  value,
  onChange,
}: {
  label: string
  value: RefillPeriod
  onChange: (next: RefillPeriod) => void
}) {
  return (
    <Field label={label}>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value as RefillPeriod)}
        className="w-full rounded-md border border-line bg-ink px-3 py-2 text-sm"
      >
        {PERIODS.map((period) => (
          <option key={period}>{period}</option>
        ))}
      </select>
    </Field>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block space-y-1">
      <span className="text-xs text-mute">{label}</span>
      {children}
    </label>
  )
}
