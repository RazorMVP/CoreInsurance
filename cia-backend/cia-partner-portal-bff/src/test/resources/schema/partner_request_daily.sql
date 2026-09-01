-- Byte-identical copy of cia-api/src/main/resources/db/migration/V81__partner_request_daily.sql,
-- for PortalUsageIT to create the table directly inside a tenant schema via plain JDBC — see
-- webhook_registrations.sql's header for the cross-module rationale (this module cannot depend on
-- cia-api, which owns Flyway and the real migration file).
--
-- Keep in sync with V81 + the PartnerRequestDaily entity (cia-partner-api) whenever either changes.
CREATE TABLE IF NOT EXISTS partner_request_daily (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_app_id UUID NOT NULL,
    usage_date     DATE NOT NULL,
    total          BIGINT NOT NULL DEFAULT 0,
    success        BIGINT NOT NULL DEFAULT 0,
    client_error   BIGINT NOT NULL DEFAULT 0,
    server_error   BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_prd_app_date UNIQUE (partner_app_id, usage_date)
);
CREATE INDEX IF NOT EXISTS idx_prd_app ON partner_request_daily (partner_app_id, usage_date DESC);
