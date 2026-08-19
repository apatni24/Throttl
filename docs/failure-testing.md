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

While Redis is down, checks fail closed (`allowed: false`, `rejectedBy: redis-unavailable`) because `THROTTL_FAIL_CLOSED=true`. After Redis returns, buckets start empty (in-memory Redis has no persistence in this Compose file). Document that outcome rather than assuming tokens survive.

## PostgreSQL unavailable

```bash
docker compose stop postgres
```

Expected: cached policies (Caffeine, 60s TTL) continue to serve `/v1/check` until the cache expires. Policy CRUD fails. After TTL expiry, enabled-policy loads fail; checks then error or fail closed depending on the exception path.

Restore:

```bash
docker compose start postgres
```

## Redis unavailable (fail closed)

```bash
docker compose stop redis
curl -s -X POST http://localhost:8080/v1/check \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u123","tenantId":"acme","api":"payment","ip":"10.0.0.1"}'
```

Expected body:

```json
{"allowed":false,"remaining":0,"retryAfterMs":0,"rejectedBy":"redis-unavailable"}
```

This protects downstream services when the limiter cannot make a reliable decision. Fail-open can be enabled later via `THROTTL_FAIL_CLOSED=false`.
