-- NDPR per-tenant data retention policy (singleton per tenant schema).
-- Created unqualified so it lands in public + every tenant schema via the per-schema migration sweep.
CREATE TABLE IF NOT EXISTS data_retention_policy (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_pii_retention_days INT         NOT NULL DEFAULT 2555,   -- ~7 years after last activity
    purge_enabled               BOOLEAN     NOT NULL DEFAULT FALSE,  -- opt-in safety rail
    purge_frequency             VARCHAR(10) NOT NULL DEFAULT 'WEEKLY',
    purge_day_of_week           SMALLINT    NOT NULL DEFAULT 0,      -- 0=Sun..6=Sat (WEEKLY)
    purge_hour_utc              SMALLINT    NOT NULL DEFAULT 3,      -- 0..23
    last_purge_run_at           TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ
);
