#!/usr/bin/env python3
"""Fail the Redis primary under load and measure what clients see.

Requires the HA stack:

    docker compose -f docker-compose.yml -f docker-compose.ha.yml up --build -d
    python3 scripts/failover-drill.py              # crash: SIGKILL the primary
    python3 scripts/failover-drill.py --mode pause # hang: freeze it, then thaw

Steps: spend 40 of 100 tokens on a "state" bucket and wait until the replica
has it; send steady checks through nginx against a FAIL_CLOSED policy; fail
the primary; time Sentinel's promotion and the window in which checks were
degraded; confirm the state bucket survived; bring the old primary back and
confirm it rejoins as a replica while checks stay healthy.

Results: load-test/results/failover-<mode>.json
"""
import argparse
import json
import os
import subprocess
import threading
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ["docker", "compose", "-f", "docker-compose.yml", "-f", "docker-compose.ha.yml"]
BASE = os.environ.get("BASE_URL", "http://localhost:8080")
TOKEN = os.environ.get("THROTTL_ADMIN_TOKEN", "throttl-dev-admin-token")
NODES = {"redis", "redis-replica"}

LOAD_POLICY = {
    "id": "drill-load",
    "name": "drill-load",
    "matching": {"scope": "USER", "conditions": {"api": "drill-load"}},
    "strategy": {"algorithm": "TOKEN_BUCKET",
                 "configuration": {"capacity": 1000000, "refillRate": 1000000, "refillPeriod": "SECOND"}},
    "metadata": {"enabled": True, "priority": 1, "failureMode": "FAIL_CLOSED"},
}
STATE_POLICY = {
    "id": "drill-state",
    "name": "drill-state",
    "matching": {"scope": "USER", "conditions": {"api": "drill-state"}},
    "strategy": {"algorithm": "TOKEN_BUCKET",
                 "configuration": {"capacity": 100, "refillRate": 1, "refillPeriod": "HOUR"}},
    "metadata": {"enabled": True, "priority": 1, "failureMode": "FAIL_CLOSED"},
}


def compose(*args, check=True):
    return subprocess.run([*COMPOSE, *args], cwd=ROOT, capture_output=True, text=True, check=check).stdout.strip()


def redis_cli(service, *args):
    return compose("exec", "-T", service, "redis-cli", *args, check=False)


def sentinel_primary():
    for sentinel in ("sentinel-1", "sentinel-2", "sentinel-3"):
        out = compose("exec", "-T", sentinel, "redis-cli", "-p", "26379",
                      "sentinel", "get-master-addr-by-name", "throttl", check=False)
        host = out.splitlines()[0] if out else ""
        if host in NODES:
            return host
    return None


def http(method, path, body=None, admin=False):
    headers = {"Content-Type": "application/json"}
    if admin:
        headers["Authorization"] = f"Bearer {TOKEN}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=5) as res:
            raw = res.read()
            return res.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as err:
        return err.code, None


def check(api, user):
    return http("POST", "/v1/check", {"userId": user, "api": api})


class Load:
    """Steady checks from a few threads; every response is timestamped."""

    def __init__(self, workers=4):
        self.samples = []
        self.lock = threading.Lock()
        self.stopped = threading.Event()
        self.threads = [threading.Thread(target=self.run, args=(i,), daemon=True) for i in range(workers)]

    def run(self, worker):
        n = 0
        while not self.stopped.is_set():
            n += 1
            started = time.time()
            try:
                status, body = check("drill-load", f"load-{worker}-{n % 50}")
                ok = status == 200 and body is not None
                sample = (started, ok and body["allowed"], ok and bool(body.get("degraded")), status)
            except Exception:
                sample = (started, False, False, 0)
            with self.lock:
                self.samples.append(sample)
            time.sleep(0.02)

    def start(self):
        for t in self.threads:
            t.start()

    def stop(self):
        self.stopped.set()
        for t in self.threads:
            t.join()
        return sorted(self.samples)


def wait_for(predicate, timeout, interval=0.2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["kill", "pause"], default="kill")
    parser.add_argument("--down", type=float, default=15.0, help="seconds the old primary stays failed")
    args = parser.parse_args()

    primary = sentinel_primary()
    if primary is None:
        raise SystemExit("Sentinel does not report a primary; is the HA stack up?")
    replica = (NODES - {primary}).pop()
    print(f"primary={primary} replica={replica} mode={args.mode}")

    for policy in (LOAD_POLICY, STATE_POLICY):
        http("DELETE", f"/v1/policies/{policy['id']}", admin=True)
        status, _ = http("POST", "/v1/policies", policy, admin=True)
        if status != 201:
            raise SystemExit(f"creating {policy['id']} returned {status}")
    time.sleep(1)

    state_user = f"state-{int(time.time())}"
    for _ in range(40):
        check("drill-state", state_user)
    acked = redis_cli(primary, "WAIT", "1", "2000")
    print(f"spent 40/100 on drill-state; replicas acknowledged: {acked}")

    load = Load()
    load.start()
    time.sleep(5)

    failed_at = time.time()
    compose("kill" if args.mode == "kill" else "pause", primary)
    print(f"{args.mode} {primary}")
    promoted = wait_for(lambda: sentinel_primary() == replica and time.time(), timeout=60)
    if promoted is None:
        load.stop()
        raise SystemExit("Sentinel did not promote the replica within 60s")
    print(f"Sentinel promoted {replica} after {promoted - failed_at:.1f}s")

    time.sleep(max(0.0, args.down - (time.time() - failed_at)))
    compose("start" if args.mode == "kill" else "unpause", primary)
    recovered_at = time.time()
    rejoined = wait_for(lambda: "role:slave" in redis_cli(primary, "INFO", "replication")
                        and f"master_host:{replica}" in redis_cli(primary, "INFO", "replication")
                        and time.time(), timeout=60)
    print(f"old primary {primary} back after {args.down:.0f}s; "
          + (f"rejoined as replica in {rejoined - recovered_at:.1f}s" if rejoined else "did NOT rejoin"))
    time.sleep(10)
    samples = load.stop()

    _, body = check("drill-state", state_user)
    state_remaining = body["remaining"] if body else None

    before = [s for s in samples if s[0] < failed_at]
    after = [s for s in samples if s[0] >= failed_at]
    bad = [s for s in after if not s[1]]
    first_bad = bad[0][0] - failed_at if bad else None
    last_bad = bad[-1][0] - failed_at if bad else None
    tail = [s for s in samples if s[0] >= recovered_at + 5]
    tail_bad = sum(1 for s in tail if not s[1])
    summary = {
        "mode": args.mode,
        "old_primary": primary,
        "new_primary": replica,
        "sentinel_down_after_ms": 3000,
        "promotion_s": round(promoted - failed_at, 2),
        "checks_total": len(samples),
        "checks_before_failure_not_allowed": sum(1 for s in before if not s[1]),
        "checks_after_failure": len(after),
        "checks_not_allowed": len(bad),
        "checks_degraded": sum(1 for s in after if s[2]),
        "http_errors": sum(1 for s in after if s[3] != 200),
        "first_failed_check_s": round(first_bad, 2) if first_bad is not None else None,
        "last_failed_check_s": round(last_bad, 2) if last_bad is not None else None,
        "old_primary_rejoined_s": round(rejoined - recovered_at, 2) if rejoined else None,
        "checks_after_rejoin_not_allowed": tail_bad,
        "state_remaining_after_failover": state_remaining,
        "state_remaining_expected": 59,
    }
    out = ROOT / "load-test" / "results" / f"failover-{args.mode}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))

    for policy in (LOAD_POLICY, STATE_POLICY):
        http("DELETE", f"/v1/policies/{policy['id']}", admin=True)

    # clients must reach the new primary while the old one is still failed,
    # not when it comes back and Sentinel kills its connections
    recovered_before_return = last_bad is None or last_bad < args.down
    healthy = (state_remaining == 59 and rejoined is not None and tail_bad == 0
               and recovered_before_return
               and summary["checks_before_failure_not_allowed"] == 0)
    print("PASS" if healthy else "FAIL")
    raise SystemExit(0 if healthy else 1)


if __name__ == "__main__":
    main()
