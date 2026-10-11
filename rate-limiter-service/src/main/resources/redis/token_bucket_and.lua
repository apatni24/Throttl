-- Atomic AND across N token buckets: consume every key or none.
-- KEYS[1..n] = bucket keys
-- ARGV[1] = nowMs
-- ARGV[2] = requested tokens
-- ARGV[3..] = repeating triples: capacity, refillRate, refillPeriodMs
--
-- Returns a flat array:
--   [1] overall allowed (0|1)
--   [2] 1-based index of first denial, or 0
--   then per key: remaining, retryAfterMs, wouldAllow (0|1)

local n = #KEYS
local now = tonumber(ARGV[1])
local requested = tonumber(ARGV[2])
if n < 1 or #ARGV ~= 2 + (n * 3) then
  return redis.error_reply('token_bucket_and argv mismatch')
end

local tokens = {}
local last_ts = {}
local remaining = {}
local retry_after = {}
local would_allow = {}
local capacities = {}
local refill_rates = {}
local refill_periods = {}
local first_deny = 0
local all_allowed = 1

for i = 1, n do
  local base = 2 + ((i - 1) * 3)
  local capacity = tonumber(ARGV[base + 1])
  local refill_rate = tonumber(ARGV[base + 2])
  local refill_period_ms = tonumber(ARGV[base + 3])
  capacities[i] = capacity
  refill_rates[i] = refill_rate
  refill_periods[i] = refill_period_ms

  local data = redis.call('HMGET', KEYS[i], 'tokens', 'lastRefillTimestamp')
  local t = tonumber(data[1])
  local last = tonumber(data[2])
  if t == nil or last == nil then
    t = capacity
    last = now
  end

  local elapsed = now - last
  if elapsed < 0 then
    elapsed = 0
  end
  local periods = math.floor(elapsed / refill_period_ms)
  if periods > 0 then
    t = math.min(capacity, t + (periods * refill_rate))
    last = last + (periods * refill_period_ms)
  end

  tokens[i] = t
  last_ts[i] = last
  remaining[i] = t
  retry_after[i] = 0
  would_allow[i] = 1

  if t < requested then
    would_allow[i] = 0
    all_allowed = 0
    if first_deny == 0 then
      first_deny = i
    end
    local deficit = requested - t
    local periods_needed = math.ceil(deficit / refill_rate)
    local next_refill_at = last + (periods_needed * refill_period_ms)
    local wait = next_refill_at - now
    if wait < 0 then
      wait = 0
    end
    retry_after[i] = wait
  end
end

for i = 1, n do
  local t = tokens[i]
  if all_allowed == 1 then
    t = t - requested
  end
  remaining[i] = t
  redis.call('HSET', KEYS[i], 'tokens', t, 'lastRefillTimestamp', last_ts[i])
  local idle_ttl = math.max(capacities[i] / refill_rates[i] * refill_periods[i] * 2, 60000)
  redis.call('PEXPIRE', KEYS[i], math.floor(idle_ttl))
end

local result = { all_allowed, first_deny }
for i = 1, n do
  result[#result + 1] = remaining[i]
  result[#result + 1] = retry_after[i]
  result[#result + 1] = would_allow[i]
end
return result
