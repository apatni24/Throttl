# Throttl — Distributed Rate Limiter

A production-style rate-limiting service that multiple application instances can call for low-latency, shared decisions.

PostgreSQL stores policies. Redis stores token-bucket state. A Lua script makes refill-and-consume atomic. Several limiter processes sit in front of the same Redis, so the limit is global.

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
                    │       → Token Bucket    │
                    │       → Policy Cache    │
                    └──────┬─────────┬────────┘
                           │         │
                           ↓         ↓
                      PostgreSQL   Redis + Lua
                       Policies    Buckets

                    Prometheus → Grafana
```

Local Compose runs three limiter instances behind nginx. All three share Redis.

## Policy model

A policy has four parts:

| Part | Role |
|---|---|
| Identity | `id`, `name` |
| Matching | `scope` (`USER`, `TENANT`, `API`, `IP`) and `conditions` |
| Strategy | `algorithm` + algorithm-specific `configuration` |
| Metadata | `enabled`, `priority`, timestamps |

MVP algorithm: **TOKEN_BUCKET** only. Capacity, refill rate, and refill period live inside `strategy.configuration`, not as top-level columns.

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

## Token bucket

A bucket starts full (`capacity` tokens). Each allowed request consumes one token. Tokens refill at `refillRate` per `refillPeriod`, capped at capacity. When empty, the check is denied and `retryAfterMs` is the time until the next refill.

## Redis and Lua

Runtime keys look like `rl:{policyId}:{scopeValue}` and store a hash of `tokens` and `lastRefillTimestamp`. The Lua script reads, refills, consumes, and writes in one Redis execution so two concurrent checks cannot both spend the last token.

## PostgreSQL

PostgreSQL is the source of truth for **what the policy is**. It is not used for request-time token counts. `conditions` and `configuration` are JSONB so new algorithms do not require schema changes.

## Caching

Enabled policies are cached in Caffeine for 60 seconds (configurable). Writes invalidate the cache. Metrics: `policy_cache_hits_total`, `policy_cache_misses_total`.

## Observability

- Prometheus scrape: `http://localhost:8080/actuator/prometheus` (via nginx) or each instance on the Docker network
- Grafana: `http://localhost:3000` (anonymous viewer, or `admin` / `admin`)
- Dashboard: **Distributed Rate Limiter** (requests/sec, allow vs reject, latency, Redis latency, cache hit rate, top rejected policies)

## API

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

Create a policy and check it:

```bash
curl -s -X POST http://localhost:8080/v1/policies \
  -H 'Content-Type: application/json' \
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

## Tests

```bash
./gradlew test
```

Unit tests cover policy validation, matching, refill, consumption, burst, and multi-policy AND semantics. Integration tests use Testcontainers (PostgreSQL + Redis) for CRUD, token-bucket behavior, all four scopes, and 1000 concurrent checks against capacity 100.

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
```

Record measured numbers in [docs/benchmarks.md](docs/benchmarks.md). Do not invent results.

## Failure testing

See [docs/failure-testing.md](docs/failure-testing.md). Redis outages fail closed by default (`THROTTL_FAIL_CLOSED=true`).

## Design decisions

See [docs/design-decisions.md](docs/design-decisions.md) for why Redis, Lua, PostgreSQL, token bucket, a single Redis node, fail-closed, and the split between policy data and algorithm code.

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
