import http from 'k6/http';
import { check } from 'k6';

const ADMIN_TOKEN = __ENV.THROTTL_ADMIN_TOKEN || 'throttl-dev-admin-token';
const ADMIN_HEADERS = { 'Content-Type': 'application/json', Authorization: `Bearer ${ADMIN_TOKEN}` };
import { Counter } from 'k6/metrics';

const INSTANCES = (__ENV.INSTANCES || 'http://localhost:8081,http://localhost:8082,http://localhost:8083').split(',');
const allowed = new Counter('allowed_decisions');
const denied = new Counter('denied_decisions');

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 300,
      iterations: 1000,
      maxDuration: '30s',
    },
  },
};

export function setup() {
  const base = INSTANCES[0];
  const userId = `dist-${Date.now()}`;
  const policy = {
    id: 'distributed-user-limit',
    name: 'Distributed User Limit',
    matching: { scope: 'USER', conditions: {} },
    strategy: {
      algorithm: 'TOKEN_BUCKET',
      configuration: { capacity: 100, refillRate: 1, refillPeriod: 'HOUR' },
    },
    metadata: { enabled: true, priority: 1 },
  };
  http.del(`${base}/v1/policies/distributed-user-limit`, null, { headers: ADMIN_HEADERS });
  http.post(`${base}/v1/policies`, JSON.stringify(policy), { headers: ADMIN_HEADERS });
  return { userId };
}

export default function (data) {
  const url = INSTANCES[(__ITER + __VU) % INSTANCES.length];
  const res = http.post(
    `${url}/v1/check`,
    JSON.stringify({ userId: data.userId }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(res, { 'status is 200': (r) => r.status === 200 });
  if (res.status === 200) {
    if (res.json().allowed) {
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
    stdout: `distributed: allowed=${a} denied=${d} across ${INSTANCES.join(', ')} (expect allowed==100 globally)\n`,
  };
}
