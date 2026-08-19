# Load test results

Recorded on 2026-08-20 against local Docker Compose (3 limiter instances behind nginx, Redis 7, PostgreSQL 16) on a MacBook. Numbers are from k6 and Prometheus, not estimates.

## Concurrency (1000 overlapping checks, capacity 100)

Policy refill is 1/hour so refill is negligible during the burst.

| Run | Allowed | Denied | Overspend? |
|---|---|---|---|
| 1 | 100 | 900 | no |
| 2 | 100 | 900 | no |
| 3 | 100 | 900 | no |

Lua never allowed 101+. Naive GET/SET experiment: [lua-atomicity.md](lua-atomicity.md).

## Distributed (1000 requests round-robin to :8081/:8082/:8083)

| Allowed globally | Denied | Per-instance 100? |
|---|---|---|
| 100 | 900 | no — one shared Redis bucket |

## Throughput

| Target | Duration | Requests | Achieved req/s | k6 p95 | HTTP error rate |
|---|---|---|---|---|---|
| 100/s | 30s | 3002 | 99.8 | 11.9 ms | 0% |
| 1,000/s | 60s | 42396 | 647 | 16.5 s | 41.2% |
| 5,000/s | 30s | 32654 | 530 | 46.0 s | 90.1% |

k6 2.x did not populate `p(50)`/`p(99)` in the custom summary writer; p95 is the recorded percentile. After the stress window Prometheus reported check-latency p95 ≈ **7.6 s** (`histogram_quantile` on `rate_limit_check_latency_seconds_bucket`).

**Reading:** 100 req/s is comfortable. Around 1,000 req/s this laptop Compose stack saturates (k6 hit 2000 VUs, nginx/Tomcat EOFs). 5,000 req/s does not increase goodput; it queues and fails. That is the degradation point for this environment, not a claim about production hardware.

## Resource sample (idle-ish, after 100/s)

From `docker stats --no-stream`:

| Container | CPU | Memory |
|---|---|---|
| limiter-1/2/3 | ~0.6–0.9% | ~390–420 MiB |
| redis | ~0.9% | ~13 MiB |
| postgres | ~0.03% | ~88 MiB |

After 5,000/s stress, limiters were ~540–566 MiB.

## Commands

```bash
k6 run load-test/k6-concurrency.js
k6 run load-test/k6-distributed.js
k6 run -e RATE=100 -e DURATION=30s load-test/k6-load.js
k6 run -e RATE=1000 -e DURATION=60s load-test/k6-load.js
k6 run -e RATE=5000 -e DURATION=30s load-test/k6-load.js
docker stats --no-stream
```
