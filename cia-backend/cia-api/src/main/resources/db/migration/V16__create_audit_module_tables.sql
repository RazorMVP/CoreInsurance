-- ── Audit module tables ──────────────────────────────────────────────────────
-- Extends the existing audit_log with an optional approval_amount field,
-- and adds login tracking, alert configuration, and fired alert storage.

ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS approval_amount NUMERIC(19, 2);

-- ── Login / session audit log ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS login_audit_log (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type     VARCHAR(50)  NOT NULL,
    user_id        VARCHAR(255),
    user_name      VARCHAR(255),
    ip_address     VARCHAR(100),
    user_agent     VARCHAR(500),
    timestamp      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    success        BOOLEAN      NOT NULL DEFAULT TRUE,
    failure_reason VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_login_audit_user ON login_audit_log (user_id);
CREATE INDEX IF NOT EXISTS idx_login_audit_ts   ON login_audit_log (timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_login_audit_type ON login_audit_log (event_type);

-- ── Alert configuration (one row per tenant, seeded on provisioning) ──────────
CREATE TABLE IF NOT EXISTS audit_alert_config (
    id                           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    business_hours_start         TIME          NOT NULL DEFAULT '08:00:00',
    business_hours_end           TIME          NOT NULL DEFAULT '18:00:00',
    business_days                VARCHAR(100)  NOT NULL DEFAULT 'MON,TUE,WED,THU,FRI',
    large_approval_threshold     NUMERIC(19,2) NOT NULL DEFAULT 50000000,
    max_failed_login_attempts    INT           NOT NULL DEFAULT 3,
    bulk_delete_count            INT           NOT NULL DEFAULT 5,
    bulk_delete_window_minutes   INT           NOT NULL DEFAULT 5,
    retention_years              INT           NOT NULL DEFAULT 7,
    created_at                   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

INSERT INTO audit_alert_config (id) VALUES (gen_random_uuid());

-- ── Fired alerts ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_alert (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_type       VARCHAR(100) NOT NULL,
    severity         VARCHAR(20)  NOT NULL DEFAULT 'WARNING',
    user_id          VARCHAR(255),
    user_name        VARCHAR(255),
    description      TEXT         NOT NULL,
    metadata         JSONB,
    triggered_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    acknowledged     BOOLEAN      NOT NULL DEFAULT FALSE,
    acknowledged_by  VARCHAR(255),
    acknowledged_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_audit_alert_type ON audit_alert (alert_type);
CREATE INDEX IF NOT EXISTS idx_audit_alert_ts   ON audit_alert (triggered_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_alert_ack  ON audit_alert (acknowledged);
