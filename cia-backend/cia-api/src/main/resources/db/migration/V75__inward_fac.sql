-- ─────────────────────────────────────────────────────────────────────────────
-- V75 — Inward Facultative Reinsurance (Module 6)
--
-- New aggregate mirroring ri_fac_covers (outward) but inward semantics: no
-- policy_id (the risk originates outside CIAGB — a ceding insurer's own
-- policy), ceding-insurer counterparty (not a reinsurer), and receivable
-- direction (premium is owed TO us, not ceded away).
--
-- Column style / idioms copied from V10__create_reinsurance_tables.sql
-- (ri_fac_covers / ri_fac_counters): DECIMAL money/rate precision, TIMESTAMPTZ
-- audit columns defaulted via now(), named PK/UNIQUE constraints, partial
-- indexes filtered on deleted_at IS NULL.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Inward FAC covers ────────────────────────────────────────────────────
CREATE TABLE ri_fac_inwards (
    id                       UUID            NOT NULL DEFAULT gen_random_uuid(),
    fac_inward_reference     VARCHAR(50)     NOT NULL,
    ceding_company_id        UUID            NOT NULL,
    ceding_company_name      VARCHAR(200)    NOT NULL,
    class_of_business_id     UUID            NOT NULL,
    class_of_business_name   VARCHAR(200)    NOT NULL,
    risk_description         TEXT,
    status                   VARCHAR(30)     NOT NULL,

    sum_insured               DECIMAL(18, 2)  NOT NULL,
    our_share_pct             DECIMAL(7, 4)   NOT NULL,
    accepted_sum_insured      DECIMAL(18, 2)  NOT NULL,
    premium_rate              DECIMAL(10, 6)  NOT NULL,
    gross_premium             DECIMAL(18, 2)  NOT NULL,
    commission_rate           DECIMAL(7, 4)   NOT NULL DEFAULT 0,
    commission_amount         DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    net_premium                DECIMAL(18, 2)  NOT NULL,

    currency_code               VARCHAR(3)      NOT NULL DEFAULT 'NGN',
    cover_from                  DATE            NOT NULL,
    cover_to                    DATE            NOT NULL,

    renewed_from_id              UUID            REFERENCES ri_fac_inwards (id),
    guaranty_document_path        VARCHAR(500),

    cancelled_by                   VARCHAR(100),
    cancelled_at                   TIMESTAMPTZ,
    cancellation_reason            TEXT,

    -- BaseEntity
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                      VARCHAR(100),
    deleted_at                      TIMESTAMPTZ,

    CONSTRAINT pk_ri_fac_inwards          PRIMARY KEY (id),
    CONSTRAINT uq_ri_fac_inward_reference UNIQUE (fac_inward_reference)
);

CREATE INDEX idx_ri_fac_inwards_ceding_company ON ri_fac_inwards (ceding_company_id)    WHERE deleted_at IS NULL;
CREATE INDEX idx_ri_fac_inwards_class          ON ri_fac_inwards (class_of_business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_ri_fac_inwards_status         ON ri_fac_inwards (status)               WHERE deleted_at IS NULL;
CREATE INDEX idx_ri_fac_inwards_cover_from     ON ri_fac_inwards (cover_from)           WHERE deleted_at IS NULL;

-- ── Inward FAC reference counter (mirrors ri_fac_counters) ──────────────────
CREATE TABLE ri_fac_inward_counters (
    year            INT     NOT NULL,
    last_sequence   BIGINT  NOT NULL DEFAULT 0,
    CONSTRAINT pk_ri_fac_inward_counters PRIMARY KEY (year)
);

-- ── Inward FAC income/expense COA accounts ───────────────────────────────────
-- The inward receivable (1330) and inward LRC/LIC liabilities (2210/2220)
-- already exist from V32 (Session 56 scope decision R1=A); only the income +
-- expense side is missing. Column list matches V32's chart_of_account INSERT
-- exactly: (code, name, account_type, parent_id, ifrs17_role, ifrs9_role,
-- created_by) — parent_id is a UUID FK, resolved here via a scalar subquery
-- on the parent's code (same effect as V32's derived-table + JOIN pattern).
INSERT INTO chart_of_account (code, name, account_type, parent_id, ifrs17_role, ifrs9_role, created_by)
VALUES
    ('4330', 'Inward reinsurance premium income',
     'INCOME',  (SELECT id FROM chart_of_account WHERE code = '4300'), NULL, NULL, 'system-seed'),
    ('5240', 'Inward reinsurance commission expense',
     'EXPENSE', (SELECT id FROM chart_of_account WHERE code = '5200'), NULL, NULL, 'system-seed')
ON CONFLICT (code) DO NOTHING;
