-- Atomic token-bucket consume.
-- KEYS[1] = bucket key
-- ARGV[1] = capacity
-- ARGV[2] = refillRate
-- ARGV[3] = refillPeriodMs
-- ARGV[4] = nowMs
-- ARGV[5] = requested tokens
--
-- Returns: {allowed (0|1), remaining, retryAfterMs}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local refill_period_ms = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
local requested = tonumber(ARGV[5])

local data = redis.call('HMGET', key, 'tokens', 'lastRefillTimestamp')
local tokens = tonumber(data[1])
local last = tonumber(data[2])

if tokens == nil or last == nil then
  tokens = capacity
  last = now
end

local elapsed = now - last
if elapsed < 0 then
  elapsed = 0
end

local periods = math.floor(elapsed / refill_period_ms)
if periods > 0 then
  tokens = math.min(capacity, tokens + (periods * refill_rate))
  last = last + (periods * refill_period_ms)
end

local allowed = 0
local retry_after = 0

if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  allowed = 0
  local deficit = requested - tokens
  local periods_needed = math.ceil(deficit / refill_rate)
  local next_refill_at = last + (periods_needed * refill_period_ms)
  retry_after = next_refill_at - now
  if retry_after < 0 then
    retry_after = 0
  end
end

redis.call('HSET', key, 'tokens', tokens, 'lastRefillTimestamp', last)

local idle_ttl = math.max(capacity / refill_rate * refill_period_ms * 2, 60000)
redis.call('PEXPIRE', key, math.floor(idle_ttl))

return {allowed, tokens, retry_after}
