import type { ReactNode } from 'react'

export function StatusDot({ status }: { status: string }) {
  const up = status === 'UP' || status === 'Healthy' || status === 'CONNECTED'
  const down = status === 'DOWN' || status === 'UNHEALTHY'
  return (
    <span className="inline-flex items-center gap-2">
      <span
        className={`h-2 w-2 rounded-full ${up ? 'bg-ok' : down ? 'bg-bad' : 'bg-warn'}`}
      />
      <span className="text-sm text-text">{up ? 'Healthy' : down ? 'Down' : status}</span>
    </span>
  )
}

export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`animate-pulse rounded-md bg-raised ${className}`} />
}

export function EmptyState({
  title,
  body,
  action,
}: {
  title: string
  body: string
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-start gap-3 rounded-lg border border-line bg-panel px-6 py-10">
      <h2 className="text-lg font-medium">{title}</h2>
      <p className="max-w-xl text-sm text-mute">{body}</p>
      {action}
    </div>
  )
}

export function ErrorState({
  message,
  onRetry,
}: {
  message: string
  onRetry?: () => void
}) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-lg border border-bad/40 bg-panel px-5 py-4">
      <p className="text-sm text-bad">{message}</p>
      {onRetry ? (
        <button
          type="button"
          onClick={onRetry}
          className="rounded-md border border-line px-3 py-1.5 text-sm hover:border-accent"
        >
          Retry
        </button>
      ) : null}
    </div>
  )
}

export function Button({
  children,
  onClick,
  to,
  kind = 'default',
  type = 'button',
  disabled,
}: {
  children: ReactNode
  onClick?: () => void
  to?: string
  kind?: 'default' | 'primary' | 'danger'
  type?: 'button' | 'submit'
  disabled?: boolean
}) {
  const cls =
    kind === 'primary'
      ? 'bg-accent text-ink hover:opacity-90'
      : kind === 'danger'
        ? 'border border-bad/50 text-bad hover:bg-bad/10'
        : 'border border-line hover:border-accent'
  const className = `inline-flex items-center rounded-md px-3 py-1.5 text-sm font-medium ${cls} disabled:opacity-50`
  if (to) {
    return (
      <a href={to} className={className}>
        {children}
      </a>
    )
  }
  return (
    <button type={type} onClick={onClick} disabled={disabled} className={className}>
      {children}
    </button>
  )
}
