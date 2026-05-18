-- ─────────────────────────────────────────────────────────────────────────────
-- V39 — IFRS 9 Foundation
--
-- Module 12 (Period-End Closures) Phase 3 Slice 3.1 — schema only.
-- The IFRS 9 measurement engines in slices 3.2–3.7 write against the tables
-- created here:
--   1. investment_holding              (one row per financial asset held)
--   2. investment_carrying_value       (period-end roll-forward per holding)
--   3. investment_classification_history (Type-2 SCD for §B4.1.26 reclassifications)
--   4. ifrs9_config                    (per-tenant accounting policy)
-- Plus FK promotion on journal_entry_line.holding_id (placeholder from V31).
--
-- COA accounts for IFRS 9 already exist (V32 seed). Slice 3.1 does not seed
-- any new accounts.
--
-- Money columns: DECIMAL(18,2) — matches V31/V36.
-- Soft-delete pattern: deleted_at TIMESTAMPTZ + partial indexes.
-- Constraint naming: pk_*, uq_*, fk_*, ck_*.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. Investment holding ─────────────────────────────────────────────────────
-- One row per financial asset held by the insurer. ISIN is nullable for
-- unlisted instruments (bilateral loans, money-market placements). The
-- classification column is set by InvestmentClassificationService (Slice 3.2)
-- based on SPPI test + business model; subsequent reclassifications go into
-- investment_classification_history as a Type-2 audit trail rather than
-- mutating this row (per §B4.1.26-B4.1.29 reclassification rarity).
CREATE TABLE investment_holding (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    isin                VARCHAR(12),
    security_name       VARCHAR(200)    NOT NULL,
    issuer              VARCHAR(200),
    asset_type          VARCHAR(20)     NOT NULL,
    classification      VARCHAR(20)     NOT NULL,
    acquisition_date    DATE            NOT NULL,
    acquisition_cost    DECIMAL(18, 2)  NOT NULL,
    face_value          DECIMAL(18, 2),
    coupon_rate         DECIMAL(8, 5),
    maturity_date       DATE,
    currency_code       VARCHAR(3)      NOT NULL DEFAULT 'NGN',
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    sppi_test_passed    BOOLEAN,
    ecl_stage           INT,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT pk_investment_holding PRIMARY KEY (id),
    CONSTRAINT ck_investment_asset_type CHECK (
        asset_type IN ('DEBT', 'EQUITY', 'MONEY_MARKET', 'DERIVATIVE')
    ),
    CONSTRAINT ck_investment_classification CHECK (
        classification IN ('AMORTISED_COST', 'FVOCI_DEBT', 'FVOCI_EQUITY', 'FVPL')
    ),
    CONSTRAINT ck_investment_status CHECK (
        status IN ('ACTIVE', 'MATURED', 'SOLD', 'IMPAIRED')
    ),
    CONSTRAINT ck_investment_ecl_stage CHECK (
        ecl_stage IS NULL OR ecl_stage BETWEEN 1 AND 3
    ),
    -- Equity instruments have no maturity and no coupon. Debt instruments
    -- have a face value. Money-market instruments may have either or both.
    CONSTRAINT ck_investment_equity_no_maturity CHECK (
        asset_type != 'EQUITY' OR (maturity_date IS NULL AND coupon_rate IS NULL)
    ),
    CONSTRAINT ck_investment_acquisition_cost_nonneg CHECK (acquisition_cost >= 0)
);

CREATE INDEX idx_investment_isin           ON investment_holding (isin)           WHERE deleted_at IS NULL AND isin IS NOT NULL;
CREATE INDEX idx_investment_asset_type     ON investment_holding (asset_type)     WHERE deleted_at IS NULL;
CREATE INDEX idx_investment_classification ON investment_holding (classification) WHERE deleted_at IS NULL;
CREATE INDEX idx_investment_status         ON investment_holding (status)         WHERE deleted_at IS NULL;
CREATE INDEX idx_investment_ecl_stage      ON investment_holding (ecl_stage)      WHERE deleted_at IS NULL AND ecl_stage IS NOT NULL;

-- ── 2. Investment carrying value (period-end snapshot) ───────────────────────
-- One row per (holding, period) capturing the period's roll-forward:
--   opening_balance
--     + effective_interest_income           (AC / FVOCI debt — IFRS 9 §5.4.1)
--     + coupon_received                     (debt only)
--     + fair_value_change_pnl               (FVPL only — §5.7.1)
--     + fair_value_change_oci               (FVOCI — §5.7.10/§5.7.5)
--     − ecl_movement                        (AC / FVOCI debt — §5.5.1)
--     − impairment_loss                     (rare — direct write-down)
--     − disposals                           (sales / maturities)
--     = closing_balance
--
-- The DB enforces non-negativity on stocks; deltas can be signed. The engines
-- enforce the roll-forward identity in code (mirrors paa_lrc / paa_lic).
CREATE TABLE investment_carrying_value (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    holding_id                  UUID            NOT NULL,
    period_id                   UUID            NOT NULL,
    opening_balance             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    effective_interest_income   DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    coupon_received             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    fair_value_change_pnl       DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    fair_value_change_oci       DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    ecl_movement                DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    impairment_loss             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    disposals                   DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    closing_balance             DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    closing_fair_value          DECIMAL(18, 2),
    ecl_stage                   INT,
    currency_code               VARCHAR(3)      NOT NULL DEFAULT 'NGN',

    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ,

    CONSTRAINT pk_investment_carrying_value PRIMARY KEY (id),
    CONSTRAINT fk_investment_carrying_holding FOREIGN KEY (holding_id) REFERENCES investment_holding (id),
    CONSTRAINT fk_investment_carrying_period  FOREIGN KEY (period_id)  REFERENCES fiscal_period (id),
    CONSTRAINT uq_investment_carrying_holding_period UNIQUE (holding_id, period_id),
    CONSTRAINT ck_investment_carrying_nonneg CHECK (
        opening_balance >= 0
        AND coupon_received >= 0
        AND impairment_loss >= 0
        AND disposals >= 0
        AND closing_balance >= 0
    ),
    CONSTRAINT ck_investment_carrying_ecl_stage CHECK (
        ecl_stage IS NULL OR ecl_stage BETWEEN 1 AND 3
    )
);

CREATE INDEX idx_investment_carrying_holding ON investment_carrying_value (holding_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_investment_carrying_period  ON investment_carrying_value (period_id)  WHERE deleted_at IS NULL;

-- ── 3. Investment classification history (Type-2 SCD audit) ──────────────────
-- IFRS 9 §B4.1.26-B4.1.29: reclassification between AC / FVOCI / FVPL is
-- only allowed when the business model itself changes — a rare event
-- auditors heavily scrutinise. When it happens, the current
-- investment_holding.classification is updated AND a row is inserted here
-- recording the previous classification, the reason, and the approver.
--
-- For routine state (the holding's current classification), readers query
-- investment_holding.classification directly. This table is purely the
-- audit trail.
CREATE TABLE investment_classification_history (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    holding_id                  UUID            NOT NULL,
    previous_classification     VARCHAR(20)     NOT NULL,
    new_classification          VARCHAR(20)     NOT NULL,
    reclassification_date       DATE            NOT NULL,
    reason                      TEXT            NOT NULL,
    approved_by                 VARCHAR(100)    NOT NULL,

    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ,

    CONSTRAINT pk_investment_classification_history PRIMARY KEY (id),
    CONSTRAINT fk_investment_classification_history_holding FOREIGN KEY (holding_id) REFERENCES investment_holding (id),
    CONSTRAINT ck_investment_classification_history_previous CHECK (
        previous_classification IN ('AMORTISED_COST', 'FVOCI_DEBT', 'FVOCI_EQUITY', 'FVPL')
    ),
    CONSTRAINT ck_investment_classification_history_new CHECK (
        new_classification IN ('AMORTISED_COST', 'FVOCI_DEBT', 'FVOCI_EQUITY', 'FVPL')
    ),
    CONSTRAINT ck_investment_classification_history_distinct CHECK (
        previous_classification != new_classification
    )
);

CREATE INDEX idx_investment_classification_history_holding
    ON investment_classification_history (holding_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_investment_classification_history_date
    ON investment_classification_history (reclassification_date) WHERE deleted_at IS NULL;

-- ── 4. IFRS 9 accounting-policy config (singleton per tenant) ────────────────
-- ECL method (SIMPLIFIED for premium receivables vs GENERAL 3-stage for
-- investment debt), SICR threshold for stage 1→2 transition, FVOCI equity
-- election irrevocability marker. Singleton enforced by partial unique index
-- on singleton_marker — same pattern as paa_config (V36).
CREATE TABLE ifrs9_config (
    id                                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    singleton_marker                        BOOLEAN         NOT NULL DEFAULT TRUE,
    investment_ecl_method                   VARCHAR(20)     NOT NULL DEFAULT 'GENERAL',
    receivable_ecl_method                   VARCHAR(20)     NOT NULL DEFAULT 'SIMPLIFIED',
    sicr_threshold_pd_increase              DECIMAL(5, 2),
    sicr_threshold_days_past_due            INT,
    default_threshold_days_past_due         INT             NOT NULL DEFAULT 90,
    fvoci_equity_election_active            BOOLEAN         NOT NULL DEFAULT FALSE,

    created_at                              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                              VARCHAR(100),
    deleted_at                              TIMESTAMPTZ,

    CONSTRAINT pk_ifrs9_config PRIMARY KEY (id),
    CONSTRAINT ck_ifrs9_config_investment_ecl_method CHECK (
        investment_ecl_method IN ('GENERAL', 'SIMPLIFIED')
    ),
    CONSTRAINT ck_ifrs9_config_receivable_ecl_method CHECK (
        receivable_ecl_method IN ('GENERAL', 'SIMPLIFIED')
    ),
    CONSTRAINT ck_ifrs9_config_sicr_pd CHECK (
        sicr_threshold_pd_increase IS NULL
            OR (sicr_threshold_pd_increase > 0 AND sicr_threshold_pd_increase <= 100)
    ),
    CONSTRAINT ck_ifrs9_config_sicr_dpd CHECK (
        sicr_threshold_days_past_due IS NULL OR sicr_threshold_days_past_due >= 0
    ),
    CONSTRAINT ck_ifrs9_config_default_dpd CHECK (default_threshold_days_past_due >= 0)
);

-- Singleton enforcement: at most one non-deleted row per tenant schema.
CREATE UNIQUE INDEX uq_ifrs9_config_singleton
    ON ifrs9_config (singleton_marker)
    WHERE deleted_at IS NULL;

-- ── 5. Promote journal_entry_line.holding_id placeholder FK ──────────────────
-- V31 shipped holding_id as an untyped UUID column anticipating Phase 3. Now
-- that investment_holding exists, install the real FK. The column remains
-- nullable — non-investment JE lines carry no holding dimension. No CASCADE:
-- holdings must not be removable while JEs reference them.
ALTER TABLE journal_entry_line
    ADD CONSTRAINT fk_journal_entry_line_holding
        FOREIGN KEY (holding_id) REFERENCES investment_holding (id);

-- ── Comments for downstream readers ──────────────────────────────────────────
COMMENT ON TABLE investment_holding IS
    'One row per financial asset held by the insurer. IFRS 9 classification '
    '(AC / FVOCI debt / FVOCI equity / FVPL) set on acquisition by '
    'InvestmentClassificationService (Slice 3.2). Reclassifications under '
    '§B4.1.26-B4.1.29 are rare and audited via investment_classification_history.';

COMMENT ON TABLE investment_carrying_value IS
    'Period-end roll-forward per holding under IFRS 9. The engines '
    '(Slices 3.3–3.5) write rows here; the §B5.5.39 disclosure view '
    '(Slice 3.7) consumes them.';

COMMENT ON TABLE investment_classification_history IS
    'Type-2 SCD audit trail for IFRS 9 §B4.1.26-B4.1.29 reclassifications. '
    'Routine state (current classification) lives on investment_holding; '
    'this table captures the rare business-model-change events that move '
    'a holding between AC / FVOCI / FVPL.';

COMMENT ON TABLE ifrs9_config IS
    'Per-tenant IFRS 9 accounting policy elections: ECL method per asset '
    'class, SICR thresholds for stage 1→2 transition, FVOCI equity election '
    'flag. Singleton enforced by partial unique index on singleton_marker.';
