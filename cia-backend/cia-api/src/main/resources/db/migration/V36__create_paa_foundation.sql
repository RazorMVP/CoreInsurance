-- ─────────────────────────────────────────────────────────────────────────────
-- V36 — IFRS 17 PAA Foundation
--
-- Module 12 (Period-End Closures) Phase 2 Slice 2.1 — schema only.
-- The PAA (Premium Allocation Approach) measurement engine in slices 2.2–2.8
-- writes against the tables created here:
--   1. portfolio              (risk-similar grouping of products per IFRS 17 §14)
--   2. group_of_contracts     (annual cohort × onerousness bucket per §16-22)
--   3. paa_lrc                (Liability for Remaining Coverage roll-forward)
--   4. paa_lic                (Liability for Incurred Claims roll-forward)
--   5. paa_config             (per-tenant IFRS 17 accounting policy elections)
-- Plus FK promotion on journal_entry_line.portfolio_id and
-- journal_entry_line.contract_group_id (placeholders shipped in V31).
--
-- Money columns: DECIMAL(18,2) — matches V31.
-- Soft-delete pattern: deleted_at TIMESTAMPTZ + partial indexes.
-- Constraint naming: pk_*, uq_*, fk_*, ck_*.
--
-- No data is seeded in this slice. ContractGroupingService (slice 2.2) is the
-- first writer; it listens to PolicyApprovedEvent and lazily upserts the
-- portfolio + group rows on first use per tenant.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. Portfolio ──────────────────────────────────────────────────────────────
-- A portfolio is a set of contracts subject to similar risks and managed
-- together (IFRS 17 §14). For GB insurance the natural grain is one row per
-- class-of-business + sales-channel combination (e.g. "Motor Comp Retail",
-- "Motor Comp Broker", "Fire Commercial"). The class-of-business FK is
-- nullable: tenants migrating from legacy systems may bucket contracts by
-- their own taxonomy that doesn't 1:1 map to the existing class table.
CREATE TABLE portfolio (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    code                    VARCHAR(20)     NOT NULL,
    name                    VARCHAR(200)    NOT NULL,
    class_of_business_id    UUID,
    description             TEXT,
    is_active               BOOLEAN         NOT NULL DEFAULT TRUE,

    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by              VARCHAR(100),
    deleted_at              TIMESTAMPTZ,

    CONSTRAINT pk_portfolio PRIMARY KEY (id),
    CONSTRAINT uq_portfolio_code UNIQUE (code),
    CONSTRAINT fk_portfolio_cob FOREIGN KEY (class_of_business_id) REFERENCES classes_of_business (id)
);

CREATE INDEX idx_portfolio_cob    ON portfolio (class_of_business_id) WHERE deleted_at IS NULL AND class_of_business_id IS NOT NULL;
CREATE INDEX idx_portfolio_active ON portfolio (is_active)            WHERE deleted_at IS NULL;

-- ── 2. Group of contracts ─────────────────────────────────────────────────────
-- IFRS 17 §16-22: within each portfolio, contracts are partitioned into
-- annual cohorts and within each cohort into onerousness buckets. A contract
-- assigned to a group at initial recognition stays in that group for life —
-- onerousness is NOT a flag that flips on the same row; loss components are
-- recognised against the assigned group instead. status reflects whether new
-- contracts can still be assigned (OPEN) or the cohort window has closed.
CREATE TABLE group_of_contracts (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    portfolio_id            UUID            NOT NULL,
    cohort_year             INT             NOT NULL,
    onerousness             VARCHAR(40)     NOT NULL,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'OPEN',

    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by              VARCHAR(100),
    deleted_at              TIMESTAMPTZ,

    CONSTRAINT pk_group_of_contracts PRIMARY KEY (id),
    CONSTRAINT fk_group_portfolio FOREIGN KEY (portfolio_id) REFERENCES portfolio (id),
    CONSTRAINT uq_group_portfolio_cohort_onerousness UNIQUE (portfolio_id, cohort_year, onerousness),
    CONSTRAINT ck_group_onerousness CHECK (
        onerousness IN ('NOT_ONEROUS', 'NO_SIGNIFICANT_POSSIBILITY', 'ONEROUS')
    ),
    CONSTRAINT ck_group_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_group_cohort_year CHECK (cohort_year BETWEEN 1900 AND 2200)
);

CREATE INDEX idx_group_portfolio ON group_of_contracts (portfolio_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_group_cohort    ON group_of_contracts (cohort_year)  WHERE deleted_at IS NULL;
CREATE INDEX idx_group_status    ON group_of_contracts (status)       WHERE deleted_at IS NULL;

-- ── 3. PAA LRC roll-forward ───────────────────────────────────────────────────
-- One row per (group, period). Captures the movement of the Liability for
-- Remaining Coverage during the period. Under PAA the LRC is essentially
-- unearned premium adjusted for: acquisition cashflow deferral (when the
-- tenant elects §59(a)), and loss component recognition for onerous groups.
--   opening_balance + premium_received - premium_earned
--                  + acquisition_costs_deferred - acquisition_costs_amortised
--                  + loss_component_change
--     = closing_balance
-- The LrcEngine (slice 2.3) enforces this identity in code; the DB enforces
-- non-negativity invariants only.
CREATE TABLE paa_lrc (
    id                              UUID            NOT NULL DEFAULT gen_random_uuid(),
    group_id                        UUID            NOT NULL,
    period_id                       UUID            NOT NULL,
    opening_balance                 DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    premium_received                DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    premium_earned                  DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    acquisition_costs_deferred      DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    acquisition_costs_amortised     DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    loss_component                  DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    loss_component_change           DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    closing_balance                 DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    currency_code                   VARCHAR(3)      NOT NULL DEFAULT 'NGN',

    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                      VARCHAR(100),
    deleted_at                      TIMESTAMPTZ,

    CONSTRAINT pk_paa_lrc PRIMARY KEY (id),
    CONSTRAINT fk_paa_lrc_group  FOREIGN KEY (group_id)  REFERENCES group_of_contracts (id),
    CONSTRAINT fk_paa_lrc_period FOREIGN KEY (period_id) REFERENCES fiscal_period (id),
    CONSTRAINT uq_paa_lrc_group_period UNIQUE (group_id, period_id),
    CONSTRAINT ck_paa_lrc_nonneg CHECK (
        opening_balance >= 0
        AND premium_received >= 0
        AND premium_earned >= 0
        AND acquisition_costs_deferred >= 0
        AND acquisition_costs_amortised >= 0
        AND loss_component >= 0
        AND closing_balance >= 0
    )
);

CREATE INDEX idx_paa_lrc_group  ON paa_lrc (group_id)  WHERE deleted_at IS NULL;
CREATE INDEX idx_paa_lrc_period ON paa_lrc (period_id) WHERE deleted_at IS NULL;

-- ── 4. PAA LIC roll-forward ───────────────────────────────────────────────────
-- One row per (group, period). Captures the Liability for Incurred Claims
-- movement during the period. PAA does not exempt LIC from discounting:
-- claims expected to settle within 1 year may be undiscounted (§59(b)),
-- but longer-tail LIC must be discounted unless materiality permits. The
-- tenant's election lives on paa_config.discount_lic; the LicEngine
-- (slice 2.4) reads it per posting.
--   opening_balance + claims_incurred - claims_paid
--                  + case_reserve_change + ibnr_change + risk_adjustment_change
--                  + discount_unwind
--     = closing_balance
-- Same code-enforced-identity / DB-enforced-non-negativity pattern as paa_lrc.
CREATE TABLE paa_lic (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    group_id                    UUID            NOT NULL,
    period_id                   UUID            NOT NULL,
    opening_balance             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    claims_incurred             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    claims_paid                 DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    case_reserve_change         DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    ibnr_estimate               DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    ibnr_change                 DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    risk_adjustment             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    risk_adjustment_change      DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    discount_unwind             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    closing_balance             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    currency_code               VARCHAR(3)      NOT NULL DEFAULT 'NGN',

    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ,

    CONSTRAINT pk_paa_lic PRIMARY KEY (id),
    CONSTRAINT fk_paa_lic_group  FOREIGN KEY (group_id)  REFERENCES group_of_contracts (id),
    CONSTRAINT fk_paa_lic_period FOREIGN KEY (period_id) REFERENCES fiscal_period (id),
    CONSTRAINT uq_paa_lic_group_period UNIQUE (group_id, period_id),
    CONSTRAINT ck_paa_lic_nonneg CHECK (
        opening_balance >= 0
        AND claims_incurred >= 0
        AND claims_paid >= 0
        AND ibnr_estimate >= 0
        AND risk_adjustment >= 0
        AND closing_balance >= 0
    )
);

CREATE INDEX idx_paa_lic_group  ON paa_lic (group_id)  WHERE deleted_at IS NULL;
CREATE INDEX idx_paa_lic_period ON paa_lic (period_id) WHERE deleted_at IS NULL;

-- ── 5. PAA accounting policy (singleton per tenant) ───────────────────────────
-- The tenant's IFRS 17 accounting policy elections. Singleton enforced by
-- partial unique index on a literal — the same pattern customer_number_format
-- uses for "at most one row". The PaaConfigService (slice 2.x) reads this on
-- every measurement run; defaults match the most common GB carrier setup
-- (no LIC discounting, no OCI election, 75% confidence-level RA, expense-as-
-- incurred acquisition costs).
CREATE TABLE paa_config (
    id                              UUID            NOT NULL DEFAULT gen_random_uuid(),
    singleton_marker                BOOLEAN         NOT NULL DEFAULT TRUE,
    discount_lic                    BOOLEAN         NOT NULL DEFAULT FALSE,
    discount_rate                   DECIMAL(8, 5),
    oci_election                    BOOLEAN         NOT NULL DEFAULT FALSE,
    ra_method                       VARCHAR(40)     NOT NULL DEFAULT 'CONFIDENCE_LEVEL',
    ra_confidence_level             DECIMAL(5, 2),
    acquisition_cashflow_method     VARCHAR(40)     NOT NULL DEFAULT 'EXPENSE_AS_INCURRED',

    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                      VARCHAR(100),
    deleted_at                      TIMESTAMPTZ,

    CONSTRAINT pk_paa_config PRIMARY KEY (id),
    CONSTRAINT ck_paa_config_ra_method CHECK (
        ra_method IN ('CONFIDENCE_LEVEL', 'COST_OF_CAPITAL', 'STRESS_TEST')
    ),
    CONSTRAINT ck_paa_config_acq_method CHECK (
        acquisition_cashflow_method IN ('EXPENSE_AS_INCURRED', 'DEFER_AND_AMORTISE')
    ),
    CONSTRAINT ck_paa_config_ra_confidence CHECK (
        ra_confidence_level IS NULL OR (ra_confidence_level > 0 AND ra_confidence_level <= 100)
    ),
    CONSTRAINT ck_paa_config_discount_rate CHECK (
        discount_rate IS NULL OR discount_rate >= 0
    ),
    CONSTRAINT ck_paa_config_discount_consistency CHECK (
        (discount_lic = FALSE) OR (discount_rate IS NOT NULL)
    )
);

-- Singleton enforcement: at most one non-deleted row per tenant schema.
CREATE UNIQUE INDEX uq_paa_config_singleton
    ON paa_config (singleton_marker)
    WHERE deleted_at IS NULL;

-- ── 6. Promote journal_entry_line placeholder FKs ─────────────────────────────
-- V31 shipped portfolio_id + contract_group_id as untyped UUID columns
-- anticipating this slice. Now that the target tables exist, install real
-- FKs. Both columns remain nullable — non-insurance JE lines (treasury,
-- payroll, etc.) carry no IFRS-17 dimension. No CASCADE: master data must
-- not be removable while JEs reference it.
ALTER TABLE journal_entry_line
    ADD CONSTRAINT fk_journal_entry_line_portfolio
        FOREIGN KEY (portfolio_id)      REFERENCES portfolio (id),
    ADD CONSTRAINT fk_journal_entry_line_group
        FOREIGN KEY (contract_group_id) REFERENCES group_of_contracts (id);

COMMENT ON TABLE portfolio IS
    'IFRS 17 portfolio — set of contracts subject to similar risks and managed '
    'together (§14). Master data; one row per risk-similar product/channel '
    'combination; FK target for journal_entry_line.portfolio_id and Phase 2 '
    'measurement tables.';

COMMENT ON TABLE group_of_contracts IS
    'IFRS 17 group of contracts — annual cohort × onerousness bucket within a '
    'portfolio (§16-22). The smallest unit of account for PAA measurement. '
    'Contract assignment at initial recognition is permanent — onerousness is '
    'never re-evaluated by moving contracts between groups; loss components '
    'are recognised on the assigned group instead.';

COMMENT ON TABLE paa_lrc IS
    'Liability for Remaining Coverage roll-forward per (group, period) under '
    'PAA. The LrcEngine (Slice 2.3) writes one row per group per closed '
    'period as part of the period-end JE batch.';

COMMENT ON TABLE paa_lic IS
    'Liability for Incurred Claims roll-forward per (group, period) under '
    'PAA. The LicEngine (Slice 2.4) writes one row per group per closed '
    'period as part of the period-end JE batch.';

COMMENT ON TABLE paa_config IS
    'Per-tenant IFRS 17 accounting policy elections — discounting on/off, OCI '
    'election, risk-adjustment method, acquisition cashflow treatment. '
    'Singleton enforced by partial unique index on singleton_marker.';
