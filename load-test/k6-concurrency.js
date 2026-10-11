import http from 'k6/http';
import { check } from 'k6';

const ADMIN_TOKEN = __ENV.THROTTL_ADMIN_TOKEN || 'throttl-dev-admin-token';
const ADMIN_HEADERS = { 'Content-Type': 'application/json', Authorization: `Bearer ${ADMIN_TOKEN}` };
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const allowed = new Counter('allowed_decisions');
const denied = new Counter('denied_decisions');

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 200,
      iterations: 1000,
      maxDuration: '30s',
    },
  },
};

export function setup() {
  const userId = `conc-${Date.now()}`;
  const policy = {
    id: 'concurrency-user-limit',
    name: 'Concurrency User Limit',
    matching: { scope: 'USER', conditions: {} },
    strategy: {
      algorithm: 'TOKEN_BUCKET',
      configuration: { capacity: 100, refillRate: 1, refillPeriod: 'HOUR' },
    },
    metadata: { enabled: true, priority: 1 },
  };
  http.del(`${BASE_URL}/v1/policies/concurrency-user-limit`, null, { headers: ADMIN_HEADERS });
  http.post(`${BASE_URL}/v1/policies`, JSON.stringify(policy), { headers: ADMIN_HEADERS });
  return { userId };
}

export default function (data) {
  const res = http.post(
    `${BASE_URL}/v1/check`,
    JSON.stringify({ userId: data.userId, api: 'payment' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(res, { 'status is 200': (r) => r.status === 200 });
  if (res.status === 200) {
    const body = res.json();
    if (body.allowed) {
      allowed.add(1);
    } else {
      denied.add(1);
    }
  }
}

export function handleSummary(data) {
  const a = (data.metrics.allowed_decisions && data.metrics.allowed_decisions.values.count) || 0;
  const d = (data.metrics.denied_decisions && data.metrics.denied_decisions.values.count) || 0;
  return {
    stdout: `concurrency: allowed=${a} denied=${d} (expect allowed<=100, typically 100)\n`,
  };
}
