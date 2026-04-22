-- ── Claim number counter ──────────────────────────────────────────────────
CREATE TABLE claim_counters (
    year            INT     NOT NULL,
    last_sequence   BIGINT  NOT NULL DEFAULT 0,
    CONSTRAINT pk_claim_counters PRIMARY KEY (year)
);

-- ── Claims ────────────────────────────────────────────────────────────────
CREATE TABLE claims (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    claim_number                VARCHAR(30)     NOT NULL,
    status                      VARCHAR(30)     NOT NULL DEFAULT 'REGISTERED',

    -- Policy link (snapshot at registration)
    policy_id                   UUID            NOT NULL REFERENCES policies (id),
    policy_number               VARCHAR(60)     NOT NULL,
    policy_start_date           DATE            NOT NULL,
    policy_end_date             DATE            NOT NULL,

    -- Customer snapshot
    customer_id                 UUID            NOT NULL,
    customer_name               VARCHAR(200)    NOT NULL,

    -- Product / class snapshot
    product_id                  UUID            NOT NULL,
    product_name                VARCHAR(100)    NOT NULL,
    class_of_business_id        UUID            NOT NULL,
    class_of_business_name      VARCHAR(100)    NOT NULL,

    -- Broker
    broker_id                   UUID,
    broker_name                 VARCHAR(100),

    -- Incident
    incident_date               DATE            NOT NULL,
    reported_date               DATE            NOT NULL,
    loss_location               VARCHAR(500),
    description                 TEXT            NOT NULL,
    estimated_loss              DECIMAL(18, 2),

    -- Financials
    reserve_amount              DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    approved_amount             DECIMAL(18, 2),
    currency_code               VARCHAR(3)      NOT NULL DEFAULT 'NGN',

    -- Surveyor
    surveyor_id                 UUID,
    surveyor_name               VARCHAR(200),
    surveyor_assigned_at        TIMESTAMPTZ,

    -- Workflow
    workflow_id                 VARCHAR(200),

    -- Approval / rejection
    approved_by                 VARCHAR(100),
    approved_at                 TIMESTAMPTZ,
    rejected_by                 VARCHAR(100),
    rejected_at                 TIMESTAMPTZ,
    rejection_reason            TEXT,

    -- Withdrawal
    withdrawn_by                VARCHAR(100),
    withdrawn_at                TIMESTAMPTZ,
    withdrawal_reason           TEXT,

    -- Settlement
    settled_at                  TIMESTAMPTZ,

    -- Notes
    notes                       TEXT,

    -- BaseEntity fields
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ,

    CONSTRAINT pk_claims PRIMARY KEY (id),
    CONSTRAINT uq_claim_number UNIQUE (claim_number)
);

CREATE INDEX idx_claims_policy_id        ON claims (policy_id)        WHERE deleted_at IS NULL;
CREATE INDEX idx_claims_status           ON claims (status)            WHERE deleted_at IS NULL;
CREATE INDEX idx_claims_customer_id      ON claims (customer_id)       WHERE deleted_at IS NULL;
CREATE INDEX idx_claims_incident_date    ON claims (incident_date)     WHERE deleted_at IS NULL;
CREATE INDEX idx_claims_surveyor_id      ON claims (surveyor_id)       WHERE deleted_at IS NULL;

-- ── Claim reserves (reserve history; latest = current) ────────────────────
CREATE TABLE claim_reserves (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    claim_id            UUID            NOT NULL REFERENCES claims (id),
    amount              DECIMAL(18, 2)  NOT NULL,
    previous_amount     DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    reason              TEXT            NOT NULL,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT pk_claim_reserves PRIMARY KEY (id)
);

CREATE INDEX idx_claim_reserves_claim_id ON claim_reserves (claim_id) WHERE deleted_at IS NULL;

-- ── Claim expenses ────────────────────────────────────────────────────────
CREATE TABLE claim_expenses (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    claim_id            UUID            NOT NULL REFERENCES claims (id),
    expense_type        VARCHAR(50)     NOT NULL,
    status              VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    vendor_id           UUID,
    vendor_name         VARCHAR(200)    NOT NULL,
    amount              DECIMAL(18, 2)  NOT NULL,
    description         TEXT            NOT NULL,
    approved_by         VARCHAR(100),
    approved_at         TIMESTAMPTZ,
    cancelled_by        VARCHAR(100),
    cancelled_at        TIMESTAMPTZ,
    cancellation_reason TEXT,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT pk_claim_expenses PRIMARY KEY (id)
);

CREATE INDEX idx_claim_expenses_claim_id ON claim_expenses (claim_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_claim_expenses_status   ON claim_expenses (status)   WHERE deleted_at IS NULL;

-- ── Claim documents ───────────────────────────────────────────────────────
CREATE TABLE claim_documents (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    claim_id        UUID            NOT NULL REFERENCES claims (id),
    document_type   VARCHAR(50)     NOT NULL,
    file_name       VARCHAR(255)    NOT NULL,
    file_path       VARCHAR(500)    NOT NULL,
    file_size       BIGINT,
    uploaded_by     VARCHAR(100),

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(100),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT pk_claim_documents PRIMARY KEY (id)
);

CREATE INDEX idx_claim_documents_claim_id ON claim_documents (claim_id) WHERE deleted_at IS NULL;
