-- V60__create_tenant_notification_template.sql
-- F7-δ + R7 — Per-tenant notification template overrides.
-- Multi-row table keyed on (template_type, channel). Permissive override
-- model: subject_template and body_template are independently nullable
-- and fall back to JAR defaults when NULL. SMS rows must have NULL
-- subject_template (subjects don't apply to SMS).

CREATE TABLE tenant_notification_template (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    template_type    VARCHAR(40)  NOT NULL,   -- RECEIPT | PAYMENT_VOUCHER (extensible)
    channel          VARCHAR(20)  NOT NULL,   -- EMAIL | SMS
    subject_template TEXT,                    -- Mustache; NULL = use default; always NULL for SMS
    body_template    TEXT,                    -- Mustache; NULL = use default
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(255),
    updated_by       VARCHAR(255),
    deleted_at       TIMESTAMPTZ,

    CONSTRAINT ck_tnt_at_least_one_override
        CHECK (subject_template IS NOT NULL OR body_template IS NOT NULL),
    CONSTRAINT ck_tnt_sms_no_subject
        CHECK (channel = 'EMAIL' OR subject_template IS NULL)
);

-- One active override per (template_type, channel) per tenant
-- (schema-per-tenant; no tenant_id column needed).
CREATE UNIQUE INDEX uq_tenant_notification_template_type_channel
    ON tenant_notification_template (template_type, channel)
    WHERE deleted_at IS NULL;

-- For listing / lookup by type only
CREATE INDEX idx_tnt_type ON tenant_notification_template (template_type)
    WHERE deleted_at IS NULL;
