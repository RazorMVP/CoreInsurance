-- V81: Task 9 (Partner Portal BFF sub-project A) — durable per-app daily request
-- telemetry. Tenant schema (unqualified — applied inside each tenant schema, like
-- V12's partner_apps/webhook_registrations/webhook_delivery_logs). Populated by
-- PartnerUsageFlushWorkflow's daily 03:00 UTC cron, which upserts the previous
-- day's Redis/in-memory live rollup here. Read by GET /portal/apps/{id}/usage
-- (cia-partner-portal-bff) for the "history" section of the usage dashboard.

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
