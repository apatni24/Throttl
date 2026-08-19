import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    constant: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(RATE, 500),
      maxVUs: Math.max(RATE * 2, 200),
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
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const httpDuration = data.metrics.http_req_duration;
  const reqs = data.metrics.http_reqs;
  const failed = data.metrics.http_req_failed;
  const values = httpDuration ? httpDuration.values : {};
  const summary = {
    rate: RATE,
    duration: DURATION,
    requests: reqs ? reqs.values.count : 0,
    throughput: reqs ? reqs.values.rate : 0,
    p50_ms: values['p(50)'] || 0,
    p95_ms: values['p(95)'] || 0,
    p99_ms: values['p(99)'] || 0,
    error_rate: failed ? failed.values.rate : 0,
  };
  return {
    [`load-test/results/${RATE}rps-summary.json`]: JSON.stringify(summary, null, 2),
    stdout:
      `load ${RATE}/s ${DURATION}\n` +
      `  requests=${summary.requests} throughput=${summary.throughput.toFixed(2)}/s\n` +
      `  p50=${summary.p50_ms.toFixed(2)}ms p95=${summary.p95_ms.toFixed(2)}ms p99=${summary.p99_ms.toFixed(2)}ms\n` +
      `  errors=${(summary.error_rate * 100).toFixed(3)}%\n`,
  };
}
