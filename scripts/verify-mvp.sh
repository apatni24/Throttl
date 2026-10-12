#!/usr/bin/env bash
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
I1="${INSTANCE_1:-http://localhost:8081}"
I2="${INSTANCE_2:-http://localhost:8082}"
I3="${INSTANCE_3:-http://localhost:8083}"
PROM="${PROM_URL:-http://localhost:9090}"
GRAFANA="${GRAFANA_URL:-http://localhost:3000}"
TOKEN="${THROTTL_ADMIN_TOKEN:-throttl-dev-admin-token}"
AUTH=(-H "Authorization: Bearer $TOKEN")

pass=0
fail=0
note() { printf '\n==> %s\n' "$*"; }
ok() { printf '    PASS %s\n' "$*"; pass=$((pass + 1)); }
bad() { printf '    FAIL %s\n' "$*"; fail=$((fail + 1)); }

json() { curl -sf -H 'Content-Type: application/json' "$@"; }
admin_json() { json "${AUTH[@]}" "$@"; }
check() { json -X POST "$BASE/v1/check" -d "$1"; }

clear_policies() {
  local ids
  ids=$(curl -sf "$BASE/v1/policies" | jq -r '.[].id // empty')
  if [[ -n "$ids" ]]; then
    while IFS= read -r id; do
      [[ -z "$id" ]] && continue
      curl -s "${AUTH[@]}" -X DELETE "$BASE/v1/policies/$id" >/dev/null || true
    done <<< "$ids"
  fi
}

put_policy() {
  local id="$1"
  local body="$2"
  local code
  code=$(curl -s -o /tmp/throttl-policy.json -w '%{http_code}' "${AUTH[@]}" -X POST "$BASE/v1/policies" \
    -H 'Content-Type: application/json' -d "$body")
  if [[ "$code" == "400" ]]; then
    admin_json -X PUT "$BASE/v1/policies/$id" -d "$body" >/dev/null
  elif [[ "$code" != "201" ]]; then
    cat /tmp/throttl-policy.json
    return 1
  fi
}

wait_up() {
  local url="$1"
  for _ in $(seq 1 90); do
    if curl -sf "$url" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

POLICY5='{
  "id": "test-user-policy",
  "name": "Test User Policy",
  "matching": { "scope": "USER", "conditions": { "api": "test" } },
  "strategy": {
    "algorithm": "TOKEN_BUCKET",
    "configuration": { "capacity": 5, "refillRate": 1, "refillPeriod": "SECOND" }
  },
  "metadata": { "enabled": true, "priority": 10 }
}'

POLICY10='{
  "id": "test-user-policy",
  "name": "Test User Policy",
  "matching": { "scope": "USER", "conditions": { "api": "test" } },
  "strategy": {
    "algorithm": "TOKEN_BUCKET",
    "configuration": { "capacity": 10, "refillRate": 1, "refillPeriod": "SECOND" }
  },
  "metadata": { "enabled": true, "priority": 10 }
}'

note "Wait for limiter health"
wait_up "$BASE/actuator/health" || { echo "limiter not up"; exit 1; }
ok "GET $BASE/actuator/health"
health=$(curl -sf "$BASE/actuator/health")
echo "$health" | grep -q '"status":"UP"' && ok "status UP" || bad "status UP: $health"

note "Infrastructure"
for svc in postgres redis limiter-1 limiter-2 limiter-3 prometheus grafana; do
  if docker compose ps --status running --services | grep -qx "$svc"; then
    ok "compose $svc running"
  else
    bad "compose $svc running"
  fi
done

note "Control-plane auth"
anon=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/policies" -H 'Content-Type: application/json' -d "$POLICY5")
[[ "$anon" == "401" ]] && ok "POST /v1/policies without token -> 401" || bad "anonymous write returned $anon"
wrong=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/v1/policies/test-user-policy" -H 'Authorization: Bearer wrong')
[[ "$wrong" == "401" ]] && ok "DELETE with wrong token -> 401" || bad "wrong-token write returned $wrong"
reads=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v1/policies")
[[ "$reads" == "200" ]] && ok "GET /v1/policies stays open" || bad "anonymous read returned $reads"

note "Policy CRUD"
clear_policies
created=$(admin_json -X POST "$BASE/v1/policies" -d "$POLICY5")
echo "$created" | grep -q '"id":"test-user-policy"' && ok "POST /v1/policies" || bad "POST /v1/policies $created"
fetched=$(curl -sf "$BASE/v1/policies/test-user-policy")
echo "$fetched" | grep -q '"capacity":5' && ok "GET policy capacity 5" || bad "GET $fetched"
admin_json -X PUT "$BASE/v1/policies/test-user-policy" -d "$POLICY10" >/dev/null
updated=$(curl -sf "$BASE/v1/policies/test-user-policy")
echo "$updated" | grep -q '"capacity":10' && ok "PUT capacity 10 persisted" || bad "PUT $updated"
admin_json -X PUT "$BASE/v1/policies/test-user-policy" -d "$POLICY5" >/dev/null

note "Token consumption capacity=5"
r1=$(check '{"userId":"user-1","api":"test"}')
r2=$(check '{"userId":"user-1","api":"test"}')
r3=$(check '{"userId":"user-1","api":"test"}')
r4=$(check '{"userId":"user-1","api":"test"}')
r5=$(check '{"userId":"user-1","api":"test"}')
r6=$(check '{"userId":"user-1","api":"test"}')
echo "$r1" | grep -q '"remaining":4' && echo "$r1" | grep -q '"allowed":true' && ok "req1 remaining 4" || bad "req1 $r1"
echo "$r2" | grep -q '"remaining":3' && ok "req2 remaining 3" || bad "req2 $r2"
echo "$r5" | grep -q '"remaining":0' && echo "$r5" | grep -q '"allowed":true' && ok "req5 remaining 0" || bad "req5 $r5"
echo "$r6" | grep -q '"allowed":false' && ok "req6 denied" || bad "req6 $r6"

note "Refill ~1s then ~3s"
clear_policies
put_policy test-user-policy "$POLICY5"
for _ in 1 2 3 4 5; do check '{"userId":"refill-user","api":"test"}' >/dev/null; done
sleep 1.15
refill=$(check '{"userId":"refill-user","api":"test"}')
echo "$refill" | grep -q '"allowed":true' && echo "$refill" | grep -q '"remaining":0' && ok "after 1s remaining 0" || bad "refill $refill"
again=$(check '{"userId":"refill-user","api":"test"}')
echo "$again" | grep -q '"allowed":false' && ok "immediate deny after refill" || bad "after refill $again"
sleep 3.15
three=$(check '{"userId":"refill-user","api":"test"}')
echo "$three" | grep -q '"allowed":true' || { bad "after 3s $three"; three=''; }
if [[ -n "$three" ]]; then
  rem=$(echo "$three" | jq '.remaining')
  # 3 refill periods minus 1 consume => remaining 2, allow 1-3 for timer jitter
  if [[ "$rem" -ge 1 && "$rem" -le 3 ]]; then
    ok "after 3s remaining=$rem"
  else
    bad "after 3s remaining=$rem body=$three"
  fi
fi

note "Redis bucket keys"
redis_dump=$(docker compose exec -T redis redis-cli HGETALL 'rl:{USER:refill-user}:test-user-policy' || true)
echo "$redis_dump" | grep -q tokens && ok "Redis hash has tokens" || bad "Redis hash $redis_dump"
echo "$redis_dump" | grep -q lastRefillTimestamp && ok "Redis hash has lastRefillTimestamp" || bad "Redis timestamp"

note "Burst"
clear_policies
put_policy test-user-policy "$POLICY5"
burst_ok=0
for _ in 1 2 3 4 5; do
  b=$(check '{"userId":"burst-user","api":"test"}')
  echo "$b" | grep -q '"allowed":true' && burst_ok=$((burst_ok + 1))
done
burst_deny=$(check '{"userId":"burst-user","api":"test"}')
[[ "$burst_ok" -eq 5 ]] && ok "burst 5 allowed" || bad "burst allowed=$burst_ok"
echo "$burst_deny" | grep -q '"allowed":false' && ok "burst 6th denied" || bad "burst 6 $burst_deny"

note "User isolation"
clear_policies
put_policy test-user-policy "$POLICY5"
for _ in 1 2 3 4 5; do check '{"userId":"user-A","api":"test"}' >/dev/null; done
a6=$(check '{"userId":"user-A","api":"test"}')
b1=$(check '{"userId":"user-B","api":"test"}')
echo "$a6" | grep -q '"allowed":false' && ok "user-A exhausted" || bad "user-A $a6"
echo "$b1" | grep -q '"allowed":true' && ok "user-B independent bucket" || bad "user-B $b1"

note "API isolation"
clear_policies
put_policy payment-limit '{
  "id": "payment-limit",
  "name": "Payment",
  "matching": { "scope": "USER", "conditions": { "api": "payment" } },
  "strategy": { "algorithm": "TOKEN_BUCKET", "configuration": { "capacity": 5, "refillRate": 1, "refillPeriod": "HOUR" } },
  "metadata": { "enabled": true, "priority": 10 }
}'
put_policy accounts-limit '{
  "id": "accounts-limit",
  "name": "Accounts",
  "matching": { "scope": "USER", "conditions": { "api": "accounts" } },
  "strategy": { "algorithm": "TOKEN_BUCKET", "configuration": { "capacity": 10, "refillRate": 1, "refillPeriod": "HOUR" } },
  "metadata": { "enabled": true, "priority": 10 }
}'
for _ in 1 2 3 4 5; do check '{"userId":"iso-user","api":"payment"}' >/dev/null; done
pay6=$(check '{"userId":"iso-user","api":"payment"}')
acc=$(check '{"userId":"iso-user","api":"accounts"}')
echo "$pay6" | grep -q '"allowed":false' && ok "payment bucket exhausted" || bad "payment $pay6"
echo "$acc" | grep -q '"allowed":true' && ok "accounts bucket untouched" || bad "accounts $acc"

note "Multiple policies AND"
clear_policies
put_policy multi-user '{
  "id": "multi-user",
  "name": "Multi user",
  "matching": { "scope": "USER", "conditions": {} },
  "strategy": { "algorithm": "TOKEN_BUCKET", "configuration": { "capacity": 5, "refillRate": 1, "refillPeriod": "HOUR" } },
  "metadata": { "enabled": true, "priority": 20 }
}'
put_policy multi-api '{
  "id": "multi-api",
  "name": "Multi api",
  "matching": { "scope": "API", "conditions": {} },
  "strategy": { "algorithm": "TOKEN_BUCKET", "configuration": { "capacity": 10, "refillRate": 1, "refillPeriod": "HOUR" } },
  "metadata": { "enabled": true, "priority": 10 }
}'
first=$(check '{"userId":"multi-a","api":"payment"}')
echo "$first" | grep -q '"allowed":true' && ok "both policies allow" || bad "multi first $first"
for _ in 1 2 3 4; do check '{"userId":"multi-a","api":"payment"}' >/dev/null; done
denied=$(check '{"userId":"multi-a","api":"payment"}')
echo "$denied" | grep -q '"allowed":false' && echo "$denied" | grep -q 'multi-user' && ok "denied by user policy" || bad "multi deny $denied"

note "Policy change resets Redis buckets"
clear_policies
put_policy test-user-policy "$POLICY5"
for _ in 1 2 3 4 5; do check '{"userId":"reset-user","api":"test"}' >/dev/null; done
before=$(docker compose exec -T redis redis-cli EXISTS 'rl:{USER:reset-user}:test-user-policy')
admin_json -X PUT "$BASE/v1/policies/test-user-policy" -d "$POLICY5" >/dev/null
after=$(docker compose exec -T redis redis-cli EXISTS 'rl:{USER:reset-user}:test-user-policy')
[[ "$before" == "1" && "$after" == "0" ]] && ok "PUT deletes existing buckets" || bad "bucket reset before=$before after=$after"
fresh=$(check '{"userId":"reset-user","api":"test"}')
echo "$fresh" | grep -q '"remaining":4' && ok "new capacity applied after reset" || bad "fresh $fresh"

note "Policy cache"
docker compose logs limiter-1 > /tmp/throttl-limiter-1.log 2>&1 || true
if grep -F "Policy cache MISS" /tmp/throttl-limiter-1.log >/dev/null; then
  ok "saw cache MISS logs"
else
  bad "cache MISS logs"
fi
hits=$(curl -sf http://localhost:8081/actuator/prometheus | awk '/^policy_cache_hits_total/ {print int($2)}')
[[ "${hits:-0}" -gt 0 ]] && ok "policy_cache_hits_total=$hits" || bad "no cache hits recorded"

note "Prometheus scrape"
targets=$(curl -sf "$PROM/api/v1/targets")
echo "$targets" | grep -q limiter-1 && ok "Prometheus knows limiter-1" || bad "prometheus targets"
metric=$(curl -sf --get "$PROM/api/v1/query" --data-urlencode 'query=rate_limit_checks_total' || true)
echo "$metric" | grep -q '"result"' && ok "Prometheus query rate_limit_checks_total" || bad "prom query $metric"
hits=$(curl -sf --get "$PROM/api/v1/query" --data-urlencode 'query=policy_cache_hits_total' || true)
echo "$hits" | grep -q '"result"' && ok "Prometheus query policy_cache_hits_total" || bad "prom cache hits"

note "Grafana"
dash=$(curl -sf "$GRAFANA/api/search?query=Distributed")
echo "$dash" | grep -q 'throttl-rate-limiter' && ok "Grafana dashboard provisioned" || bad "grafana $dash"

note "Distributed shared state (capacity 100 across 3 instances)"
clear_policies
put_policy dist-user '{
  "id": "dist-user",
  "name": "Distributed",
  "matching": { "scope": "USER", "conditions": {} },
  "strategy": { "algorithm": "TOKEN_BUCKET", "configuration": { "capacity": 100, "refillRate": 1, "refillPeriod": "HOUR" } },
  "metadata": { "enabled": true, "priority": 1 }
}'
allowed=0
denied=0
for i in $(seq 1 300); do
  inst=$I1
  rem=$((i % 3))
  [[ $rem -eq 1 ]] && inst=$I2
  [[ $rem -eq 2 ]] && inst=$I3
  body=$(curl -sf -X POST "$inst/v1/check" -H 'Content-Type: application/json' -d '{"userId":"dist-user-1"}')
  if echo "$body" | grep -q '"allowed":true'; then
    allowed=$((allowed + 1))
  else
    denied=$((denied + 1))
  fi
done
echo "    distributed allowed=$allowed denied=$denied (300 requests)"
[[ "$allowed" -eq 100 ]] && ok "global allow == 100 not 100/instance" || bad "global allow $allowed"
clear_policies

printf '\nPassed: %s  Failed: %s\n' "$pass" "$fail"
if [[ "$fail" -gt 0 ]]; then
  exit 1
fi
