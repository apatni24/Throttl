# Load test results

All numbers are from k6 and Prometheus on a MacBook running Docker Compose (3 limiter instances behind nginx, Redis 7, PostgreSQL 16). They describe this laptop, not production hardware.

## Connection pooling (2026-09-03)

The August throughput table below showed collapse near 1,000 req/s while Redis server-side execution stayed under 5 µs per call. The client was the bottleneck: Lettuce's default `shareNativeConnection=true` sends every command from a JVM over one TCP connection.

Protocol (`load-test/io-isolation.py`): slim stack (no Grafana, UI, or demo), 20 s warmup at 200/s, then 30 s steps from 400 to 4,000 req/s, fresh stack per variant.

| Variant | Collapse point | Achieved at 3,000 offered | HTTP p95 at 2,500 | Redis clients |
|---|---|---|---|---|
| sync, one shared connection (old default) | ~1,200 req/s | 273 req/s | 5,804 ms | 7 |
| sync, 32-connection pool (**shipped**) | ~3,000 req/s | 2,932 req/s | 141 ms | 96–124 |
| async (reactive Lettuce), shared | ~3,000 req/s | 2,983 req/s | 23 ms | 4 |

Pooling is a config change and moves the collapse point about 2.5×. Async reaches the same ceiling with fewer threads, but its policy cache-miss path still runs blocking JDBC, so it stays behind `THROTTL_CHECK_IO=async`.

**Reproducibility.** The original August run showed sync-shared "recovering" to 2,990 req/s at 20 ms p95 at 3,000 offered. Two fresh-stack replays of that protocol (`load-test/reconcile-warm-ceiling.py`) did not reproduce it:

| Run | 1,500 | 2,000 | 2,500 | 3,000 |
|---|---|---|---|---|
| Original run | 11 ms · 1,491/s | 12 ms · 1,984/s | 933 ms · 2,079/s | 20 ms · 2,990/s |
| Replay 1 | 6.5 ms · 1,496/s | 1.8 s · 1,431/s | 517 ms · 2,347/s | 809 ms · 2,627/s |
| Replay 2 | 5.2 ms · 1,499/s | 494 ms · 1,948/s | 101 ms · 2,473/s | 88 ms · 2,968/s, 843 dropped iterations |

Post-collapse "recovery" is queue drain, not health. Shared-sync is reliable through ~1,500 req/s here.

The pooled variant also showed one 360 ms p95 step at 1,200 req/s in one of three fresh-stack replicates, and one replicate collapsed at 1,500. That is laptop variance at the knee, not a fixed warm-up cost.

## Baseline (2026-08-20, one shared Redis connection)

### Concurrency (1000 overlapping checks, capacity 100)

Policy refill is 1/hour so refill is negligible during the burst.

| Run | Allowed | Denied | Overspend? |
|---|---|---|---|
| 1 | 100 | 900 | no |
| 2 | 100 | 900 | no |
| 3 | 100 | 900 | no |

Lua never allowed 101+. Naive GET/SET experiment: [lua-atomicity.md](lua-atomicity.md).

### Distributed (1000 requests round-robin to :8081/:8082/:8083)

| Allowed globally | Denied | Per-instance 100? |
|---|---|---|
| 100 | 900 | no — one shared Redis bucket |

### Throughput

| Target | Duration | Requests | Achieved req/s | k6 p95 | HTTP error rate |
|---|---|---|---|---|---|
| 100/s | 30s | 3002 | 99.8 | 11.9 ms | 0% |
| 1,000/s | 60s | 42396 | 647 | 16.5 s | 41.2% |
| 5,000/s | 30s | 32654 | 530 | 46.0 s | 90.1% |

k6 2.x did not populate `p(50)`/`p(99)` in the custom summary writer; p95 is the recorded percentile. After the stress window Prometheus reported check-latency p95 ≈ **7.6 s** (`histogram_quantile` on `rate_limit_check_latency_seconds_bucket`).

**Reading:** 100 req/s is comfortable. Around 1,000 req/s this laptop Compose stack saturates (k6 hit 2000 VUs, nginx/Tomcat EOFs). 5,000 req/s does not increase goodput; it queues and fails. That is the degradation point for this environment, not a claim about production hardware.

### Resource sample (idle-ish, after 100/s)

From `docker stats --no-stream`:

| Container | CPU | Memory |
|---|---|---|
| limiter-1/2/3 | ~0.6–0.9% | ~390–420 MiB |
| redis | ~0.9% | ~13 MiB |
| postgres | ~0.03% | ~88 MiB |

After 5,000/s stress, limiters were ~540–566 MiB.

### Commands

```bash
k6 run load-test/k6-concurrency.js
k6 run load-test/k6-distributed.js
k6 run -e RATE=100 -e DURATION=30s load-test/k6-load.js
k6 run -e RATE=1000 -e DURATION=60s load-test/k6-load.js
k6 run -e RATE=5000 -e DURATION=30s load-test/k6-load.js
docker stats --no-stream
```
