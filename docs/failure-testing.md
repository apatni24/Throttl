# Failure testing

These scenarios assume `docker compose up` is already running.

## Kill a rate-limiter instance

```bash
docker compose stop limiter-2
```

Expected: nginx continues to route to `limiter-1` and `limiter-3`. Shared Redis state is unchanged. `/v1/check` keeps returning decisions.

Restore:

```bash
docker compose start limiter-2
```

## Restart Redis

```bash
docker compose restart redis
```

While Redis is down, each policy applies its `failureMode` (see below). After Redis returns, buckets start fresh (in-memory Redis has no persistence in this Compose file), so every key can admit a full burst once. Document that outcome rather than assuming tokens survive.

## Redis primary failover (Sentinel)

With `docker-compose.ha.yml`, killing or freezing the primary promotes the replica:

```bash
docker compose -f docker-compose.yml -f docker-compose.ha.yml up --build -d
python3 scripts/failover-drill.py --mode kill
python3 scripts/failover-drill.py --mode pause
```

Expected: about 5 to 6 seconds of degraded decisions (each policy's failure mode), no HTTP errors, bucket state preserved on the new primary, and the old primary rejoining as a replica. Measured results and caveats: [redis-ha.md](redis-ha.md).

## PostgreSQL unavailable

```bash
docker compose stop postgres
```

Expected: cached policies (Caffeine, 60s TTL) continue to serve `/v1/check` until the cache expires. Policy CRUD fails. After TTL expiry, enabled-policy loads fail and no per-policy failure mode is known, so the service default applies: `rejectedBy: policy-catalog-unavailable` with `THROTTL_FAIL_CLOSED=true`, allow with `degraded: true` otherwise.

Restore:

```bash
docker compose start postgres
```

## Redis unavailable (per-policy failure modes)

```bash
docker compose stop redis
curl -s -X POST http://localhost:8080/v1/check \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u123","tenantId":"acme","api":"payment","ip":"10.0.0.1"}'
```

Expected body for a policy with no explicit mode (service default `THROTTL_FAIL_CLOSED=true`) or `FAIL_CLOSED`:

```json
{"allowed":false,"remaining":0,"retryAfterMs":0,"rejectedBy":"redis-unavailable",
 "evaluations":[{"policyId":"payment-user-limit","status":"FAILED_CLOSED","remaining":0,"retryAfterMs":0}],
 "degraded":true}
```

A `FAIL_OPEN` policy returns `allowed: true` with status `FAILED_OPEN`. A `LOCAL_FALLBACK` policy admits `limit / 3` per instance in this Compose file, then denies with `rejectedBy` set to the policy and status `LOCAL_REJECTED`. The first check after the outage starts waits for the Redis timeout once; later checks skip Redis while the circuit breaker is open (`redis_circuit_open` gauge, `redis_short_circuited_total`) and return in well under a millisecond of limiter time.
