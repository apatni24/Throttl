# Throttl — Distributed Rate Limiter

A production-style rate-limiting service that multiple application instances can call for low-latency, shared decisions.

PostgreSQL stores policies. Redis stores limiter state (token buckets and sliding windows). A Lua script makes check-and-consume atomic. Several limiter processes sit in front of the same Redis, so the limit is global.

## Problem

In-process rate limiters do not work once traffic is spread across instances. Each JVM has its own counters, so a 100 request/minute user limit becomes `100 × instance count`. Shared state is required, and that state must update atomically or concurrent requests will double-spend tokens.

## Architecture

```text
                         ┌──────────────────┐
                         │  Demo Application │
                         └────────┬─────────┘
                                  │
                                  ↓
                         RateLimiterClient
                                  │
                                  ↓
                    ┌─────────────────────────┐
                    │    Rate Limit Service   │
                    │  REST → Policy Engine   │
                    │  → Bucket/Window (Lua)  │
                    │       → Policy Cache    │
                    └──────┬─────────┬────────┘
                           │         │
                           ↓         ↓
                      PostgreSQL   Redis + Lua
                       Policies    Buckets

                    Prometheus → Grafana
```

Local Compose runs three limiter instances behind nginx. All three share Redis. `docker-compose.ha.yml` adds a Redis replica and Sentinel failover.

## Policy model

A policy has four parts:

| Part | Role |
|---|---|
| Identity | `id`, `name` |
| Matching | `scope` (`USER`, `TENANT`, `API`, `IP`) and `conditions` |
| Strategy | `algorithm` + algorithm-specific `configuration`, optional `lease` |
| Metadata | `enabled`, `priority`, `failureMode`, timestamps |

Algorithms: **TOKEN_BUCKET** and **SLIDING_WINDOW**. Algorithm-specific fields live inside `strategy.configuration`, not as top-level columns.

Example:

```json
{
  "id": "payment-user-limit",
  "name": "Payment User Limit",
  "matching": {
    "scope": "USER",
    "conditions": { "api": "payment" }
  },
  "strategy": {
    "algorithm": "TOKEN_BUCKET",
    "configuration": {
      "capacity": 100,
      "refillRate": 10,
      "refillPeriod": "SECOND"
    }
  },
  "metadata": { "enabled": true, "priority": 10 }
}
```

## Algorithms

**Token bucket** (`capacity`, `refillRate`, `refillPeriod`). A bucket starts full. Each allowed request consumes one token. Tokens refill at `refillRate` per `refillPeriod`, capped at capacity. Use it when short bursts are fine.

**Sliding window counter** (`limit`, `window`). At most `limit` requests in any trailing `window`, estimated as `previous × unexpired fraction + current`. There is no 2× burst at window edges. Use it for contractual or abuse limits.

```json
"strategy": { "algorithm": "SLIDING_WINDOW", "configuration": { "limit": 5, "window": "MINUTE" } }
```

Either way, a denial carries `retryAfterMs`: the earliest time the next request would be allowed.

## Hot keys

Most checks on a hot key never reach Redis:

- **Deny cache** (default on, `THROTTL_DENY_CACHE_ENABLED`). After Redis denies a bucket, the instance denies that key locally until `retryAfterMs`, capped at `THROTTL_DENY_CACHE_MAX_MS` (1000). An over-limit client costs Redis about one call per instance per second instead of one per request.
- **Leases** (opt-in per policy). A leased policy takes up to `tokens` units from Redis in one call and spends them in process for up to `ttlMs`. Redis still counts every unit, so the limit is never exceeded; admissions can shift by up to `ttlMs`, and unused units expire as under-admission.

```json
"strategy": {
  "algorithm": "TOKEN_BUCKET",
  "configuration": { "capacity": 1000, "refillRate": 1000, "refillPeriod": "SECOND" },
  "lease": { "tokens": 50, "ttlMs": 1000 }
}
```

Checks answered locally show up in `rate_limit_local_decisions_total{source}`, and leased policies report status `LEASED` in the check response. Trade-offs: [docs/design-decisions.md](docs/design-decisions.md#does-every-check-need-a-redis-round-trip-hot-keys).

## Redis and Lua

Runtime keys look like `rl:{SCOPE:value}:policyId` (for example `rl:{USER:u123}:payment-user-limit`) and store a hash: `tokens` and `lastRefillTimestamp` for a token bucket, `windowStart`, `current`, and `previous` for a sliding window. `rate_limit.lua` reads, refills or rolls, checks, consumes, and writes every matching key in one Redis execution, so two concurrent checks cannot both spend the last unit, and a request matching policies of both algorithms is still all-or-nothing.

The script reads "now" from Redis `TIME`, not from the limiter JVM, so every instance refills against one clock.

The braces are a Redis Cluster hash tag: every policy on the same subject lands on one slot and is consumed in one atomic script. With `THROTTL_REDIS_CLUSTER=true`, a request whose policies span slots (a USER and a TENANT limit, say) runs one atomic script per slot and refunds the slots that consumed if any slot denies. That path can briefly deny early under contention; it never overspends. Refunds are counted in `rate_limit_cross_slot_refunds_total`.

## PostgreSQL

PostgreSQL is the source of truth for **what the policy is**. It is not used for request-time token counts. `conditions` and `configuration` are JSONB so new algorithms do not require schema changes.

## Caching

Enabled policies are cached in Caffeine for 60 seconds (configurable). Writes invalidate the cache. Metrics: `policy_cache_hits_total`, `policy_cache_misses_total`.

## Observability

- Prometheus scrape: `http://localhost:8080/actuator/prometheus` (via nginx) or each instance on the Docker network
- Grafana: `http://localhost:3000` (anonymous viewer, or `admin` / `admin`)
- Dashboard: **Distributed Rate Limiter** (requests/sec, allow vs reject, latency, Redis latency, cache hit rate, top rejected policies)

## API

### Authentication

Every state-changing control-plane call (`POST`/`PUT`/`PATCH`/`DELETE` under `/v1/`, including the playground burst) requires `Authorization: Bearer $THROTTL_ADMIN_TOKEN`. Reads and the data-plane `POST /v1/check` stay open. If `THROTTL_ADMIN_TOKEN` is unset, writes return 503 rather than falling open. Compose uses `throttl-dev-admin-token` unless you export your own.

### Policies

```http
POST   /v1/policies
GET    /v1/policies
GET    /v1/policies/{id}
PUT    /v1/policies/{id}
DELETE /v1/policies/{id}
```

### Check

`POST /v1/check` returns a **decision body**, not HTTP 429. This service is the limiter, not the protected app.

```json
{
  "userId": "u123",
  "tenantId": "acme",
  "api": "payment",
  "ip": "10.0.0.1"
}
```

Allowed:

```json
{ "allowed": true, "remaining": 73, "retryAfterMs": 0 }
```

Rejected:

```json
{
  "allowed": false,
  "remaining": 0,
  "retryAfterMs": 3200,
  "rejectedBy": "payment-user-limit"
}
```

A request must pass **every** applicable policy. The first failing policy (highest priority first) is returned in `rejectedBy`.

### When Redis is down

Each policy sets `metadata.failureMode`: `FAIL_CLOSED` (deny), `FAIL_OPEN` (allow), or `LOCAL_FALLBACK` (enforce `limit / instances` in process). Unset inherits `THROTTL_FAIL_CLOSED` (default true). Any matching `FAIL_CLOSED` policy denies the request. Decisions made without Redis carry `"degraded": true`. A circuit breaker skips Redis for `THROTTL_REDIS_BREAKER_OPEN_MS` (1000) after a failure, so degraded checks do not each wait out the 500ms timeout.

```json
"metadata": { "enabled": true, "priority": 10, "failureMode": "LOCAL_FALLBACK" }
```

## Local setup

Requires Docker. Java 21 and Gradle are only needed for tests outside Compose.

```bash
docker compose up --build
```

| Service | URL |
|---|---|
| Rate limiter (3 instances via nginx) | http://localhost:8080 |
| Health | http://localhost:8080/actuator/health |
| Demo payment service | http://localhost:8090 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |
| Throttl console | http://localhost:8088 |

The operator console talks only to the limiter API at `http://127.0.0.1:8080`. Dev:

```bash
cd frontend && npm install && npm run dev
```

CORS allows `localhost:5173`. Keep `docker compose` (or the service on `:8080`) running while using the UI. Paste the admin token into the sidebar field to create, edit, or delete policies; it is kept in the browser's local storage.

Create a policy and check it:

```bash
curl -s -X POST http://localhost:8080/v1/policies \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer throttl-dev-admin-token' \
  -d '{
    "id": "payment-user-limit",
    "name": "Payment User Limit",
    "matching": { "scope": "USER", "conditions": { "api": "payment" } },
    "strategy": {
      "algorithm": "TOKEN_BUCKET",
      "configuration": { "capacity": 100, "refillRate": 10, "refillPeriod": "SECOND" }
    },
    "metadata": { "enabled": true, "priority": 10 }
  }'

curl -s -X POST http://localhost:8080/v1/check \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u123","tenantId":"acme","api":"payment","ip":"10.0.0.1"}'
```

Demo payment endpoint (returns HTTP 429 when limited):

```bash
curl -s -D - -X POST http://localhost:8090/payments -H 'X-User-Id: u123'
```

Direct instances for the distributed proof: `http://localhost:8081`, `:8082`, `:8083`.

### Redis with failover

```bash
docker compose -f docker-compose.yml -f docker-compose.ha.yml up --build -d
python3 scripts/failover-drill.py --mode kill    # or --mode pause
```

Adds a replica and three Sentinels; limiters find the primary through Sentinel and move to a new one within a second of promotion. Measured: a primary crash or hang costs about 5 to 6 seconds of degraded decisions, no HTTP errors, and no bucket state. See [docs/redis-ha.md](docs/redis-ha.md).

## Tests

```bash
./gradlew test
```

Unit tests cover policy validation, matching, refill, consumption, burst, multi-policy AND semantics, hash-slot co-location of one subject's keys, the local deny cache, and lease expiry. Integration tests use Testcontainers (PostgreSQL + Redis) for CRUD, admin-token enforcement, token-bucket behavior, all four scopes, per-policy bucket reset, 1000 concurrent checks against capacity 100, deny-cache hits without a Redis call, and leased policies that batch Redis calls without over-admitting under concurrency. `CrossSlotIntegrationTest` runs the cluster code path: a denial on one slot refunds the other, and 1000 concurrent USER+TENANT checks never overspend and leave every bucket at exactly `capacity - allowed`.

MVP acceptance (CRUD, refill, isolation, shared Redis, Prometheus):

```bash
docker compose down -v && docker compose up --build -d
./scripts/verify-mvp.sh
```

Checklist: [docs/mvp-verification.md](docs/mvp-verification.md). Lua vs GET/SET: [docs/lua-atomicity.md](docs/lua-atomicity.md).

## Load testing

```bash
k6 run load-test/k6-concurrency.js
k6 run load-test/k6-distributed.js
k6 run -e RATE=100 -e DURATION=30s load-test/k6-load.js
k6 run -e RATE=1000 -e DURATION=60s load-test/k6-load.js
k6 run -e RATE=5000 -e DURATION=30s load-test/k6-load.js
k6 run -e MODE=deny -e RATE=200 -e LIMIT=10 load-test/k6-hot-key.js
k6 run -e MODE=lease -e RATE=200 -e LEASE_TOKENS=50 load-test/k6-hot-key.js
```

`k6-hot-key.js` sends one subject at one bucket and reports Redis calls per check, scraped from every instance.

Measured results, including the before/after of connection pooling, are in [docs/benchmarks.md](docs/benchmarks.md). `load-test/io-isolation.py` reruns the shared vs pooled vs async comparison on a fresh stack.

## Failure testing

See [docs/failure-testing.md](docs/failure-testing.md) and, for Sentinel failover, [docs/redis-ha.md](docs/redis-ha.md). During a Redis outage each policy applies its own `failureMode`; policies without one fail closed by default (`THROTTL_FAIL_CLOSED=true`).

## Design decisions

See [docs/design-decisions.md](docs/design-decisions.md) for why Redis, Lua, PostgreSQL, token bucket and sliding window, Sentinel failover rather than Cluster, per-policy failure modes, the Redis clock, the hash-tagged key layout, the deny cache and leases, control-plane auth, and the split between policy data and algorithm code.

## Spring Boot client

The demo app uses a small client:

```java
RateLimitDecision decision = rateLimiterClient.check(
    RateLimitContext.builder()
        .userId("u123")
        .tenantId("acme")
        .api("payment")
        .ip("10.0.0.1")
        .build()
);
```

It POSTs to `/v1/check` and maps the JSON to `RateLimitDecision`.

## Stack

Java 21, Spring Boot 3.4, Gradle, Redis + Lua, PostgreSQL, Caffeine, Micrometer/Prometheus, Grafana, Testcontainers, k6, Docker Compose.
