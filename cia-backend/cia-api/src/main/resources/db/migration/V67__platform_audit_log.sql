CREATE TABLE IF NOT EXISTS platform_audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action          VARCHAR(32)  NOT NULL,           -- ONBOARD | SUSPEND | ACTIVATE
    target_schema   VARCHAR(63)  NOT NULL,
    actor_username  VARCHAR(255) NOT NULL,
    actor_realm     VARCHAR(63)  NOT NULL,
    detail          JSONB,
    source_ip       VARCHAR(64),
    at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_platform_audit_at ON platform_audit_log (at DESC);
