export type Scope = 'USER' | 'TENANT' | 'API' | 'IP'
export type Algorithm = 'TOKEN_BUCKET' | 'SLIDING_WINDOW'
export type RefillPeriod = 'SECOND' | 'MINUTE' | 'HOUR'
export type FailureMode = 'FAIL_CLOSED' | 'FAIL_OPEN' | 'LOCAL_FALLBACK'

export type TokenBucketConfiguration = {
  capacity: number
  refillRate: number
  refillPeriod: RefillPeriod
}

export type SlidingWindowConfiguration = {
  limit: number
  window: RefillPeriod
}

export type LeaseConfiguration = {
  tokens: number
  ttlMs: number
}

export type Strategy = (
  | { algorithm: 'TOKEN_BUCKET'; configuration: TokenBucketConfiguration }
  | { algorithm: 'SLIDING_WINDOW'; configuration: SlidingWindowConfiguration }
) & { lease?: LeaseConfiguration | null }

export type Policy = {
  id: string
  name: string
  matching: {
    scope: Scope
    conditions: Record<string, string>
  }
  strategy: Strategy
  metadata: {
    enabled: boolean
    priority: number
    failureMode?: FailureMode | null
    createdAt?: string
    updatedAt?: string
  }
}

export type RateLimitContext = {
  userId?: string
  tenantId?: string
  api?: string
  ip?: string
}

export type PolicyEvaluation = {
  policyId: string
  status:
    | 'ALLOWED'
    | 'REJECTED'
    | 'SKIPPED'
    | 'LEASED'
    | 'FAILED_OPEN'
    | 'FAILED_CLOSED'
    | 'LOCAL_ALLOWED'
    | 'LOCAL_REJECTED'
  remaining: number
  retryAfterMs: number
}

export type RateLimitDecision = {
  allowed: boolean
  remaining: number
  retryAfterMs: number
  rejectedBy?: string
  evaluations?: PolicyEvaluation[]
  degraded?: boolean
}

export type BurstResult = {
  sent: number
  allowed: number
  rejected: number
  overspend: number
  rejectedBy?: string
}

export type MetricPoint = { t: number; v: number }

export type InstanceSnapshot = {
  id: string
  url?: string
  status: 'UP' | 'DOWN' | 'UNKNOWN'
  checks?: number
  allowed?: number
  rejected?: number
  cacheHits?: number
  cacheMisses?: number
  p95LatencyMs?: number
  p99LatencyMs?: number
  redisP95Ms?: number
}

export type OverviewMetrics = InstanceSnapshot & {
  healthyInstances?: number
  totalInstances?: number
  instances?: InstanceSnapshot[]
  cacheHitRate?: number
  requestsPerSecond?: number
}

export type AnalyticsMetrics = {
  range: string
  available: boolean
  error?: string
  throughput?: MetricPoint[]
  allowed?: MetricPoint[]
  rejected?: MetricPoint[]
  latencyP50?: MetricPoint[]
  latencyP95?: MetricPoint[]
  latencyP99?: MetricPoint[]
  redisP95?: MetricPoint[]
  cacheHits?: MetricPoint[]
  cacheMisses?: MetricPoint[]
}

export type SystemHealth = {
  status: string
  components?: Record<string, string>
  prometheus?: { status: string }
  grafana?: { status: string }
}

export type SystemInfo = {
  application: string
  version: string
  environment: string
  instanceId: string
  javaVersion: string
  springBootVersion: string
  uptimeSeconds: number
  cacheTtlSeconds: number
  failClosed: boolean
  defaultFailureMode?: FailureMode
  redisCircuitOpen?: boolean
  redisTimeoutMs: number
  configuredInstances: number
  redisVersion: string
  redisTopology?: 'STANDALONE' | 'SENTINEL' | 'CLUSTER'
  redisPrimary?: string
  postgresVersion: string
}
