#!/usr/bin/env python3
"""Warm slim-stack I/O isolation: shared Lettuce vs pooled connections vs async check.

Protocol matches the original warm-ceiling run: quiet host, no Grafana/UI/demo, nginx keepalive 32,
20s warmup at 200/s, then 30s steps 400→4000, maxVUs=1000.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROM = "http://127.0.0.1:9090/api/v1/query"
OUT = Path("/tmp/throttl-io-isolation.json")
REDIS = "throttl-redis-1"
PORTS = (8081, 8082, 8083)
SERVICES = (
    "postgres",
    "redis",
    "limiter-1",
    "limiter-2",
    "limiter-3",
    "rate-limiter",
    "prometheus",
)
RATES = [400, 500, 600, 700, 800, 900, 1000, 1200, 1500, 2000, 2500, 3000, 4000]
WARM_RATE = 200
WARM_DUR = "20s"
DURATION = "30s"
MAX_VUS = "1000"
PRE_VUS = "100"
GAUGE_PREFIXES = (
    "tomcat_threads_busy_threads",
    "tomcat_threads_current_threads",
    "process_cpu_usage",
)
QUERIES = {
    "checks_rps": "sum(rate(rate_limit_checks_total[30s]))",
    "check_p50": "histogram_quantile(0.50, sum by (le) (rate(rate_limit_check_latency_seconds_bucket[30s])))",
    "check_p95": "histogram_quantile(0.95, sum by (le) (rate(rate_limit_check_latency_seconds_bucket[30s])))",
    "gen_get_p50": "histogram_quantile(0.50, sum by (le) (rate(policy_generation_get_latency_seconds_bucket[30s])))",
    "gen_get_p95": "histogram_quantile(0.95, sum by (le) (rate(policy_generation_get_latency_seconds_bucket[30s])))",
    "lua_p50": "histogram_quantile(0.50, sum by (le) (rate(redis_operation_latency_seconds_bucket[30s])))",
    "lua_p95": "histogram_quantile(0.95, sum by (le) (rate(redis_operation_latency_seconds_bucket[30s])))",
    "tomcat_busy": "sum(tomcat_threads_busy_threads)",
    "tomcat_current": "sum(tomcat_threads_current_threads)",
}

VARIANTS = [
    {
        "name": "sync-shared",
        "env": {
            "THROTTL_CHECK_IO": "sync",
            "THROTTL_REDIS_SHARE_NATIVE": "true",
            "THROTTL_REDIS_POOL_ENABLED": "false",
        },
    },
    {
        "name": "sync-pooled",
        "env": {
            "THROTTL_CHECK_IO": "sync",
            "THROTTL_REDIS_SHARE_NATIVE": "false",
            "THROTTL_REDIS_POOL_ENABLED": "true",
            "THROTTL_REDIS_POOL_MAX_ACTIVE": "32",
        },
    },
    {
        "name": "async-shared",
        "env": {
            "THROTTL_CHECK_IO": "async",
            "THROTTL_REDIS_SHARE_NATIVE": "true",
            "THROTTL_REDIS_POOL_ENABLED": "false",
        },
    },
]


def fnum(v):
    try:
        x = float(v)
        return None if x != x else x
    except (TypeError, ValueError):
        return None


def prom(q: str):
    url = PROM + "?" + urllib.parse.urlencode({"query": q})
    with urllib.request.urlopen(url, timeout=10) as r:
        data = json.load(r)
    result = data.get("data", {}).get("result", [])
    if not result:
        return None
    return fnum(result[0]["value"][1])


def snapshot():
    out = {}
    for k, q in QUERIES.items():
        try:
            out[k] = prom(q)
        except Exception as e:
            out[k] = None
            out[k + "_err"] = str(e)
    return out


def wait_healthy(url: str, timeout=120):
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as r:
                if r.status == 200:
                    return
        except Exception as e:
            last = str(e)
        time.sleep(2)
    raise SystemExit(f"health timeout {url}: {last}")


def jar_path() -> Path:
    jars = [p for p in (ROOT / "rate-limiter-service" / "build" / "libs").glob("*.jar") if "plain" not in p.name]
    if not jars:
        raise SystemExit("bootJar not found")
    return jars[0]


def inject_jar():
    jar = jar_path()
    for i in (1, 2, 3):
        name = f"throttl-limiter-{i}-1"
        subprocess.check_call(["docker", "cp", str(jar), f"{name}:/app/app.jar"])
        subprocess.check_call(["docker", "restart", name])


def redis_cli(*args: str) -> str:
    return subprocess.check_output(["docker", "exec", REDIS, "redis-cli", *args], text=True, stderr=subprocess.STDOUT)


def parse_info(text: str) -> dict:
    out = {}
    for line in text.splitlines():
        if not line or line.startswith("#") or ":" not in line:
            continue
        k, v = line.split(":", 1)
        out[k.strip()] = v.strip()
    return out


def parse_cmdstat(raw: str) -> dict:
    out = {}
    for cmd, body in re.findall(r"cmdstat_(\w+):(\S+)", raw):
        fields = {}
        for part in body.split(","):
            if "=" not in part:
                continue
            fk, fv = part.split("=", 1)
            try:
                fields[fk] = float(fv) if "." in fv else int(fv)
            except ValueError:
                fields[fk] = fv
        out[cmd] = fields
    return out


def parse_cpu(pct: str):
    try:
        return float(pct.replace("%", "").strip())
    except ValueError:
        return 0.0


def scrape_actuators() -> dict:
    totals = {k: 0.0 for k in GAUGE_PREFIXES}
    ok = 0
    for port in PORTS:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/prometheus", timeout=1.5) as r:
                text = r.read().decode()
            ok += 1
        except Exception:
            continue
        for line in text.splitlines():
            if line.startswith("#"):
                continue
            for prefix in GAUGE_PREFIXES:
                if line.startswith(prefix + "{") or line.startswith(prefix + " "):
                    totals[prefix] += float(line.split()[-1])
                    break
    totals["_ok"] = float(ok)
    return totals


def docker_stats_row():
    raw = subprocess.check_output(
        ["docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}"],
        text=True,
        stderr=subprocess.DEVNULL,
    )
    redis_cpu = limiter_cpu = nginx_cpu = 0.0
    for line in raw.strip().splitlines():
        name, cpu = line.split("\t")
        val = parse_cpu(cpu)
        if "throttl-redis" in name:
            redis_cpu = val
        elif "throttl-limiter-" in name:
            limiter_cpu += val
        elif "throttl-rate-limiter-1" in name:
            nginx_cpu = val
    return {"redis_cpu": redis_cpu, "limiter_cpu": limiter_cpu, "nginx_cpu": nginx_cpu}


def redis_light():
    info = parse_info(redis_cli("INFO", "stats") + redis_cli("INFO", "cpu") + redis_cli("INFO", "clients"))
    cmdstats = parse_cmdstat(redis_cli("INFO", "commandstats"))
    get = cmdstats.get("get") or {}
    evalsha = cmdstats.get("evalsha") or {}
    return {
        "ops_per_sec": int(info.get("instantaneous_ops_per_sec") or 0),
        "connected_clients": int(info.get("connected_clients") or 0),
        "blocked_clients": int(info.get("blocked_clients") or 0),
        "get_usec_per_call": float(get.get("usec_per_call") or 0),
        "evalsha_usec_per_call": float(evalsha.get("usec_per_call") or 0),
    }


class Window:
    def __init__(self):
        self.lock = threading.Lock()
        self.stop = False
        self.samples = []
        self.max_busy = 0.0
        self.max_current = 0.0
        self.max_jvm_cpu = 0.0
        self.max_redis_cpu = 0.0
        self.max_get_usec = 0.0
        self.max_evalsha_usec = 0.0
        self.max_clients = 0
        self.max_blocked = 0
        self.max_ops = 0

    def tick(self, t0: float):
        gauges = scrape_actuators()
        ds = docker_stats_row()
        rd = redis_light()
        row = {
            "t": round(time.time() - t0, 1),
            "tomcat_busy": gauges["tomcat_threads_busy_threads"],
            "tomcat_current": gauges["tomcat_threads_current_threads"],
            "jvm_cpu_sum": gauges["process_cpu_usage"],
            **ds,
            **rd,
        }
        with self.lock:
            self.samples.append(row)
            self.max_busy = max(self.max_busy, row["tomcat_busy"])
            self.max_current = max(self.max_current, row["tomcat_current"])
            self.max_jvm_cpu = max(self.max_jvm_cpu, row["jvm_cpu_sum"])
            self.max_redis_cpu = max(self.max_redis_cpu, ds["redis_cpu"])
            self.max_get_usec = max(self.max_get_usec, rd["get_usec_per_call"])
            self.max_evalsha_usec = max(self.max_evalsha_usec, rd["evalsha_usec_per_call"])
            self.max_clients = max(self.max_clients, rd["connected_clients"])
            self.max_blocked = max(self.max_blocked, rd["blocked_clients"])
            self.max_ops = max(self.max_ops, rd["ops_per_sec"])

    def peaks(self):
        with self.lock:
            return {
                "samples": len(self.samples),
                "max_tomcat_busy": self.max_busy,
                "max_tomcat_current": self.max_current,
                "max_jvm_cpu_sum": self.max_jvm_cpu,
                "max_redis_cpu_pct": self.max_redis_cpu,
                "max_get_usec_per_call": self.max_get_usec,
                "max_evalsha_usec_per_call": self.max_evalsha_usec,
                "max_connected_clients": self.max_clients,
                "max_blocked_clients": self.max_blocked,
                "max_ops_per_sec": self.max_ops,
            }


def poller(win: Window, t0: float):
    while not win.stop:
        try:
            win.tick(t0)
        except Exception as e:
            with win.lock:
                win.samples.append({"t": round(time.time() - t0, 1), "error": str(e)})
        time.sleep(1.0)


def k6(rate: int, duration: str):
    result_dir = ROOT / "load-test" / "results"
    result_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        "k6", "run",
        "-e", f"RATE={rate}",
        "-e", f"DURATION={duration}",
        "-e", "BASE_URL=http://127.0.0.1:8080",
        "-e", f"PRE_VUS={PRE_VUS}",
        "-e", f"MAX_VUS={MAX_VUS}",
        "-e", "HTTP_TIMEOUT=8s",
        "-e", "GRACEFUL_STOP=5s",
        str(ROOT / "load-test" / "k6-load.js"),
    ]
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    path = result_dir / f"{rate}rps-summary.json"
    summary = json.loads(path.read_text()) if path.exists() else {}
    return proc.returncode, proc.stdout[-2000:], summary


def idle_for(rate: int) -> float:
    return 22.0 if rate >= 1500 else 2.0


def capture(variant: str, rate: int, duration: str, label: str) -> dict:
    print(f"\n######## {variant} {label} {rate}/s {duration} ########", flush=True)
    win = Window()
    t0 = time.time()
    t = threading.Thread(target=poller, args=(win, t0), daemon=True)
    t.start()
    time.sleep(0.8)
    redis_cli("CONFIG", "RESETSTAT")
    rc, stdout, summary = k6(rate, duration)
    win.stop = True
    t.join(timeout=3)
    time.sleep(3)
    prom_snap = snapshot()
    peaks = win.peaks()
    row = {
        "variant": variant,
        "label": label,
        "offered": rate,
        "duration": duration,
        "achieved": summary.get("throughput"),
        "dropped": summary.get("dropped_iterations"),
        "dropped_rate": summary.get("dropped_rate"),
        "http_p50_ms": summary.get("p50_ms"),
        "http_p95_ms": summary.get("p95_ms"),
        "error_rate": summary.get("error_rate"),
        "vus_max": summary.get("vus_max"),
        "http_req_blocked_p95_ms": summary.get("http_req_blocked_p95_ms"),
        "http_req_waiting_p95_ms": summary.get("http_req_waiting_p95_ms"),
        "engine_p50_ms": (prom_snap.get("check_p50") or 0) * 1000,
        "engine_p95_ms": (prom_snap.get("check_p95") or 0) * 1000,
        "get_client_p50_ms": (prom_snap.get("gen_get_p50") or 0) * 1000,
        "get_client_p95_ms": (prom_snap.get("gen_get_p95") or 0) * 1000,
        "evalsha_client_p50_ms": (prom_snap.get("lua_p50") or 0) * 1000,
        "evalsha_client_p95_ms": (prom_snap.get("lua_p95") or 0) * 1000,
        "peaks": peaks,
        "k6": summary,
        "k6_rc": rc,
        "stdout_tail": (stdout or "")[-400:],
    }
    print(stdout[-400:] if stdout else "", flush=True)
    print(
        json.dumps(
            {
                "variant": variant,
                "rate": rate,
                "achieved": row["achieved"],
                "http_p95": row["http_p95_ms"],
                "engine_p95": row["engine_p95_ms"],
                "get_p95": row["get_client_p95_ms"],
                "eval_p95": row["evalsha_client_p95_ms"],
                "rget_us": peaks["max_get_usec_per_call"],
                "reval_us": peaks["max_evalsha_usec_per_call"],
                "redis_cpu": peaks["max_redis_cpu_pct"],
                "busy": peaks["max_tomcat_busy"],
                "clients": peaks["max_connected_clients"],
                "dropped": row["dropped"],
                "blocked_p95": row["http_req_blocked_p95_ms"],
                "vus": row["vus_max"],
            },
            indent=2,
        ),
        flush=True,
    )
    return row


def compose_env(extra: dict) -> dict:
    env = os.environ.copy()
    env.update(extra)
    return env


def compose_slim(extra: dict):
    env = compose_env(extra)
    subprocess.check_call(["docker", "compose", "down"], cwd=ROOT, env=env)
    subprocess.check_call(["docker", "compose", "up", "-d", "--no-build", *SERVICES], cwd=ROOT, env=env)
    time.sleep(8)
    inject_jar()
    wait_healthy("http://127.0.0.1:8081/actuator/health")
    wait_healthy("http://127.0.0.1:8082/actuator/health")
    wait_healthy("http://127.0.0.1:8083/actuator/health")
    wait_healthy("http://127.0.0.1:8080/actuator/health")
    time.sleep(6)


def prepare_redis():
    redis_cli("CONFIG", "SET", "latency-monitor-threshold", "1")
    redis_cli("CONFIG", "RESETSTAT")


def quiet_host():
    load = os.getloadavg()
    print(f"host loadavg={load} (slim Compose, no Grafana/UI/demo)", flush=True)
    return load


def run_variant(variant: dict) -> list[dict]:
    name = variant["name"]
    print(f"\n========== variant {name} {variant['env']} ==========", flush=True)
    compose_slim(variant["env"])
    prepare_redis()
    cases = []
    cases.append(capture(name, WARM_RATE, WARM_DUR, "warmup"))
    for rate in RATES:
        cases.append(capture(name, rate, DURATION, "step"))
        pause = idle_for(rate)
        print(f"idle {pause}s", flush=True)
        time.sleep(pause)
    return cases


def main():
    quiet_host()
    subprocess.check_call(["./gradlew", ":rate-limiter-service:bootJar", "-q"], cwd=ROOT)
    payload = {
        "when": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "protocol": (
            "quiet Mac, slim Compose (no UI/Grafana/demo), nginx keepalive 32, "
            "one warm stack per variant, 20s @ 200/s then 30s steps 400-4000, maxVUs=1000"
        ),
        "variants": {},
        "loadavg_start": list(os.getloadavg()),
    }
    for variant in VARIANTS:
        payload["variants"][variant["name"]] = run_variant(variant)
        OUT.write_text(json.dumps(payload, indent=2))
    payload["loadavg_end"] = list(os.getloadavg())
    OUT.write_text(json.dumps(payload, indent=2))
    print("wrote", OUT, flush=True)


if __name__ == "__main__":
    main()
