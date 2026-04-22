-- ── Policies ─────────────────────────────────────────────────────────────
CREATE TABLE policies (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    policy_number               VARCHAR(60),
    status                      VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',

    -- Quote linkage (nullable — direct policies have no quote)
    quote_id                    UUID,
    quote_number                VARCHAR(30),

    -- Customer snapshot
    customer_id                 UUID            NOT NULL,
    customer_name               VARCHAR(200)    NOT NULL,

    -- Product snapshot (locked at policy creation)
    product_id                  UUID            NOT NULL,
    product_name                VARCHAR(100)    NOT NULL,
    product_code                VARCHAR(20)     NOT NULL,
    product_rate                DECIMAL(10, 4)  NOT NULL,

    -- Class of business snapshot
    class_of_business_id        UUID            NOT NULL,
    class_of_business_name      VARCHAR(100)    NOT NULL,
    class_of_business_code      VARCHAR(20)     NOT NULL,

    -- Broker (nullable)
    broker_id                   UUID,
    broker_name                 VARCHAR(100),

    business_type               VARCHAR(30)     NOT NULL DEFAULT 'DIRECT',
    niid_required               BOOLEAN         NOT NULL DEFAULT FALSE,

    policy_start_date           DATE            NOT NULL,
    policy_end_date             DATE            NOT NULL,

    -- Financials
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

    -- Cancellation
    cancelled_by                VARCHAR(100),
    cancelled_at                TIMESTAMPTZ,
    cancellation_reason         TEXT,

    -- NAICOM
    naicom_uid                  VARCHAR(100),
    naicom_uploaded_at          TIMESTAMPTZ,
    naicom_certificate_path     VARCHAR(500),

    -- NIID
    niid_ref                    VARCHAR(100),
    niid_uploaded_at            TIMESTAMPTZ,

    -- Documents
    policy_document_path        VARCHAR(500),

    -- BaseEntity fields
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ,

    CONSTRAINT pk_policies PRIMARY KEY (id),
    CONSTRAINT uq_policies_policy_number UNIQUE (policy_number)
);

CREATE INDEX idx_policies_status            ON policies (status)            WHERE deleted_at IS NULL;
CREATE INDEX idx_policies_customer_id       ON policies (customer_id)       WHERE deleted_at IS NULL;
CREATE INDEX idx_policies_product_id        ON policies (product_id)        WHERE deleted_at IS NULL;
CREATE INDEX idx_policies_quote_id          ON policies (quote_id)          WHERE deleted_at IS NULL;
CREATE INDEX idx_policies_broker_id         ON policies (broker_id)         WHERE deleted_at IS NULL;
CREATE INDEX idx_policies_policy_start_date ON policies (policy_start_date) WHERE deleted_at IS NULL;
CREATE INDEX idx_policies_policy_end_date   ON policies (policy_end_date)   WHERE deleted_at IS NULL;

-- ── Policy risks ──────────────────────────────────────────────────────────
CREATE TABLE policy_risks (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    policy_id           UUID            NOT NULL REFERENCES policies (id),
    description         VARCHAR(500)    NOT NULL,
    sum_insured         DECIMAL(18, 2)  NOT NULL,
    premium             DECIMAL(18, 2)  NOT NULL,
    section_id          UUID,
    section_name        VARCHAR(100),
    risk_details        JSONB,
    vehicle_reg_number  VARCHAR(20),
    order_no            INT             NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT pk_policy_risks PRIMARY KEY (id)
);

CREATE INDEX idx_policy_risks_policy_id ON policy_risks (policy_id) WHERE deleted_at IS NULL;

-- ── Policy coinsurance participants ───────────────────────────────────────
CREATE TABLE policy_coinsurance_participants (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    policy_id                   UUID            NOT NULL REFERENCES policies (id),
    insurance_company_id        UUID            NOT NULL,
    insurance_company_name      VARCHAR(200)    NOT NULL,
    share_percentage            DECIMAL(6, 4)   NOT NULL,

    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ,

    CONSTRAINT pk_policy_coinsurance_participants PRIMARY KEY (id)
);

CREATE INDEX idx_policy_coins_policy_id ON policy_coinsurance_participants (policy_id)
    WHERE deleted_at IS NULL;
