// One subject hammering one bucket: how many Redis round trips does it cost?
//
//   MODE=deny   capacity LIMIT, refill LIMIT/s (default 100); offered RATE
//               well above LIMIT so most checks are over the limit. Compare
//               deny cache on/off (THROTTL_DENY_CACHE_ENABLED on the limiters).
//   MODE=lease  limit far above RATE; LEASE_TOKENS>0 enables a lease of that
//               many units per Redis call (LEASE_TTL_MS, default 1000).
//
// Redis calls are read from each limiter's Prometheus endpoint before and
// after the run (redis_operation_latency_seconds_count).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const ADMIN_TOKEN = __ENV.THROTTL_ADMIN_TOKEN || 'throttl-dev-admin-token';
const ADMIN_HEADERS = { 'Content-Type': 'application/json', Authorization: `Bearer ${ADMIN_TOKEN}` };
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const INSTANCES = (__ENV.INSTANCES || 'http://localhost:8081,http://localhost:8082,http://localhost:8083').split(',');
const MODE = __ENV.MODE || 'deny';
const RATE = Number(__ENV.RATE || 1000);
const LIMIT = Number(__ENV.LIMIT || 100);
const DURATION = __ENV.DURATION || '30s';
const LEASE_TOKENS = Number(__ENV.LEASE_TOKENS || 0);
const LEASE_TTL_MS = Number(__ENV.LEASE_TTL_MS || 1000);
const LABEL = __ENV.LABEL || `${MODE}${LEASE_TOKENS ? `-lease${LEASE_TOKENS}` : ''}`;
const POLICY_ID = `hot-${MODE}`;

const allowed = new Counter('checks_allowed');
const redisCalls = new Counter('redis_calls');
const limiterChecks = new Counter('limiter_checks');

export const options = {
  scenarios: {
    hot: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(RATE, 400),
      maxVUs: Math.min(RATE * 2, 2000),
    },
  },
};

function policy() {
  const strategy = MODE === 'deny'
    ? { algorithm: 'TOKEN_BUCKET', configuration: { capacity: LIMIT, refillRate: LIMIT, refillPeriod: 'SECOND' } }
    : { algorithm: 'TOKEN_BUCKET', configuration: { capacity: 1000000, refillRate: 1000000, refillPeriod: 'SECOND' } };
  if (LEASE_TOKENS > 0) {
    strategy.lease = { tokens: LEASE_TOKENS, ttlMs: LEASE_TTL_MS };
  }
  return {
    id: POLICY_ID,
    name: POLICY_ID,
    matching: { scope: 'USER', conditions: { api: POLICY_ID } },
    strategy,
    metadata: { enabled: true, priority: 1 },
  };
}

function scrape(name) {
  let total = 0;
  for (const base of INSTANCES) {
    const res = http.get(`${base}/actuator/prometheus`);
    for (const line of res.body.split('\n')) {
      if (line.startsWith(name)) {
        total += Number(line.substring(line.lastIndexOf(' ') + 1));
      }
    }
  }
  return total;
}

export function setup() {
  http.del(`${BASE_URL}/v1/policies/${POLICY_ID}`, null, { headers: ADMIN_HEADERS });
  const created = http.post(`${BASE_URL}/v1/policies`, JSON.stringify(policy()), { headers: ADMIN_HEADERS });
  if (created.status !== 201) {
    throw new Error(`policy create failed: ${created.status} ${created.body}`);
  }
  // let every instance observe the new policy generation, and refuse to run if
  // some other policy also matches: its bucket would decide instead of ours
  for (const base of INSTANCES) {
    const probe = http.post(`${base}/v1/check`, JSON.stringify({ userId: 'probe', api: POLICY_ID }), {
      headers: { 'Content-Type': 'application/json' },
    });
    const matched = (probe.json('evaluations') || []).map((e) => e.policyId);
    if (matched.length !== 1 || matched[0] !== POLICY_ID) {
      throw new Error(`${base} matched ${JSON.stringify(matched)}; delete the other policies first`);
    }
  }
  return {
    userId: `hot-${Date.now()}`,
    redis: scrape('redis_operation_latency_seconds_count'),
    checks: scrape('rate_limit_checks_total'),
  };
}

export default function (data) {
  const res = http.post(
    `${BASE_URL}/v1/check`,
    JSON.stringify({ userId: data.userId, tenantId: 'acme', api: POLICY_ID }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(res, { 'status 200': (r) => r.status === 200 });
  if (res.status === 200 && res.json('allowed') === true) {
    allowed.add(1);
  }
}

export function teardown(baseline) {
  redisCalls.add(scrape('redis_operation_latency_seconds_count') - baseline.redis);
  limiterChecks.add(scrape('rate_limit_checks_total') - baseline.checks);
  http.del(`${BASE_URL}/v1/policies/${POLICY_ID}`, null, { headers: ADMIN_HEADERS });
}

export function handleSummary(data) {
  const value = (metric, key) => (data.metrics[metric] ? data.metrics[metric].values[key] || 0 : 0);
  const checks = value('limiter_checks', 'count');
  const redis = value('redis_calls', 'count');
  const summary = {
    label: LABEL,
    mode: MODE,
    rate: RATE,
    limit: MODE === 'deny' ? LIMIT : null,
    duration: DURATION,
    lease_tokens: LEASE_TOKENS,
    lease_ttl_ms: LEASE_TTL_MS,
    requests: value('http_reqs', 'count'),
    allowed: value('checks_allowed', 'count'),
    limiter_checks: checks,
    redis_calls: redis,
    redis_calls_per_check: checks ? redis / checks : 0,
    p50_ms: value('http_req_duration', 'med'),
    p95_ms: value('http_req_duration', 'p(95)'),
    error_rate: value('http_req_failed', 'rate'),
    dropped_iterations: value('dropped_iterations', 'count'),
  };
  return {
    [`load-test/results/hot-key-${LABEL}.json`]: JSON.stringify(summary, null, 2),
    stdout:
      `hot-key ${LABEL} ${RATE}/s ${DURATION}\n` +
      `  requests=${summary.requests} allowed=${summary.allowed} errors=${(summary.error_rate * 100).toFixed(3)}%\n` +
      `  limiter checks=${checks} redis calls=${redis} (${summary.redis_calls_per_check.toFixed(3)} per check)\n` +
      `  p50=${summary.p50_ms.toFixed(2)}ms p95=${summary.p95_ms.toFixed(2)}ms dropped=${summary.dropped_iterations}\n`,
  };
}
