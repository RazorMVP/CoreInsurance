-- Tenant schema template: tables created in each tenant's schema on provisioning.
-- This migration creates the template_ schema; provisioning copies it per tenant.

CREATE SCHEMA IF NOT EXISTS template_;

SET search_path TO template_;

CREATE TABLE IF NOT EXISTS audit_log (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(100) NOT NULL,
    entity_id   VARCHAR(100) NOT NULL,
    action      VARCHAR(20)  NOT NULL,
    user_id     VARCHAR(255),
    user_name   VARCHAR(255),
    timestamp   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    old_value   JSONB,
    new_value   JSONB,
    ip_address  VARCHAR(45),
    session_id  VARCHAR(255)
);

CREATE INDEX idx_audit_entity  ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_user    ON audit_log (user_id);
CREATE INDEX idx_audit_ts      ON audit_log (timestamp DESC);

CREATE TABLE IF NOT EXISTS webhook_registrations (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_app_id  VARCHAR(255) NOT NULL,
    target_url      VARCHAR(2048) NOT NULL,
    secret          VARCHAR(255) NOT NULL,
    event_types     TEXT         NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(255),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_webhook_partner ON webhook_registrations (partner_app_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS partner_apps (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id       VARCHAR(255) NOT NULL UNIQUE,
    app_name        VARCHAR(255) NOT NULL,
    contact_email   VARCHAR(255) NOT NULL,
    tenant_id       VARCHAR(63)  NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    plan            VARCHAR(20)  NOT NULL DEFAULT 'SANDBOX',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(255),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_partner_apps_client_id ON partner_apps (client_id) WHERE deleted_at IS NULL;

RESET search_path;
