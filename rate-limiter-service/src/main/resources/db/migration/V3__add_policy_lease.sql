-- NULL = no lease: every check for the policy is decided in Redis.
ALTER TABLE policies ADD COLUMN lease_tokens INTEGER;
ALTER TABLE policies ADD COLUMN lease_ttl_ms INTEGER;
