-- ── Endorsement number counter ────────────────────────────────────────────
CREATE TABLE endorsement_counters (
    year            INT         NOT NULL,
    last_sequence   BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_endorsement_counters PRIMARY KEY (year)
);

-- ── Endorsements ──────────────────────────────────────────────────────────
CREATE TABLE endorsements (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    endorsement_number          VARCHAR(30)     NOT NULL,
    status                      VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',
    endorsement_type            VARCHAR(30)     NOT NULL DEFAULT 'NON_PREMIUM_BEARING',

    -- Parent policy (snapshot at creation time)
    policy_id                   UUID            NOT NULL REFERENCES policies (id),
    policy_number               VARCHAR(60)     NOT NULL,

    -- Customer snapshot
    customer_id                 UUID            NOT NULL,
    customer_name               VARCHAR(200)    NOT NULL,

    -- Product snapshot
    product_id                  UUID            NOT NULL,
    product_name                VARCHAR(100)    NOT NULL,
    product_code                VARCHAR(20)     NOT NULL,
    product_rate                DECIMAL(10, 4)  NOT NULL,

    -- Class of business snapshot
    class_of_business_id        UUID            NOT NULL,
    class_of_business_name      VARCHAR(100)    NOT NULL,

    -- Broker
    broker_id                   UUID,
    broker_name                 VARCHAR(100),

    -- Endorsement period
    effective_date              DATE            NOT NULL,
    policy_end_date             DATE            NOT NULL,
    remaining_days              INT             NOT NULL DEFAULT 0,

    -- Financial adjustment (positive = additional premium, negative = return premium)
    old_sum_insured             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    new_sum_insured             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    old_net_premium             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    new_net_premium             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    premium_adjustment          DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    currency_code               VARCHAR(3)      NOT NULL DEFAULT 'NGN',

    -- Description of what changed
    description                 TEXT            NOT NULL,
    notes                       TEXT,

    -- Temporal workflow
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

    -- BaseEntity fields
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ,

    CONSTRAINT pk_endorsements PRIMARY KEY (id),
    CONSTRAINT uq_endorsement_number UNIQUE (endorsement_number)
);

CREATE INDEX idx_endorsements_policy_id    ON endorsements (policy_id)    WHERE deleted_at IS NULL;
CREATE INDEX idx_endorsements_status       ON endorsements (status)        WHERE deleted_at IS NULL;
CREATE INDEX idx_endorsements_customer_id  ON endorsements (customer_id)   WHERE deleted_at IS NULL;
CREATE INDEX idx_endorsements_eff_date     ON endorsements (effective_date) WHERE deleted_at IS NULL;

-- ── Endorsement risks (post-endorsement risk state) ───────────────────────
CREATE TABLE endorsement_risks (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    endorsement_id      UUID            NOT NULL REFERENCES endorsements (id),
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

    CONSTRAINT pk_endorsement_risks PRIMARY KEY (id)
);

CREATE INDEX idx_endorsement_risks_endorsement_id ON endorsement_risks (endorsement_id)
    WHERE deleted_at IS NULL;
