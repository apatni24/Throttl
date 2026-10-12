import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { rateLimiterApi } from '../api/rateLimiter'
import { Button, ErrorState } from '../components/Ui'
import { formatMs } from '../utils/format'
import type { PolicyEvaluation, RateLimitContext, RateLimitDecision } from '../types/models'

export function PlaygroundPage() {
  const [context, setContext] = useState<RateLimitContext>({
    userId: 'user-123',
    tenantId: 'acme',
    api: 'payment',
    ip: '10.0.0.1',
  })
  const [decision, setDecision] = useState<RateLimitDecision | null>(null)
  const check = useMutation({
    mutationFn: () => rateLimiterApi.check(context),
    onSuccess: setDecision,
  })
  const burst = useMutation({
    mutationFn: (count: number) => rateLimiterApi.burst(context, count),
  })

  return (
    <div className="grid grid-cols-[320px_1fr] gap-8">
      <form
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault()
          check.mutate()
        }}
      >
        {(['userId', 'tenantId', 'api', 'ip'] as const).map((field) => (
          <label key={field} className="block space-y-1">
            <span className="text-xs uppercase tracking-wider text-mute">{field}</span>
            <input
              value={context[field] ?? ''}
              onChange={(event) => setContext({ ...context, [field]: event.target.value })}
              className="w-full rounded-md border border-line bg-ink px-3 py-2 font-mono text-sm"
            />
          </label>
        ))}
        <Button type="submit" kind="primary" disabled={check.isPending}>
          Check Limit
        </Button>
        <div className="flex flex-wrap gap-2 pt-2">
          {[10, 100, 1000].map((count) => (
            <Button key={count} onClick={() => burst.mutate(count)} disabled={burst.isPending}>
              Send {count}
            </Button>
          ))}
        </div>
      </form>
      <div className="space-y-4">
        {check.isError ? <ErrorState message="Check failed." onRetry={() => check.mutate()} /> : null}
        {decision ? <DecisionCard decision={decision} /> : (
          <p className="text-sm text-mute">Submit a context to see an ALLOW or RATE LIMITED decision.</p>
        )}
        {burst.data ? (
          <div className="rounded-lg border border-line bg-panel p-5">
            <h3 className="text-sm text-mute">Batch result</h3>
            <p className="mt-3 font-mono text-sm">
              Sent {burst.data.sent} · Allowed {burst.data.allowed} · Rejected {burst.data.rejected} ·
              Overspend {burst.data.overspend}
            </p>
          </div>
        ) : null}
      </div>
    </div>
  )
}

function DecisionCard({ decision }: { decision: RateLimitDecision }) {
  const remaining = Number.isFinite(decision.remaining) ? decision.remaining : 0
  return (
    <div className="rounded-lg border border-line bg-panel p-6">
      <div className={`text-2xl font-semibold ${decision.allowed ? 'text-ok' : 'text-bad'}`}>
        {decision.allowed ? 'ALLOWED' : 'RATE LIMITED'}
      </div>
      <div className="mt-4 text-sm text-mute">Remaining tokens</div>
      <div className="mt-1 h-2 overflow-hidden rounded-full bg-raised">
        <div
          className={`h-full ${decision.allowed ? 'bg-ok' : 'bg-bad'}`}
          style={{ width: `${Math.min(100, remaining)}%` }}
        />
      </div>
      <p className="mt-2 font-mono text-sm">{remaining}</p>
      <p className="mt-4 text-sm text-mute">Retry after {formatMs(decision.retryAfterMs)}</p>
      {decision.degraded ? (
        <p className="mt-2 text-sm text-warn">
          Redis unavailable: decided by each policy&apos;s failure mode.
        </p>
      ) : null}
      {decision.rejectedBy ? (
        <p className="mt-2 font-mono text-sm text-bad">Rejected by {decision.rejectedBy}</p>
      ) : null}
      <div className="mt-6 space-y-2">
        <h3 className="text-sm text-mute">Evaluated policies</h3>
        {(decision.evaluations ?? []).length === 0 ? (
          <p className="text-sm text-mute">No matching policies. Request allowed by default.</p>
        ) : (
          decision.evaluations?.map((evaluation) => (
            <div key={evaluation.policyId} className="flex items-center justify-between rounded-md bg-raised px-3 py-2">
              <span className="font-mono text-sm">{evaluation.policyId}</span>
              <span className={statusTone(evaluation.status)}>
                {evaluation.status}
                {['ALLOWED', 'REJECTED', 'LEASED', 'LOCAL_ALLOWED', 'LOCAL_REJECTED'].includes(evaluation.status)
                  ? ` · ${evaluation.remaining} left`
                  : ''}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  )
}

function statusTone(status: PolicyEvaluation['status']): string {
  if (status === 'REJECTED' || status === 'FAILED_CLOSED' || status === 'LOCAL_REJECTED') return 'text-bad'
  if (status === 'ALLOWED' || status === 'LOCAL_ALLOWED' || status === 'LEASED') return 'text-ok'
  if (status === 'FAILED_OPEN') return 'text-warn'
  return 'text-mute'
}
