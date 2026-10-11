# Design decisions

## Why Redis?

Token-bucket state is read and written on every check. Redis keeps that state in memory, supports sub-millisecond round trips on a local network, and gives every limiter instance the same view of remaining tokens. PostgreSQL is the wrong store for this path: it would add disk latency and lock contention to a decision that must stay cheap.

## Why Lua?

A refill-and-consume cycle is several steps: read tokens, compute elapsed time, refill, cap, consume, write. If those steps run as separate Redis commands, two checkers can both observe `tokens=1` and both allow. Lua runs inside Redis against one key as a single atomic script, so concurrent requests cannot double-spend. When several policies match one request, `rate_limit.lua` evaluates every bucket in the same script, whatever its algorithm, and consumes from all of them only if every bucket would allow.

## Why PostgreSQL?

Policies change rarely and must survive process restarts. PostgreSQL is the source of truth for configuration. JSONB columns hold `conditions` and algorithm `configuration`; adding the sliding window needed no schema change.

## Why token bucket and sliding window?

They answer different product questions, so a policy picks one.

- **Token bucket** (`capacity`, `refillRate`, `refillPeriod`) answers "allow a spike, then settle to a steady rate". It is the right default for API quotas where short bursts are fine. Two fields of state per key.
- **Sliding window counter** (`limit`, `window`) answers "never more than N in any trailing window". A token bucket of capacity 100 refilling 100/min admits up to 200 in one minute across a refill boundary; a fixed window admits up to 2N across a window edge. Contracts and abuse limits ("5 login attempts per minute") need the stricter guarantee. Three fields of state per key: window start, current count, previous count.

The sliding window is the counter approximation, not a log of timestamps: the trailing count is `previous × (unexpired fraction) + current`. It is O(1) memory per key instead of O(N), and it assumes requests in the previous window were evenly spread. Cloudflare measured this approximation at about 0.003% wrongly allowed or denied requests on real traffic; the cost of an exact log (a sorted set per key, trimmed on every check) is not worth that difference here.

Windows are aligned to the epoch and `now` comes from Redis `TIME`. A stored window start that is ahead of `now` (possible after a failover to a replica whose clock is behind) is kept rather than rolled backwards, so a clock step cannot reset a window and re-admit a full quota.

`retryAfterMs` is exact for both algorithms: it is the earliest moment the next request would be allowed if nobody else consumed. For the sliding window that means solving for when the previous window's weight decays enough, possibly after the next rollover. `SlidingWindowMathTest` checks that a request at `retryAfter` is allowed and one at `retryAfter - 1` is not over 500 randomized traces. The formulas are arranged so integer inputs produce exact doubles; the first version computed `W × (1 - (L-r)/c)`, rounded `50` to `50.00000000000004`, and reported a retry 1 ms late.

## Why does "now" come from Redis?

The scripts used to receive `System.currentTimeMillis()` from whichever limiter handled the request. Three JVMs do not share a clock. An instance running ahead refills a bucket early and stamps a future `lastRefillTimestamp`; an instance running behind then sees negative elapsed time and refills nothing until its clock catches up. Effective rate depended on which instance nginx picked. The scripts now call `redis.call('TIME')`, so refill math runs against the one clock that owns the state. Calling `TIME` before a write needs script effects replication (Redis 5+; default in 7).

## Why no Redis Cluster in MVP?

A single Redis instance already proves shared state across multiple limiter processes. Cluster adds topology and failure modes that do not yet correspond to a measured bottleneck. Add it when one Redis node is the documented limit. The key layout and the cross-slot path below are in place so that move is a deployment change, not a rewrite.

## Why `rl:{SCOPE:value}:policyId`?

The all-or-nothing script touches every bucket that matches a request in one `EVAL`. Redis Cluster rejects a script whose keys hash to different slots. The old layout, `rl:<policyId>:<scopeValue>`, had no hash tag, so Redis hashed the whole key and two limits on one user (per second and per day) almost always landed on different slots.

The hash tag is now the subject, `{USER:u123}`. Every policy on that user shares a slot and stays one atomic script, while different users still spread across the cluster. Tagging on the policy instead would put every user of a policy on one slot, which is a hot shard by design.

Tagging cannot co-locate different subjects. A request matching a USER limit and a TENANT limit touches `{USER:u123}` and `{TENANT:acme}`, and the tenant bucket is shared by every user in the tenant. With `THROTTL_REDIS_CLUSTER=true`, the engine groups keys by slot (Lettuce `SlotHash`, the same CRC16 Redis uses), runs one atomic script per group, and refunds groups that consumed if any group denied. Properties:

- **Never overspends.** A token is only kept if every group allowed.
- **Can briefly under-admit.** Between consume and refund, a concurrent check sees one fewer token. That errs toward denial, which matches fail-closed.
- **Refunds cannot starve.** The cross-slot check runs on one bound connection, so the refund never borrows from a pool that concurrent checks have drained. An earlier version borrowed per command; under 1000 concurrent checks six refunds timed out and those tokens leaked. `CrossSlotIntegrationTest` asserts exact conservation (`tokens == capacity - allowed`) to catch that.
- **Refund failure is logged, not hidden.** If Redis itself fails mid-refund, the tokens stay spent until refill and `redis_errors_total` increments.

Standalone Redis keeps the single-script path for every request; the tag is harmless there.

## What happens when Redis fails?

The MVP fails closed: if the Lua script cannot run, `/v1/check` returns `allowed: false` with `rejectedBy: redis-unavailable`. `spring.data.redis.timeout` is 500ms so the client does not wait indefinitely. Downstream services should treat that as a deny.

## Why authenticate the control plane separately?

`/v1/check` is a data-plane call: every protected service makes it on every request, and it can only consume tokens. Policy writes change who is limited and how hard. An unauthenticated `PUT` could set capacity to a billion, or a `DELETE` could remove the limit in front of payments. Those are different trust boundaries, so writes require `Authorization: Bearer $THROTTL_ADMIN_TOKEN` and the check path stays open.

- The check is a Spring MVC interceptor, not a servlet filter, so CORS headers are already set when it rejects. The console sees a readable 401 instead of an opaque CORS failure.
- Token comparison hashes both sides and uses `MessageDigest.isEqual`, so response time does not reveal how many leading characters matched.
- No token configured means writes return 503. A missing secret should not fall open.
- Reads stay open for dashboards. A single shared token is the minimum boundary, not per-operator identity; the next step would be OIDC with an audit log of policy changes.

## Why a Redis generation for the policy cache?

Caffeine is per process. Invalidating only the instance that handled `PUT /v1/policies` left the other two serving a stale list for up to 60s. `policy:generation` in Redis is incremented on create/update/delete; each instance compares that counter before using Caffeine. TTL remains a backstop.

## Why separate policy configuration from algorithm implementation?

Matching (who/what is limited) is independent of how capacity is counted. Policies persist as data; algorithms are code. In Java, `LimitConfiguration` is a sealed interface (`TokenBucketConfiguration`, `SlidingWindowConfiguration`), so every `switch` over it is checked for exhaustiveness at compile time and adding a third algorithm fails the build until mapping, validation, and Lua argument encoding handle it.

Dispatch does not happen in Java, though. An earlier plan had one `RateLimitAlgorithm` class per algorithm, but a request can match a token-bucket policy and a sliding-window policy at once, and the all-or-nothing guarantee only holds if both are decided in one atomic script. So `rate_limit.lua` takes an algorithm code per key and branches inside Redis; `RedisRateLimiter` only encodes arguments. The refund script used by the cross-slot path is algorithm-aware the same way (add tokens back, or decrement the window count).

## Why a Lettuce connection pool (not async rewrite)?

Lettuce defaults to `shareNativeConnection=true`: one TCP connection per JVM multiplexes every Redis command. Under stepped load (~1200+ req/s per stack, two commands per check), client-visible latency can balloon while Redis server-side execution stays in microseconds — connection-level command queuing, not slow Redis.

**Fix shipped:** `THROTTL_REDIS_SHARE_NATIVE=false` plus a small pool (`max-active: 32`, `commons-pool2`). This is a config change with measured benefit: collapse point moves from ~1200–1500 req/s (shared) to ~3000 req/s (pooled sync) on the same warm-stack protocol.

**Deferred:** `THROTTL_CHECK_IO=async` (reactive Lettuce + `CompletableFuture` on `/v1/check`) also raises the ceiling and keeps Tomcat threads idle, but the Postgres cache-miss path still runs JDBC on `boundedElastic` — the whole request is not end-to-end reactive. Async is validated behind a flag; enable it when there is a concrete reason (e.g. measured thread-pool pressure after pooling), not preemptively.

**Benchmark caveat:** The original warm-ceiling run (August) reported sync-shared “recovery” at 3000 req/s (2990 achieved, 20 ms p95) after a 2500 collapse. Two later replays of the same protocol (Tomcat max still 200/instance) never reproduced that 20 ms point: one stayed saturated (809 ms p95 at 3000), the other reached 2968 req/s at 88 ms p95 with hundreds of dropped iterations. Isolation on a noisier host collapsed monotonically from 1500. Treat post-collapse “recovery” as queue drain plus laptop noise, not a product ceiling. Shared-sync is reliable through ~1500 req/s here.
