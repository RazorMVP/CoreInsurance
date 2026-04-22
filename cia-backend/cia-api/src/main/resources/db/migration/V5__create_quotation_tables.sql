-- ── Quote number counters ────────────────────────────────────────────────
CREATE TABLE quote_counters (
    year            INT         NOT NULL,
    last_sequence   BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_quote_counters PRIMARY KEY (year)
);

-- ── Quotes ───────────────────────────────────────────────────────────────
CREATE TABLE quotes (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    quote_number                VARCHAR(30)     NOT NULL,
    status                      VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',

    -- Customer snapshot (denormalized at quote creation)
    customer_id                 UUID            NOT NULL,
    customer_name               VARCHAR(200)    NOT NULL,

    -- Product snapshot (denormalized at quote creation — rate locked at this point)
    product_id                  UUID            NOT NULL,
    product_name                VARCHAR(100)    NOT NULL,
    product_code                VARCHAR(20)     NOT NULL,
    product_rate                DECIMAL(10, 4)  NOT NULL,

    -- Class of business snapshot
    class_of_business_id        UUID            NOT NULL,
    class_of_business_name      VARCHAR(100)    NOT NULL,

    -- Broker (nullable — direct business has no broker)
    broker_id                   UUID,
    broker_name                 VARCHAR(100),

    business_type               VARCHAR(30)     NOT NULL DEFAULT 'DIRECT',

    policy_start_date           DATE            NOT NULL,
    policy_end_date             DATE            NOT NULL,

    -- Financials (computed and stored for fast retrieval)
    total_sum_insured           DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    total_premium               DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    discount                    DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    net_premium                 DECIMAL(18, 2)  NOT NULL DEFAULT 0,

    notes                       TEXT,

    -- Temporal workflow reference
    workflow_id                 VARCHAR(200),

    -- Approval / rejection
    approved_by                 VARCHAR(100),
    approved_at                 TIMESTAMPTZ,
    rejected_by                 VARCHAR(100),
    rejected_at                 TIMESTAMPTZ,
    rejection_reason            TEXT,

    expires_at                  TIMESTAMPTZ,

    -- BaseEntity fields
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ,

    CONSTRAINT pk_quotes PRIMARY KEY (id),
    CONSTRAINT uq_quotes_quote_number UNIQUE (quote_number)
);

CREATE INDEX idx_quotes_status           ON quotes (status)          WHERE deleted_at IS NULL;
CREATE INDEX idx_quotes_customer_id      ON quotes (customer_id)     WHERE deleted_at IS NULL;
CREATE INDEX idx_quotes_product_id       ON quotes (product_id)      WHERE deleted_at IS NULL;
CREATE INDEX idx_quotes_broker_id        ON quotes (broker_id)       WHERE deleted_at IS NULL;
CREATE INDEX idx_quotes_created_at       ON quotes (created_at DESC) WHERE deleted_at IS NULL;

-- ── Quote risks ──────────────────────────────────────────────────────────
CREATE TABLE quote_risks (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    quote_id        UUID            NOT NULL REFERENCES quotes (id),
    description     VARCHAR(500)    NOT NULL,
    sum_insured     DECIMAL(18, 2)  NOT NULL,
    premium         DECIMAL(18, 2)  NOT NULL,
    section_id      UUID,
    section_name    VARCHAR(100),
    risk_details    JSONB,
    order_no        INT             NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(100),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT pk_quote_risks PRIMARY KEY (id)
);

CREATE INDEX idx_quote_risks_quote_id ON quote_risks (quote_id) WHERE deleted_at IS NULL;

-- ── Quote coinsurance participants ───────────────────────────────────────
CREATE TABLE quote_coinsurance_participants (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    quote_id                    UUID            NOT NULL REFERENCES quotes (id),
    insurance_company_id        UUID            NOT NULL,
    insurance_company_name      VARCHAR(200)    NOT NULL,
    share_percentage            DECIMAL(6, 4)   NOT NULL,

    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ,

    CONSTRAINT pk_quote_coinsurance_participants PRIMARY KEY (id)
);

CREATE INDEX idx_quote_coins_quote_id ON quote_coinsurance_participants (quote_id)
    WHERE deleted_at IS NULL;
