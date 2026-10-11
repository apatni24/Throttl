-- Return what a slot group consumed when a sibling group denied the request.
-- KEYS[1..n] = bucket keys (one hash slot)
-- ARGV = per key triples: algorithm, amount, limit
--   algorithm 1 = token bucket:   tokens += amount, capped at limit (capacity)
--   algorithm 2 = sliding window: current -= amount, floored at 0; spills into
--                 previous if the window rolled between consume and refund
--
-- Refill timestamps and window starts are left alone, so a refund can never
-- mint capacity beyond what the policy allows. Missing keys are skipped.

local TOKEN_BUCKET = 1
local SLIDING_WINDOW = 2
local STRIDE = 3

local n = #KEYS
if #ARGV ~= n * STRIDE then
  return redis.error_reply('rate_limit_refund argv mismatch')
end

local refunded = 0
for i = 1, n do
  local base = (i - 1) * STRIDE
  local algorithm = tonumber(ARGV[base + 1])
  local amount = tonumber(ARGV[base + 2])
  local limit = tonumber(ARGV[base + 3])

  if algorithm == TOKEN_BUCKET then
    local tokens = tonumber(redis.call('HGET', KEYS[i], 'tokens'))
    if tokens ~= nil then
      redis.call('HSET', KEYS[i], 'tokens', math.min(limit, tokens + amount))
      refunded = refunded + 1
    end
  elseif algorithm == SLIDING_WINDOW then
    local data = redis.call('HMGET', KEYS[i], 'current', 'previous')
    local current = tonumber(data[1])
    if current ~= nil then
      local previous = tonumber(data[2]) or 0
      local from_current = math.min(current, amount)
      local from_previous = math.min(previous, amount - from_current)
      redis.call('HSET', KEYS[i], 'current', current - from_current, 'previous', previous - from_previous)
      refunded = refunded + 1
    end
  else
    return redis.error_reply('rate_limit_refund unknown algorithm')
  end
end
return refunded
