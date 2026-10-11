# Design decisions

## Why Redis?

Token-bucket state is read and written on every check. Redis keeps that state in memory, supports sub-millisecond round trips on a local network, and gives every limiter instance the same view of remaining tokens. PostgreSQL is the wrong store for this path: it would add disk latency and lock contention to a decision that must stay cheap.

## Why Lua?

A refill-and-consume cycle is several steps: read tokens, compute elapsed time, refill, cap, consume, write. If those steps run as separate Redis commands, two checkers can both observe `tokens=1` and both allow. Lua runs inside Redis against one key as a single atomic script, so concurrent requests cannot double-spend. When several policies match one request, `token_bucket_and.lua` evaluates every bucket in the same script and consumes tokens from all of them only if every bucket would allow.

## Why PostgreSQL?

Policies change rarely and must survive process restarts. PostgreSQL is the source of truth for configuration. JSONB columns hold `conditions` and algorithm `configuration` so adding Sliding Window later does not require new columns.

## Why Token Bucket?

Token bucket models both a burst (`capacity`) and a sustained rate (`refillRate` / `refillPeriod`) with one piece of state. That matches API quotas: allow a short spike, then settle to a steady rate. Sliding and fixed windows can be added behind the same `RateLimitAlgorithm` interface when a product need appears.

## Why no Redis Cluster in MVP?

A single Redis instance already proves shared state across multiple limiter processes. Cluster adds topology, slot hashing, and failure modes that do not yet correspond to a measured bottleneck. Add it when one Redis node is the documented limit.

## What happens when Redis fails?

The MVP fails closed: if the Lua script cannot run, `/v1/check` returns `allowed: false` with `rejectedBy: redis-unavailable`. `spring.data.redis.timeout` is 500ms so the client does not wait indefinitely. Downstream services should treat that as a deny.

## Why a Redis generation for the policy cache?

Caffeine is per process. Invalidating only the instance that handled `PUT /v1/policies` left the other two serving a stale list for up to 60s. `policy:generation` in Redis is incremented on create/update/delete; each instance compares that counter before using Caffeine. TTL remains a backstop.

## Why separate policy configuration from algorithm implementation?

Matching (who/what is limited) is independent of how tokens refill. Policies persist as data; algorithms are code. The check path resolves policies, then dispatches to `TokenBucketAlgorithm`. A later `SlidingWindowAlgorithm` plugs in without rewriting the API or the table.

## Why a Lettuce connection pool (not async rewrite)?

Lettuce defaults to `shareNativeConnection=true`: one TCP connection per JVM multiplexes every Redis command. Under stepped load (~1200+ req/s per stack, two commands per check), client-visible latency can balloon while Redis server-side execution stays in microseconds — connection-level command queuing, not slow Redis.

**Fix shipped:** `THROTTL_REDIS_SHARE_NATIVE=false` plus a small pool (`max-active: 32`, `commons-pool2`). This is a config change with measured benefit: collapse point moves from ~1200–1500 req/s (shared) to ~3000 req/s (pooled sync) on the same warm-stack protocol.

**Deferred:** `THROTTL_CHECK_IO=async` (reactive Lettuce + `CompletableFuture` on `/v1/check`) also raises the ceiling and keeps Tomcat threads idle, but the Postgres cache-miss path still runs JDBC on `boundedElastic` — the whole request is not end-to-end reactive. Async is validated behind a flag; enable it when there is a concrete reason (e.g. measured thread-pool pressure after pooling), not preemptively.

**Benchmark caveat:** The original warm-ceiling run (August) reported sync-shared “recovery” at 3000 req/s (2990 achieved, 20 ms p95) after a 2500 collapse. Two later replays of the same protocol (Tomcat max still 200/instance) never reproduced that 20 ms point: one stayed saturated (809 ms p95 at 3000), the other reached 2968 req/s at 88 ms p95 with hundreds of dropped iterations. Isolation on a noisier host collapsed monotonically from 1500. Treat post-collapse “recovery” as queue drain plus laptop noise, not a product ceiling. Shared-sync is reliable through ~1500 req/s here.
