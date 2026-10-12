import type { Policy } from '../types/models'

export function validatePolicy(policy: Policy): string[] {
  const errors: string[] = []
  if (!policy.id?.trim()) errors.push('Policy ID is required')
  else if (!/^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$/.test(policy.id)) {
    errors.push('Policy ID must be letters, digits, ".", "_", ":", or "-"')
  }
  if (!policy.name?.trim()) errors.push('Name is required')
  if (!policy.matching?.scope) errors.push('Scope is required')
  const strategy = policy.strategy
  if (!strategy?.algorithm) errors.push('Algorithm is required')
  else if (!strategy.configuration) errors.push('Configuration is required')
  else if (strategy.algorithm === 'TOKEN_BUCKET') {
    const cfg = strategy.configuration
    if (!(cfg.capacity > 0)) errors.push('Capacity must be greater than 0')
    if (!(cfg.refillRate > 0)) errors.push('Refill rate must be greater than 0')
    if (!cfg.refillPeriod) errors.push('Refill period is required')
  } else {
    const cfg = strategy.configuration
    if (!(cfg.limit > 0)) errors.push('Limit must be greater than 0')
    if (!cfg.window) errors.push('Window is required')
  }
  const lease = strategy?.lease
  if (lease) {
    if (!(lease.tokens >= 2)) errors.push('Lease size must be at least 2')
    if (!(lease.ttlMs >= 10 && lease.ttlMs <= 60000)) errors.push('Lease TTL must be 10–60000 ms')
  }
  if (policy.metadata.priority < 0) errors.push('Priority must be 0 or greater')
  return errors
}

export function configSummary(policy: Policy): string {
  const strategy = policy.strategy
  const lease = strategy.lease ? ` · lease ${strategy.lease.tokens}/${strategy.lease.ttlMs}ms` : ''
  if (strategy.algorithm === 'SLIDING_WINDOW') {
    return `${strategy.configuration.limit} per sliding ${strategy.configuration.window.toLowerCase()}${lease}`
  }
  const cfg = strategy.configuration
  return `${cfg.capacity} cap · ${cfg.refillRate}/${cfg.refillPeriod.toLowerCase()}${lease}`
}

export function conditionsSummary(policy: Policy): string {
  const entries = Object.entries(policy.matching.conditions ?? {})
  return entries.length === 0 ? '—' : entries.map(([k, v]) => `${k}=${v}`).join(', ')
}

export function formatMs(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

export function formatNumber(value: number | undefined, digits = 1): string {
  if (value == null || Number.isNaN(value)) return '—'
  if (Math.abs(value) >= 1000) return value.toFixed(0)
  return value.toFixed(digits)
}
