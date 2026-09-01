-- Byte-identical (webhook_delivery_logs table only) copy of the CREATE TABLE statement in
-- cia-api/src/main/resources/db/migration/V12__create_partner_tables.sql — see
-- webhook_registrations.sql's header for the cross-module rationale.
--
-- Keep in sync with V12 + the WebhookDeliveryLog entity (cia-partner-api) whenever either changes.
CREATE TABLE IF NOT EXISTS webhook_delivery_logs (
    id                       UUID        NOT NULL DEFAULT gen_random_uuid(),
    webhook_registration_id  UUID        NOT NULL,
    event_type               VARCHAR(60) NOT NULL,
    payload_json             TEXT        NOT NULL,
    success                  BOOLEAN     NOT NULL,
    http_status              INT,
    response_body            TEXT,
    error_message            TEXT,
    attempt                  INT         NOT NULL DEFAULT 1,
    delivered_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_webhook_delivery_logs PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_webhook_log_reg_id ON webhook_delivery_logs (webhook_registration_id);
CREATE INDEX IF NOT EXISTS idx_webhook_log_event   ON webhook_delivery_logs (event_type);
