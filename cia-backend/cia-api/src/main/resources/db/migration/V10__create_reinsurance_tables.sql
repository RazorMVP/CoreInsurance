-- ── RI allocation counter ─────────────────────────────────────────────────
CREATE TABLE ri_counters (
    year            INT     NOT NULL,
    last_sequence   BIGINT  NOT NULL DEFAULT 0,
    CONSTRAINT pk_ri_counters PRIMARY KEY (year)
);

-- ── FAC reference counter ──────────────────────────────────────────────────
CREATE TABLE ri_fac_counters (
    year            INT     NOT NULL,
    last_sequence   BIGINT  NOT NULL DEFAULT 0,
    CONSTRAINT pk_ri_fac_counters PRIMARY KEY (year)
);

-- ── RI Treaties ───────────────────────────────────────────────────────────
CREATE TABLE ri_treaties (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    treaty_type             VARCHAR(30)     NOT NULL,
    status                  VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',
    treaty_year             INT             NOT NULL,

    -- Scope — null means applies to all
    product_id              UUID,
    class_of_business_id    UUID,

    -- SURPLUS fields
    retention_limit         DECIMAL(18, 2),
    surplus_capacity        DECIMAL(18, 2),

    -- XOL fields
    xol_per_risk_retention  DECIMAL(18, 2),
    xol_per_risk_limit      DECIMAL(18, 2),

    -- Common
    currency_code           VARCHAR(3)      NOT NULL DEFAULT 'NGN',
    effective_date          DATE            NOT NULL,
    expiry_date             DATE            NOT NULL,
    description             TEXT,

    -- BaseEntity
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by              VARCHAR(100),
    deleted_at              TIMESTAMPTZ,

    CONSTRAINT pk_ri_treaties PRIMARY KEY (id)
);

CREATE INDEX idx_ri_treaties_type        ON ri_treaties (treaty_type)          WHERE deleted_at IS NULL;
CREATE INDEX idx_ri_treaties_year        ON ri_treaties (treaty_year)          WHERE deleted_at IS NULL;
CREATE INDEX idx_ri_treaties_cob         ON ri_treaties (class_of_business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_ri_treaties_status      ON ri_treaties (status)               WHERE deleted_at IS NULL;

-- ── RI Treaty Participants ────────────────────────────────────────────────
CREATE TABLE ri_treaty_participants (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    treaty_id                   UUID            NOT NULL REFERENCES ri_treaties (id),
    reinsurance_company_id      UUID            NOT NULL,
    reinsurance_company_name    VARCHAR(200)    NOT NULL,
    share_percentage            DECIMAL(7, 4)   NOT NULL,
    surplus_line                INT,            -- 1, 2, 3… for SURPLUS; NULL for QS/XOL
    is_lead                     BOOLEAN         NOT NULL DEFAULT false,
    commission_rate             DECIMAL(7, 4)   NOT NULL DEFAULT 0,

    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ,

    CONSTRAINT pk_ri_treaty_participants PRIMARY KEY (id)
);

CREATE INDEX idx_ri_tp_treaty_id ON ri_treaty_participants (treaty_id) WHERE deleted_at IS NULL;

-- ── RI Allocations ────────────────────────────────────────────────────────
CREATE TABLE ri_allocations (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    allocation_number       VARCHAR(30)     NOT NULL,
    policy_id               UUID            NOT NULL,
    policy_number           VARCHAR(60)     NOT NULL,
    endorsement_id          UUID,
    treaty_id               UUID            REFERENCES ri_treaties (id),
    treaty_type             VARCHAR(30)     NOT NULL,
    status                  VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',

    our_share_sum_insured   DECIMAL(18, 2)  NOT NULL,
    retained_amount         DECIMAL(18, 2)  NOT NULL,
    ceded_amount            DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    excess_amount           DECIMAL(18, 2)  NOT NULL DEFAULT 0,

    our_share_premium       DECIMAL(18, 2)  NOT NULL,
    retained_premium        DECIMAL(18, 2)  NOT NULL,
    ceded_premium           DECIMAL(18, 2)  NOT NULL DEFAULT 0,

    currency_code           VARCHAR(3)      NOT NULL DEFAULT 'NGN',

    -- BaseEntity
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by              VARCHAR(100),
    deleted_at              TIMESTAMPTZ,

    CONSTRAINT pk_ri_allocations    PRIMARY KEY (id),
    CONSTRAINT uq_ri_alloc_number   UNIQUE (allocation_number)
);

CREATE INDEX idx_ri_alloc_policy_id     ON ri_allocations (policy_id)   WHERE deleted_at IS NULL;
CREATE INDEX idx_ri_alloc_treaty_id     ON ri_allocations (treaty_id)   WHERE deleted_at IS NULL;
CREATE INDEX idx_ri_alloc_status        ON ri_allocations (status)      WHERE deleted_at IS NULL;
CREATE INDEX idx_ri_alloc_endorse_id    ON ri_allocations (endorsement_id) WHERE endorsement_id IS NOT NULL;

-- ── RI Allocation Lines (per-reinsurer breakdown) ─────────────────────────
CREATE TABLE ri_allocation_lines (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    allocation_id               UUID            NOT NULL REFERENCES ri_allocations (id),
    participant_id              UUID            REFERENCES ri_treaty_participants (id),
    reinsurance_company_id      UUID            NOT NULL,
    reinsurance_company_name    VARCHAR(200)    NOT NULL,
    share_percentage            DECIMAL(7, 4)   NOT NULL,
    ceded_amount                DECIMAL(18, 2)  NOT NULL,
    ceded_premium               DECIMAL(18, 2)  NOT NULL,
    commission_rate             DECIMAL(7, 4)   NOT NULL DEFAULT 0,
    commission_amount           DECIMAL(18, 2)  NOT NULL DEFAULT 0,

    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),

    CONSTRAINT pk_ri_allocation_lines PRIMARY KEY (id)
);

CREATE INDEX idx_ri_alloc_lines_alloc_id ON ri_allocation_lines (allocation_id);

-- ── FAC Covers (outward facultative) ──────────────────────────────────────
CREATE TABLE ri_fac_covers (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    fac_reference               VARCHAR(50)     NOT NULL,
    policy_id                   UUID            NOT NULL,
    policy_number               VARCHAR(60)     NOT NULL,
    reinsurance_company_id      UUID            NOT NULL,
    reinsurance_company_name    VARCHAR(200)    NOT NULL,
    status                      VARCHAR(30)     NOT NULL DEFAULT 'PENDING',

    sum_insured_ceded           DECIMAL(18, 2)  NOT NULL,
    premium_rate                DECIMAL(10, 6)  NOT NULL,
    premium_ceded               DECIMAL(18, 2)  NOT NULL,
    commission_rate             DECIMAL(7, 4)   NOT NULL DEFAULT 0,
    commission_amount           DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    net_premium                 DECIMAL(18, 2)  NOT NULL,

    currency_code               VARCHAR(3)      NOT NULL DEFAULT 'NGN',
    cover_from                  DATE            NOT NULL,
    cover_to                    DATE            NOT NULL,

    offer_slip_reference        VARCHAR(100),
    terms                       TEXT,

    approved_by                 VARCHAR(100),
    approved_at                 TIMESTAMPTZ,
    cancelled_by                VARCHAR(100),
    cancelled_at                TIMESTAMPTZ,
    cancellation_reason         TEXT,

    -- BaseEntity
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ,

    CONSTRAINT pk_ri_fac_covers     PRIMARY KEY (id),
    CONSTRAINT uq_fac_reference     UNIQUE (fac_reference)
);

CREATE INDEX idx_ri_fac_policy_id   ON ri_fac_covers (policy_id)  WHERE deleted_at IS NULL;
CREATE INDEX idx_ri_fac_status      ON ri_fac_covers (status)     WHERE deleted_at IS NULL;
CREATE INDEX idx_ri_fac_ri_co       ON ri_fac_covers (reinsurance_company_id) WHERE deleted_at IS NULL;
