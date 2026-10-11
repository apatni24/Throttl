-- Return tokens taken by a slot group whose sibling group denied the request.
-- KEYS[1..n] = bucket keys (one hash slot)
-- ARGV[1] = tokens to return per key
-- ARGV[2..] = capacity per key
--
-- Refill timestamps are left alone; the refund is capped at capacity so it can
-- never mint tokens beyond what the policy allows. Missing keys are skipped.

local n = #KEYS
local amount = tonumber(ARGV[1])
if #ARGV ~= 1 + n then
  return redis.error_reply('token_bucket_refund argv mismatch')
end

local refunded = 0
for i = 1, n do
  local tokens = tonumber(redis.call('HGET', KEYS[i], 'tokens'))
  if tokens ~= nil then
    local capacity = tonumber(ARGV[1 + i])
    redis.call('HSET', KEYS[i], 'tokens', math.min(capacity, tokens + amount))
    refunded = refunded + 1
  end
end
return refunded
