#!/usr/bin/env python3
"""Re-run sync-pooled at 1000→1500 only, 3 replicates, to test 1200 spike."""
from __future__ import annotations

import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

import importlib.util

_spec = importlib.util.spec_from_file_location("io_isolation", ROOT / "load-test" / "io-isolation.py")
_io = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_io)

capture = _io.capture
compose_slim = _io.compose_slim
prepare_redis = _io.prepare_redis
quiet_host = _io.quiet_host

OUT = Path("/tmp/throttl-pooled-1200-spike.json")
RATES = [1000, 1200, 1500]
REPLICATES = 3
POOLED_ENV = {
    "THROTTL_CHECK_IO": "sync",
    "THROTTL_REDIS_SHARE_NATIVE": "false",
    "THROTTL_REDIS_POOL_ENABLED": "true",
    "THROTTL_REDIS_POOL_MAX_ACTIVE": "32",
}


def run_rep(rep: int) -> list[dict]:
    print(f"\n========== sync-pooled spike check rep {rep} ==========", flush=True)
    compose_slim(POOLED_ENV)
    prepare_redis()
    cases = []
    cases.append(capture("sync-pooled", 200, "20s", f"rep{rep}-warmup"))
    for rate in RATES:
        cases.append(capture("sync-pooled", rate, "30s", f"rep{rep}-step"))
        time.sleep(2)
    return cases


def main():
    quiet_host()
    subprocess.check_call(["./gradlew", ":rate-limiter-service:bootJar", "-q"], cwd=ROOT)
    payload = {"when": time.strftime("%Y-%m-%dT%H:%M:%S"), "replicates": []}
    for rep in range(1, REPLICATES + 1):
        payload["replicates"].append({"rep": rep, "cases": run_rep(rep)})
        OUT.write_text(json.dumps(payload, indent=2))
    print("wrote", OUT, flush=True)


if __name__ == "__main__":
    main()
