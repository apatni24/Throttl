# Redis high availability

The default Compose file runs one Redis node: simple, and enough to prove shared state across limiters. `docker-compose.ha.yml` adds automatic failover: one primary, one replica, and three Sentinels with a quorum of 2.

```bash
docker compose -f docker-compose.yml -f docker-compose.ha.yml up --build -d
python3 scripts/failover-drill.py               # crash: SIGKILL the primary
python3 scripts/failover-drill.py --mode pause  # hang: freeze the primary, thaw it later
```

## How limiters find the primary

Limiters do not get a Redis host. They get `SPRING_DATA_REDIS_SENTINEL_MASTER=throttl` and the three Sentinel addresses, and Lettuce asks Sentinel for the primary when it opens a connection. Every node announces its Compose hostname (`replica-announce-ip`, `sentinel announce-hostnames`), so addresses survive container restarts and a demoted primary rejoins under its own name.

Sentinel marks the primary down after 3 s without a reply (`down-after-milliseconds 3000`). Two Sentinels must agree before one of them promotes the replica. Lower values fail over faster but risk failing over on a GC pause or a slow `BGSAVE`.

## The hang problem, and the fix

The first drill against a frozen primary (`docker pause`) failed. Sentinel promoted the replica after 5.1 s, but checks stayed degraded for **26.3 s**:

- Lettuce consults Sentinel only when it opens a connection.
- A frozen process does not close its sockets, and the kernel keeps ACKing TCP, so the limiters' pooled connections stayed open to the old primary. Every command timed out, and nothing reconnected.
- Clients moved over only when the old primary was thawed (at 15 s) and Sentinel demoted it. As part of demotion Sentinel kills that node's client connections, which forced the reconnect.

If the old primary had stayed frozen (a hung host, a one-way partition), the limiters would have stayed on it indefinitely. TCP keepalive and `TCP_USER_TIMEOUT` do not catch this, because the frozen node's kernel still acknowledges packets.

`SentinelFailoverWatcher` fixes it the way Jedis's Sentinel pool does. Each limiter asks Sentinel for the primary every `THROTTL_SENTINEL_POLL_MS` (1000 ms) on its own small connection. When the answer changes, it rebuilds the Lettuce connection factory, which closes every pooled connection; new ones resolve the new primary.

- A Sentinel outage is not a failover: the last known primary is kept.
- Checks that race the rebuild fail like any other Redis error and get their policy's failure mode.
- Each failover is counted in `redis_failovers_observed_total`, and `GET /v1/system/info` reports `redisTopology` and `redisPrimary`.

## Drill results

Four threads send steady checks through nginx against a `FAIL_CLOSED` policy, so every check that could not reach Redis shows up as a denial. Before the failure, 40 of 100 tokens are spent on a separate bucket, and `WAIT 1` confirms the replica has them. The old primary is restored after 15 s.

| Failure | Promotion | Checks degraded | Last degraded check | HTTP errors | State after failover | Old primary |
|---|---|---|---|---|---|---|
| Crash (`kill`), before the watcher | 4.7 s | 440 | 5.7 s | 0 | 59 remaining (expected 59) | rejoined as replica |
| Hang (`pause`), before the watcher | 5.1 s | 1,740 | **26.3 s** | 0 | 59 | rejoined as replica |
| Hang (`pause`), with the watcher | 5.1 s | 651 | **6.2 s** | 0 | 59 | rejoined as replica |
| Crash (`kill`), with the watcher | 4.7 s | 544 | 5.6 s | 0 | 59 | rejoined as replica |

So an unplanned primary loss costs about 5 to 6 seconds of degraded decisions: the 3 s detection, the Sentinel election, and at most one watcher poll. No requests failed at the HTTP level. Bucket state survived, and nothing failed after the old primary returned.

Single runs on a laptop, so treat these as orders of magnitude. The drill exits non-zero if state is lost, if checks fail after the old primary rejoins, or if clients only recover when the old primary comes back.

## What the failover window means for decisions

For those ~5 seconds, Redis cannot answer, and each policy decides by its `failureMode` exactly as in a full outage (see [design-decisions.md](design-decisions.md#what-happens-when-redis-fails)). `FAIL_CLOSED` denies, `FAIL_OPEN` allows, and `LOCAL_FALLBACK` enforces `limit / instances` in process. The circuit breaker keeps degraded checks fast instead of each waiting out the 500 ms timeout.

Local state keeps working through the window:

- **Deny cache.** Denials cached before the failure are still served. If the failover lost writes (below), the new primary's bucket may be fuller than the cache assumes, so the cache errs toward denial for at most its 1 s cap.
- **Leases.** Units leased before the failure keep being spent. They were consumed on the old primary, and if that consumption replicated, the new primary already counts them.

## What a failover can lose

Redis replication is asynchronous. A script the primary acknowledged but had not yet sent to the replica is lost on promotion. For a rate limiter that means a few buckets are slightly fuller than they should be: **over-admission bounded by replication lag × request rate** on the affected keys, typically milliseconds of traffic. Policy writes do not live in Redis (PostgreSQL is the source of truth), and the `policy:generation` counter only has to differ for caches to refresh, so a lost increment at worst delays one cache refresh until the TTL.

Options considered and not enabled by default:

- **`WAIT` after every check.** It makes the primary wait for the replica, adding a round trip to every request to protect a few milliseconds of counts. It is also not allowed inside a Lua script.
- **`min-replicas-to-write 1`.** It stops an isolated primary from accepting writes the cluster will discard (split brain). With a single replica, losing that replica would then stop all writes, turning a replica failure into a limiter outage. With two or more replicas it becomes the right setting.

## Clock after a failover

"Now" comes from Redis `TIME` on whichever node is primary. A new primary whose clock is behind could make elapsed time negative:

- **Token bucket.** Elapsed time is clamped at 0, so the bucket waits rather than refilling.
- **Sliding window.** A stored window start ahead of "now" is kept rather than rolled backwards, so a clock step cannot reset a window and re-admit a full quota.

## Persistence

This Compose file uses Redis defaults (periodic RDB snapshots inside the container). The dangerous case for any Sentinel setup is a primary that restarts with an empty dataset faster than Sentinel notices (under 3 s here). It is still primary, so the replica re-syncs from it and copies the empty state. In production, enable AOF on the primary, or do not auto-restart it faster than `down-after-milliseconds`.

## Why Sentinel and not Cluster?

Sentinel solves availability. Cluster solves capacity. One Redis primary handles on the order of 100k script calls per second (the `redis-benchmark` of `rate_limit.lua` on this laptop: 87k/s, 7.6 µs per call), well beyond what this stack has needed. The hash-tagged key layout and the per-slot path with refunds (`THROTTL_REDIS_CLUSTER=true`) are already in place, so moving to Cluster when one primary is the measured limit is a deployment change. Cluster also brings its own failover (replica promotion per shard), with the same async-replication caveat.
