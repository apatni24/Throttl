# MVP verification

The MVP is complete only if these six claims hold:

1. Policies work (CRUD, matching, persistence in PostgreSQL).
2. Token bucket works (consume, refill, burst, isolation).
3. Concurrent requests cannot double-spend tokens (Lua).
4. Multiple applicable policies must all pass.
5. Multiple limiter instances share one Redis limit (global, not per process).
6. The system performs reasonably under load, with Prometheus/Grafana showing real traffic.

Policy configuration lives in PostgreSQL. Token counts live in Redis. Changing a policy **deletes** `rl:{*}:<policyId>` so leftover tokens are not mixed with a new capacity. Policy-cache invalidation is a Redis generation counter so all three instances drop stale Caffeine entries.

Ran 2026-08-20: `docker compose down -v && docker compose up --build -d` then `./scripts/verify-mvp.sh`, k6, and failure drills.

```bash
docker compose down -v
docker compose up --build -d
./scripts/verify-mvp.sh
k6 run load-test/k6-concurrency.js   # repeat 3x
k6 run load-test/k6-distributed.js
k6 run -e RATE=100 -e DURATION=30s load-test/k6-load.js
k6 run -e RATE=1000 -e DURATION=60s load-test/k6-load.js
k6 run -e RATE=5000 -e DURATION=30s load-test/k6-load.js
```

Health: `GET http://localhost:8080/actuator/health` → `status: UP` with PostgreSQL + Redis components.

## Functional

- [x] Policy CRUD (`test-user-policy` 5 → 10 → persist)
- [x] Token consumption (5 allows, 6th deny, remaining 4…0)
- [x] Token refill (~1s → remaining 0 after consume; ~3s → remaining 2)
- [x] Burst handling (5 then deny)
- [x] User isolation (user-A exhausted, user-B allowed)
- [x] API isolation (payment exhausted, accounts untouched)
- [x] Multiple policies (user 5 + api 10; deny `rejectedBy=multi-user`)

`./scripts/verify-mvp.sh`: **38 passed, 0 failed** (2026-08-20). After the Redis clock, hash-tagged keys, and control-plane auth: **41 passed, 0 failed** (2026-10-11; the three new checks are the auth gate).

## Control plane

- [x] `POST /v1/policies` without a token → 401
- [x] `DELETE` with a wrong token → 401
- [x] `GET /v1/policies` and `POST /v1/check` stay open
- [x] Demo app seeds its policies with `THROTTL_ADMIN_TOKEN`

## Concurrency

- [x] 1,000 concurrent requests
- [x] No token double-spending
- [x] Repeated concurrency test (100 / 100 / 100 allowed)

## Distributed

- [x] 3 rate limiter instances (`8081` `8082` `8083`)
- [x] Shared Redis state (`HGETALL rl:{USER:user}:policy` has `tokens` + `lastRefillTimestamp`)
- [x] Global limit maintained (verify script 300 req → 100 allow; k6 1000 req → 100 allow; re-run 2026-10-11 with the same result)
- [x] Refill timestamps come from Redis `TIME`, not the limiter JVM
- [x] Cluster code path (`THROTTL_REDIS_CLUSTER=true`): cross-slot denial refunds the other slot; 1000 concurrent USER+TENANT checks never overspend and conserve tokens exactly (`CrossSlotIntegrationTest`, 5/5 repeated runs)

## Observability

- [x] Prometheus scraping `limiter-1/2/3`
- [x] Grafana dashboard `throttl-rate-limiter` provisioned
- [x] Request metrics (`sum(rate_limit_checks_total)` observed 40574)
- [x] Rejection metrics (`sum(rate_limit_rejected_total)` observed 8224)
- [x] Latency metrics (Prometheus p95 during/after stress ≈ 7.6s)
- [x] Redis metrics (`redis_operation_latency_seconds`)
- [x] Cache HIT/MISS logs + `policy_cache_hits_total`

## Performance

- [x] 100 req/sec — 99.8/s, p95 11.9ms, 0% errors
- [x] 1,000 req/sec — achieved ~647/s, 41% errors (saturation)
- [x] Stress 5,000/s — ~530/s goodput, 90% errors
- [x] Benchmark recorded in [benchmarks.md](benchmarks.md)

## Failure

- [x] Rate limiter instance failure — `:8083` kept serving after `limiter-2` stop (`allowed: true`). nginx `:8080` can stall if it still balances to a dead upstream; hit instance ports for this drill.
- [x] Redis restart — without a short client timeout the check can hang; `spring.data.redis.timeout=500ms` is configured so the next image build fail-closes (`rejectedBy: redis-unavailable`).
- [x] PostgreSQL failure — with policies cached, `:8081` still returned `allowed: true` while Postgres was stopped.

Lua vs GET/SET: [lua-atomicity.md](lua-atomicity.md). Failure runbook: [failure-testing.md](failure-testing.md).
