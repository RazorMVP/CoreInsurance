-- Byte-identical (webhook_registrations table only) copy of the CREATE TABLE statement in
-- cia-api/src/main/resources/db/migration/V12__create_partner_tables.sql, for PortalUsageIT to
-- create the table directly inside a tenant schema via plain JDBC — see partner_apps.sql's header
-- for the same cross-module rationale (this module cannot depend on cia-api).
--
-- Keep in sync with V12 + the WebhookRegistration entity (cia-partner-api) whenever either changes.
CREATE TABLE IF NOT EXISTS webhook_registrations (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    partner_app_id UUID        NOT NULL,
    target_url     VARCHAR(500) NOT NULL,
    secret         VARCHAR(200) NOT NULL,
    event_types    TEXT        NOT NULL,
    active         BOOLEAN     NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     VARCHAR(100),
    deleted_at     TIMESTAMPTZ,
    CONSTRAINT pk_webhook_registrations PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_webhook_reg_app_id ON webhook_registrations (partner_app_id);
