import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '30s';
// Little's law at ~50ms in-flight: concurrency ≈ rate * 0.05. Cap VUs there
// so a slow backend cannot accumulate thousands of waiting VUs.
const MAX_VUS = Number(__ENV.MAX_VUS || Math.min(Math.max(RATE * 2, 200), 2000));
const PRE_VUS = Number(__ENV.PRE_VUS || Math.min(RATE, MAX_VUS, 500));
const HTTP_TIMEOUT = __ENV.HTTP_TIMEOUT || '60s';
const GRACEFUL_STOP = __ENV.GRACEFUL_STOP || '30s';

export const options = {
  scenarios: {
    constant: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: GRACEFUL_STOP,
    },
  },
};

export function setup() {
  const policy = {
    id: 'load-user-limit',
    name: 'Load User Limit',
    matching: { scope: 'USER', conditions: { api: 'load' } },
    strategy: {
      algorithm: 'TOKEN_BUCKET',
      configuration: { capacity: 10000000, refillRate: 10000000, refillPeriod: 'SECOND' },
    },
    metadata: { enabled: true, priority: 1 },
  };
  http.post(`${BASE_URL}/v1/policies`, JSON.stringify(policy), {
    headers: { 'Content-Type': 'application/json' },
  });
}

export default function () {
  const res = http.post(
    `${BASE_URL}/v1/check`,
    JSON.stringify({
      userId: `user-${__VU}`,
      tenantId: 'acme',
      api: 'load',
      ip: '10.0.0.1',
    }),
    { headers: { 'Content-Type': 'application/json' }, timeout: HTTP_TIMEOUT }
  );
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const httpDuration = data.metrics.http_req_duration;
  const reqs = data.metrics.http_reqs;
  const failed = data.metrics.http_req_failed;
  const values = httpDuration ? httpDuration.values : {};
  const dropped = data.metrics.dropped_iterations;
  const vus = data.metrics.vus;
  const blocked = data.metrics.http_req_blocked;
  const waiting = data.metrics.http_req_waiting;
  const connecting = data.metrics.http_req_connecting;
  const blockedValues = blocked ? blocked.values : {};
  const waitingValues = waiting ? waiting.values : {};
  const connectingValues = connecting ? connecting.values : {};
  const summary = {
    rate: RATE,
    duration: DURATION,
    max_vus: MAX_VUS,
    pre_vus: PRE_VUS,
    requests: reqs ? reqs.values.count : 0,
    throughput: reqs ? reqs.values.rate : 0,
    p50_ms: values['p(50)'] || values['avg'] || 0,
    p95_ms: values['p(95)'] || 0,
    p99_ms: values['p(99)'] || 0,
    avg_ms: values.avg || 0,
    error_rate: failed ? failed.values.rate : 0,
    dropped_iterations: dropped ? dropped.values.count || 0 : 0,
    dropped_rate: dropped && dropped.values ? dropped.values.rate || 0 : 0,
    vus_max: vus && vus.values ? vus.values.max : 0,
    vus_min: vus && vus.values ? vus.values.min : 0,
    vus_value: vus && vus.values ? vus.values.value : 0,
    http_req_blocked_p95_ms: blockedValues['p(95)'] || 0,
    http_req_waiting_p95_ms: waitingValues['p(95)'] || 0,
    http_req_connecting_p95_ms: connectingValues['p(95)'] || 0,
  };
  return {
    [`load-test/results/${RATE}rps-summary.json`]: JSON.stringify(summary, null, 2),
    stdout:
      `load ${RATE}/s ${DURATION}\n` +
      `  requests=${summary.requests} throughput=${summary.throughput.toFixed(2)}/s\n` +
      `  p50=${summary.p50_ms.toFixed(2)}ms p95=${summary.p95_ms.toFixed(2)}ms p99=${summary.p99_ms.toFixed(2)}ms avg=${summary.avg_ms.toFixed(2)}ms\n` +
      `  errors=${(summary.error_rate * 100).toFixed(3)}% dropped=${summary.dropped_iterations} vus_max=${summary.vus_max} cap=${MAX_VUS}\n` +
      `  blocked_p95=${summary.http_req_blocked_p95_ms.toFixed(2)}ms waiting_p95=${summary.http_req_waiting_p95_ms.toFixed(2)}ms\n`,
  };
}
