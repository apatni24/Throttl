CREATE TABLE policies (
    id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    conditions JSONB NOT NULL DEFAULT '{}'::jsonb,
    algorithm VARCHAR(32) NOT NULL,
    configuration JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    priority INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_policies_enabled_priority ON policies (enabled, priority DESC);
