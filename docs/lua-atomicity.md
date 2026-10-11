# Why Lua

Token consume is several Redis steps: read tokens, refill, check, decrement, write. If those steps run as separate Redis commands from Java, two checkers can both observe the last token and both allow.

## Experiment

`NaiveRedisTokenBucket` (test-only) does:

```text
GET tokens
if tokens > 0
  SET tokens-1
  ALLOW
```

`RedisRateLimiter` (production) runs the same math inside `redis/rate_limit.lua` with `EVAL`, so Redis executes the whole cycle atomically.

Run both against the same Redis:

```bash
./gradlew :rate-limiter-service:test \
  --tests com.throttl.ratelimiter.engine.NaiveRedisTokenBucketRaceTest \
  --tests com.throttl.ratelimiter.RateLimiterIntegrationTest.concurrentRequestsDoNotDoubleSpendTokens
```

Expected:

| Implementation | 1000 concurrent consumes, capacity 100 |
|---|---|
| GET → Java → SET | Often **more than 100** allows (double-spend) |
| Lua | **Exactly 100** allows |

The naive test asserts `allowed >= 100`. If a given run does not overspend, re-run; races are timing-dependent. The Lua integration test asserts `allowed == 100` every time.

The naive class is not on the request path and must not be wired into production.
