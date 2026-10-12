-- Atomic AND across N buckets of mixed algorithms: consume every key or none.
-- KEYS[1..n] = bucket keys (must share a hash slot on Redis Cluster)
-- ARGV[1]    = requested units (the minimum every key must have available)
-- ARGV[2..]  = per key quintuples: algorithm, want, p1, p2, p3
--   want >= requested; a key with want > requested is a lease refill and is
--   granted min(want, available) units instead of exactly `requested`
--   algorithm 1 = token bucket:   capacity, refillRate, refillPeriodMs
--   algorithm 2 = sliding window: limit, windowMs, (unused)
--
-- "now" comes from the Redis server clock, so every limiter instance refills
-- and rolls windows against one clock regardless of host skew.
--
-- Returns a flat array:
--   [1] overall allowed (0|1)
--   [2] 1-based index of first denial, or 0
--   then per key: remaining, retryAfterMs, wouldAllow (0|1), granted

local TOKEN_BUCKET = 1
local SLIDING_WINDOW = 2
local STRIDE = 5

local n = #KEYS
local requested = tonumber(ARGV[1])
if n < 1 or #ARGV ~= 1 + (n * STRIDE) then
  return redis.error_reply('rate_limit argv mismatch')
end

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

-- Token bucket: refill whole periods since the last refill, then check.
local function load_token_bucket(key, capacity, refill_rate, refill_period_ms)
  local data = redis.call('HMGET', key, 'tokens', 'lastRefillTimestamp')
  local tokens = tonumber(data[1])
  local last = tonumber(data[2])
  if tokens == nil or last == nil then
    tokens = capacity
    last = now
  end
  local elapsed = math.max(0, now - last)
  local periods = math.floor(elapsed / refill_period_ms)
  if periods > 0 then
    tokens = math.min(capacity, tokens + (periods * refill_rate))
    last = last + (periods * refill_period_ms)
  end

  local s = { tokens = tokens, last = last, available = tokens, retry = 0 }
  if tokens < requested then
    local periods_needed = math.ceil((requested - tokens) / refill_rate)
    s.retry = math.max(0, last + (periods_needed * refill_period_ms) - now)
  end
  return s
end

-- Sliding window counter: windows are aligned to the epoch. The trailing
-- window count is estimated as previous * (unexpired fraction) + current.
-- A window start in the future (clock stepped back after a failover) is kept
-- rather than rolled backwards.
local function load_sliding_window(key, limit, window_ms)
  local data = redis.call('HMGET', key, 'windowStart', 'current', 'previous')
  local start = tonumber(data[1])
  local current = tonumber(data[2]) or 0
  local previous = tonumber(data[3]) or 0
  local aligned = now - (now % window_ms)
  if start == nil then
    start = aligned
    current = 0
    previous = 0
  elseif aligned > start then
    if aligned - start == window_ms then
      previous = current
    else
      previous = 0
    end
    current = 0
    start = aligned
  end

  local elapsed = math.min(window_ms, math.max(0, now - start))
  local estimate = (previous * (window_ms - elapsed)) / window_ms + current
  local available = math.max(0, math.floor(limit - estimate))

  local s = { start = start, current = current, previous = previous, available = available, retry = 0 }
  if available < requested then
    local budget = limit - requested - current
    local wait
    if budget >= 0 and previous > 0 then
      -- Still inside this window: wait for the previous window's weight to decay.
      wait = window_ms - elapsed - ((budget * window_ms) / previous)
    elseif current > 0 then
      -- Wait for the rollover, then for the old current (now previous) to decay.
      wait = (window_ms - elapsed) + ((window_ms * (current - (limit - requested))) / current)
    else
      wait = window_ms - elapsed
    end
    s.retry = math.max(0, math.ceil(wait))
  end
  return s
end

local states = {}
local all_allowed = 1
local first_deny = 0

for i = 1, n do
  local base = 1 + ((i - 1) * STRIDE)
  local algorithm = tonumber(ARGV[base + 1])
  local want = tonumber(ARGV[base + 2])
  local p1 = tonumber(ARGV[base + 3])
  local p2 = tonumber(ARGV[base + 4])
  local p3 = tonumber(ARGV[base + 5])

  local s
  if algorithm == TOKEN_BUCKET then
    s = load_token_bucket(KEYS[i], p1, p2, p3)
  elseif algorithm == SLIDING_WINDOW then
    s = load_sliding_window(KEYS[i], p1, p2)
  else
    return redis.error_reply('rate_limit unknown algorithm')
  end
  s.algorithm = algorithm
  s.want = want
  s.p1 = p1
  s.p2 = p2
  s.p3 = p3
  s.would_allow = 1
  if s.available < requested then
    s.would_allow = 0
    all_allowed = 0
    if first_deny == 0 then
      first_deny = i
    end
  end
  states[i] = s
end

local result = { all_allowed, first_deny }
for i = 1, n do
  local s = states[i]
  local take = 0
  if all_allowed == 1 then
    take = math.max(requested, math.min(s.want, s.available))
  end

  if s.algorithm == TOKEN_BUCKET then
    redis.call('HSET', KEYS[i], 'tokens', s.tokens - take, 'lastRefillTimestamp', s.last)
    local idle_ttl = math.max(s.p1 / s.p2 * s.p3 * 2, 60000)
    redis.call('PEXPIRE', KEYS[i], math.floor(idle_ttl))
  else
    redis.call('HSET', KEYS[i], 'windowStart', s.start, 'current', s.current + take, 'previous', s.previous)
    redis.call('PEXPIRE', KEYS[i], (2 * s.p2) + 1000)
  end

  result[#result + 1] = s.available - take
  result[#result + 1] = s.retry
  result[#result + 1] = s.would_allow
  result[#result + 1] = take
end
return result
