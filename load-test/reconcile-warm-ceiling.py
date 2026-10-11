#!/usr/bin/env python3
"""Reconcile the original warm-ceiling sync baseline vs io-isolation sync-shared.

Runs the original warm-ceiling protocol twice on a fresh stack each time:
  sync + shareNativeConnection=true, pool disabled, 20s @ 200/s warmup,
  then 30s steps 400→4000, 2s idle between every step (the original protocol).

Also probes tomcat_threads_config_max_threads from Prometheus.
"""
from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "load-test"))

import importlib.util

_spec = importlib.util.spec_from_file_location("io_isolation", ROOT / "load-test" / "io-isolation.py")
_io = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_io)

RATES = _io.RATES
WARM_DUR = _io.WARM_DUR
WARM_RATE = _io.WARM_RATE
capture = _io.capture
compose_slim = _io.compose_slim
prepare_redis = _io.prepare_redis
quiet_host = _io.quiet_host

PROM = "http://127.0.0.1:9090/api/v1/query"
OUT = Path("/tmp/throttl-reconcile-warm-ceiling.json")
REPLICATES = 2
SYNC_ENV = {
    "THROTTL_CHECK_IO": "sync",
    "THROTTL_REDIS_SHARE_NATIVE": "true",
    "THROTTL_REDIS_POOL_ENABLED": "false",
}


def prom(q: str):
    url = PROM + "?" + urllib.parse.urlencode({"query": q})
    with urllib.request.urlopen(url, timeout=10) as r:
        data = json.load(r)
    result = data.get("data", {}).get("result", [])
    if not result:
        return None
    return float(result[0]["value"][1])


def tomcat_config():
    return {
        "max_threads_sum": prom("sum(tomcat_threads_config_max_threads)"),
        "max_threads_per_instance": prom("max(tomcat_threads_config_max_threads)"),
        "busy_sum": prom("sum(tomcat_threads_busy_threads)"),
        "current_sum": prom("sum(tomcat_threads_current_threads)"),
    }


def run_replicate(rep: int) -> dict:
    print(f"\n========== warm-ceiling reconcile rep {rep} ==========", flush=True)
    compose_slim(SYNC_ENV)
    prepare_redis()
    cfg = tomcat_config()
    print("tomcat config:", json.dumps(cfg, indent=2), flush=True)
    cases = []
    cases.append(capture("sync-shared-reconcile", WARM_RATE, WARM_DUR, f"rep{rep}-warmup"))
    for rate in RATES:
        cases.append(capture("sync-shared-reconcile", rate, "30s", f"rep{rep}-step"))
        print("idle 2s (original warm-ceiling protocol)", flush=True)
        time.sleep(2)
    return {"rep": rep, "tomcat": cfg, "loadavg_start": list(os.getloadavg()), "cases": cases}


def main():
    quiet_host()
    subprocess.check_call(["./gradlew", ":rate-limiter-service:bootJar", "-q"], cwd=ROOT)
    payload = {
        "when": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "protocol": (
            "Original warm-ceiling replay: sync-shared, 2s idle every step, "
            "no 22s high-rate pause, fresh stack per replicate"
        ),
        "replicates": [],
        "original_run_reference": {
            "2500": {"achieved": 2079, "http_p95_ms": 933, "busy": 400},
            "3000": {"achieved": 2990, "http_p95_ms": 20.5, "busy": 33},
        },
        "io_isolation_sync_shared": {
            "2500": {"achieved": 471, "http_p95_ms": 5804, "busy": 443},
            "3000": {"achieved": 273, "http_p95_ms": 8000, "busy": 7},
        },
    }
    for rep in range(1, REPLICATES + 1):
        payload["replicates"].append(run_replicate(rep))
        OUT.write_text(json.dumps(payload, indent=2))
    payload["loadavg_end"] = list(os.getloadavg())
    OUT.write_text(json.dumps(payload, indent=2))
    print("wrote", OUT, flush=True)


if __name__ == "__main__":
    main()
