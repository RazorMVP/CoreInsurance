-- V12: Partner API — partner apps, webhook registrations, webhook delivery log

CREATE TABLE partner_apps (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    client_id         VARCHAR(100) NOT NULL,
    app_name          VARCHAR(200) NOT NULL,
    contact_email     VARCHAR(200) NOT NULL,
    scopes            TEXT        NOT NULL DEFAULT '',
    plan              VARCHAR(20)  NOT NULL DEFAULT 'STARTER',
    rate_limit_rpm    INT         NOT NULL DEFAULT 60,
    allowed_ips       TEXT,
    active            BOOLEAN     NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        VARCHAR(100),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT pk_partner_apps PRIMARY KEY (id),
    CONSTRAINT uq_partner_apps_client_id UNIQUE (client_id)
);

CREATE TABLE webhook_registrations (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    partner_app_id UUID       NOT NULL,
    target_url    VARCHAR(500) NOT NULL,
    secret        VARCHAR(200) NOT NULL,
    event_types   TEXT        NOT NULL,
    active        BOOLEAN     NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    deleted_at    TIMESTAMPTZ,
    CONSTRAINT pk_webhook_registrations PRIMARY KEY (id)
);

CREATE TABLE webhook_delivery_logs (
    id                      UUID        NOT NULL DEFAULT gen_random_uuid(),
    webhook_registration_id UUID        NOT NULL,
    event_type              VARCHAR(60) NOT NULL,
    payload_json            TEXT        NOT NULL,
    success                 BOOLEAN     NOT NULL,
    http_status             INT,
    response_body           TEXT,
    error_message           TEXT,
    attempt                 INT         NOT NULL DEFAULT 1,
    delivered_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_webhook_delivery_logs PRIMARY KEY (id)
);

CREATE INDEX idx_partner_apps_client_id   ON partner_apps (client_id);
CREATE INDEX idx_webhook_reg_app_id       ON webhook_registrations (partner_app_id);
CREATE INDEX idx_webhook_log_reg_id       ON webhook_delivery_logs (webhook_registration_id);
CREATE INDEX idx_webhook_log_event        ON webhook_delivery_logs (event_type);
