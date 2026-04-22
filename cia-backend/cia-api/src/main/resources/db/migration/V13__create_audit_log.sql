CREATE TABLE IF NOT EXISTS audit_log (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(100) NOT NULL,
    entity_id   VARCHAR(100) NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    user_id     VARCHAR(255),
    user_name   VARCHAR(255),
    timestamp   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    old_value   JSONB,
    new_value   JSONB,
    ip_address  VARCHAR(100),
    session_id  VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON audit_log (timestamp);
