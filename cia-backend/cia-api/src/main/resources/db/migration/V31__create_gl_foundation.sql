-- ─────────────────────────────────────────────────────────────────────────────
-- V31 — General Ledger Foundation
--
-- Module 12 (Period-End Closures) Slice 1.1 — schema only, no runtime behaviour.
-- Adds the seven GL tables that every later closure slice posts against:
--   1. chart_of_account            (COA hierarchy with leaf-only posting policy)
--   2. fiscal_year                 (per-tenant fiscal year configuration)
--   3. fiscal_period               (DAY/MONTH/QUARTER/HALF_YEAR/YEAR child periods)
--   4. period_lock                 (soft/hard close enforcement records)
--   5. journal_entry               (posted JEs with two-date model)
--   6. journal_entry_line          (debit/credit lines with promoted dimensions)
--   7. posting_rule                (sub-ledger event → JE template mapping)
--
-- Money columns: DECIMAL(18,2) — matches existing cia-finance tables.
-- Soft-delete pattern: deleted_at TIMESTAMPTZ + partial indexes.
-- Constraint naming: pk_*, uq_*, fk_*, ck_*.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. Chart of accounts ──────────────────────────────────────────────────────
-- Hierarchical COA: parent_id nullable, arbitrary depth. Leaf-only posting is
-- enforced at the JournalEntryService layer (slice 1.4), not by DB CHECK, to
-- leave room for one-off platform overrides. Each row carries optional
-- IFRS 17 / IFRS 9 disclosure roles for downstream measurement modules.
CREATE TABLE chart_of_account (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    code            VARCHAR(20)     NOT NULL,
    name            VARCHAR(200)    NOT NULL,
    account_type    VARCHAR(20)     NOT NULL,
    parent_id       UUID,
    ifrs17_role     VARCHAR(50),
    ifrs9_role      VARCHAR(50),
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(100),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT pk_chart_of_account PRIMARY KEY (id),
    CONSTRAINT uq_chart_of_account_code UNIQUE (code),
    CONSTRAINT fk_chart_of_account_parent FOREIGN KEY (parent_id) REFERENCES chart_of_account (id),
    CONSTRAINT ck_chart_of_account_type CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE'))
);

CREATE INDEX idx_chart_of_account_parent ON chart_of_account (parent_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_chart_of_account_type   ON chart_of_account (account_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_chart_of_account_ifrs17 ON chart_of_account (ifrs17_role)  WHERE deleted_at IS NULL AND ifrs17_role IS NOT NULL;
CREATE INDEX idx_chart_of_account_ifrs9  ON chart_of_account (ifrs9_role)   WHERE deleted_at IS NULL AND ifrs9_role IS NOT NULL;

-- ── 2. Fiscal year ────────────────────────────────────────────────────────────
-- Tenant-configurable fiscal year. Default Dec 31 year-end is created via the
-- FiscalYearService (slice 1.6), not seeded here. Only one ACTIVE fiscal year
-- at a time per tenant — enforced by the service, not by DB constraint, since
-- the activation flow needs to deactivate the prior year atomically.
CREATE TABLE fiscal_year (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    name            VARCHAR(50)     NOT NULL,
    start_date      DATE            NOT NULL,
    end_date        DATE            NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PLANNING',

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(100),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT pk_fiscal_year PRIMARY KEY (id),
    CONSTRAINT uq_fiscal_year_name UNIQUE (name),
    CONSTRAINT ck_fiscal_year_status CHECK (status IN ('PLANNING','ACTIVE','CLOSED')),
    CONSTRAINT ck_fiscal_year_dates CHECK (end_date > start_date)
);

CREATE INDEX idx_fiscal_year_status ON fiscal_year (status) WHERE deleted_at IS NULL;
CREATE INDEX idx_fiscal_year_dates  ON fiscal_year (start_date, end_date) WHERE deleted_at IS NULL;

-- ── 3. Fiscal period ──────────────────────────────────────────────────────────
-- Child periods of a fiscal year. period_type defines granularity. MONTH +
-- QUARTER + HALF_YEAR + YEAR rows are generated eagerly on fiscal year
-- activation; DAY rows are generated lazily on first reference (avoids
-- 365-row clutter for a feature most tenants will rarely use).
-- ck_fiscal_period_close_chronology guards against hard-close-before-soft.
CREATE TABLE fiscal_period (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    fiscal_year_id  UUID            NOT NULL,
    period_type     VARCHAR(20)     NOT NULL,
    start_date      DATE            NOT NULL,
    end_date        DATE            NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    soft_closed_at  TIMESTAMPTZ,
    hard_closed_at  TIMESTAMPTZ,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(100),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT pk_fiscal_period PRIMARY KEY (id),
    CONSTRAINT fk_fiscal_period_year FOREIGN KEY (fiscal_year_id) REFERENCES fiscal_year (id),
    CONSTRAINT uq_fiscal_period_year_type_start UNIQUE (fiscal_year_id, period_type, start_date),
    CONSTRAINT ck_fiscal_period_type CHECK (period_type IN ('DAY','MONTH','QUARTER','HALF_YEAR','YEAR')),
    CONSTRAINT ck_fiscal_period_status CHECK (status IN ('OPEN','SOFT_CLOSED','HARD_CLOSED','REOPENED')),
    CONSTRAINT ck_fiscal_period_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_fiscal_period_close_chronology CHECK (
        (hard_closed_at IS NULL) OR (soft_closed_at IS NOT NULL AND hard_closed_at >= soft_closed_at)
    )
);

CREATE INDEX idx_fiscal_period_year   ON fiscal_period (fiscal_year_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_fiscal_period_dates  ON fiscal_period (start_date, end_date) WHERE deleted_at IS NULL;
CREATE INDEX idx_fiscal_period_lookup ON fiscal_period (period_type, start_date, end_date) WHERE deleted_at IS NULL;
CREATE INDEX idx_fiscal_period_status ON fiscal_period (status) WHERE deleted_at IS NULL;

-- ── 4. Period lock ────────────────────────────────────────────────────────────
-- Lock records track soft/hard close events. lock_type=SOFT begins the
-- 5-business-day grace window (grace_window_until); lock_type=HARD is final
-- unless explicitly released via released_at + released_by + release_reason.
-- ck_period_lock_release ensures all three release columns are set together
-- or all unset together — no half-released rows.
CREATE TABLE period_lock (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    fiscal_period_id    UUID            NOT NULL,
    lock_type           VARCHAR(10)     NOT NULL,
    locked_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    locked_by           VARCHAR(100)    NOT NULL,
    grace_window_until  TIMESTAMPTZ,
    released_at         TIMESTAMPTZ,
    released_by         VARCHAR(100),
    release_reason      TEXT,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT pk_period_lock PRIMARY KEY (id),
    CONSTRAINT fk_period_lock_period FOREIGN KEY (fiscal_period_id) REFERENCES fiscal_period (id),
    CONSTRAINT ck_period_lock_type CHECK (lock_type IN ('SOFT','HARD')),
    CONSTRAINT ck_period_lock_release CHECK (
        (released_at IS NULL AND released_by IS NULL AND release_reason IS NULL) OR
        (released_at IS NOT NULL AND released_by IS NOT NULL AND release_reason IS NOT NULL)
    )
);

CREATE INDEX idx_period_lock_period ON period_lock (fiscal_period_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_period_lock_active ON period_lock (fiscal_period_id, lock_type) WHERE deleted_at IS NULL AND released_at IS NULL;

-- ── 5. Journal entry (header) ─────────────────────────────────────────────────
-- Two-date model: posting_date = recording timestamp; business_date =
-- economic transaction date used for period assignment + 5-business-day cutoff.
-- ck_journal_entry_dates enforces business_date <= posting_date (the universal
-- accounting invariant preventing future-dated postings).
-- period_id derives from business_date via FiscalPeriodService.findPeriod(...).
-- (source_module, source_event_type, source_reference) is the idempotency key
-- the SubledgerPostingService listeners (slice 1.5) check before posting,
-- enforced as a DB UNIQUE so a duplicate insert errors at DB level — closes
-- the TOCTOU race window that a service-only existence check leaves open.
CREATE TABLE journal_entry (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    posting_date        DATE            NOT NULL DEFAULT current_date,
    business_date       DATE            NOT NULL,
    period_id           UUID            NOT NULL,
    source_module       VARCHAR(40)     NOT NULL,
    source_event_type   VARCHAR(60)     NOT NULL,
    source_reference    VARCHAR(100)    NOT NULL,
    narrative           TEXT,
    posted_by           VARCHAR(100)    NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'POSTED',
    reversal_of         UUID,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT pk_journal_entry PRIMARY KEY (id),
    CONSTRAINT fk_journal_entry_period FOREIGN KEY (period_id) REFERENCES fiscal_period (id),
    CONSTRAINT fk_journal_entry_reversal FOREIGN KEY (reversal_of) REFERENCES journal_entry (id),
    CONSTRAINT uq_journal_entry_idempotency UNIQUE (source_module, source_event_type, source_reference),
    CONSTRAINT ck_journal_entry_status CHECK (status IN ('DRAFT','POSTED','REVERSED')),
    CONSTRAINT ck_journal_entry_dates CHECK (business_date <= posting_date)
);

CREATE INDEX idx_journal_entry_business_date ON journal_entry (business_date) WHERE deleted_at IS NULL;
CREATE INDEX idx_journal_entry_posting_date  ON journal_entry (posting_date)  WHERE deleted_at IS NULL;
CREATE INDEX idx_journal_entry_period        ON journal_entry (period_id)     WHERE deleted_at IS NULL;
CREATE INDEX idx_journal_entry_source        ON journal_entry (source_module, source_reference) WHERE deleted_at IS NULL;
CREATE INDEX idx_journal_entry_status        ON journal_entry (status) WHERE deleted_at IS NULL;

-- ── 6. Journal entry line ─────────────────────────────────────────────────────
-- Two-column debit/credit representation: exactly one of debit_amount /
-- credit_amount must be > 0 per line (ck_journal_entry_line_amount).
-- Promoted dimension columns: cohort_year for IFRS 17 cohort accounting;
-- portfolio_id + contract_group_id for IFRS 17 group-level roll-ups; holding_id
-- for IFRS 9 per-instrument ECL/income roll-ups. dimension_tags JSONB carries
-- any other ad-hoc analytic tags. FKs to portfolio/contract_group/holding are
-- declared in their own slice migrations (V34, V37) since those tables don't
-- exist yet — the columns are typed UUID to receive them later.
-- Active-account enforcement (rejecting posts to is_active=FALSE accounts) is
-- a service-layer rule in JournalEntryService, not a DB trigger — activity is
-- a tenant policy, not a structural integrity invariant.
CREATE TABLE journal_entry_line (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    journal_entry_id    UUID            NOT NULL,
    line_no             INT             NOT NULL,
    account_id          UUID            NOT NULL,
    debit_amount        DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    credit_amount       DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    currency_code       VARCHAR(3)      NOT NULL DEFAULT 'NGN',
    cohort_year         INT,
    portfolio_id        UUID,
    contract_group_id   UUID,
    holding_id          UUID,
    dimension_tags      JSONB           NOT NULL DEFAULT '{}'::jsonb,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT pk_journal_entry_line PRIMARY KEY (id),
    CONSTRAINT fk_journal_entry_line_je FOREIGN KEY (journal_entry_id) REFERENCES journal_entry (id) ON DELETE CASCADE,
    CONSTRAINT fk_journal_entry_line_account FOREIGN KEY (account_id) REFERENCES chart_of_account (id),
    CONSTRAINT uq_journal_entry_line_no UNIQUE (journal_entry_id, line_no),
    CONSTRAINT ck_journal_entry_line_amount CHECK (
        (debit_amount > 0 AND credit_amount = 0) OR
        (debit_amount = 0 AND credit_amount > 0)
    ),
    CONSTRAINT ck_journal_entry_line_amount_nonneg CHECK (debit_amount >= 0 AND credit_amount >= 0)
);

CREATE INDEX idx_journal_entry_line_je        ON journal_entry_line (journal_entry_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_journal_entry_line_account   ON journal_entry_line (account_id)       WHERE deleted_at IS NULL;
CREATE INDEX idx_journal_entry_line_portfolio ON journal_entry_line (portfolio_id)     WHERE deleted_at IS NULL AND portfolio_id IS NOT NULL;
CREATE INDEX idx_journal_entry_line_group     ON journal_entry_line (contract_group_id) WHERE deleted_at IS NULL AND contract_group_id IS NOT NULL;
CREATE INDEX idx_journal_entry_line_holding   ON journal_entry_line (holding_id)       WHERE deleted_at IS NULL AND holding_id IS NOT NULL;
CREATE INDEX idx_journal_entry_line_cohort    ON journal_entry_line (cohort_year)      WHERE deleted_at IS NULL AND cohort_year IS NOT NULL;
CREATE INDEX idx_journal_entry_line_tags      ON journal_entry_line USING GIN (dimension_tags);

-- ── 7. Posting rule ───────────────────────────────────────────────────────────
-- Mapping from sub-ledger event types (DebitNoteApprovedEvent,
-- CreditNoteIssuedEvent, ReceiptPostedEvent, PaymentPostedEvent — slice 1.5)
-- to the COA accounts to debit and credit. narrative_template uses a simple
-- placeholder syntax interpolated by SubledgerPostingService at post time.
-- FK references chart_of_account.code (UNIQUE) rather than .id so seed
-- migrations stay readable and reviewable.
CREATE TABLE posting_rule (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    source_event_type       VARCHAR(60)     NOT NULL,
    debit_account_code      VARCHAR(20)     NOT NULL,
    credit_account_code     VARCHAR(20)     NOT NULL,
    narrative_template      TEXT,
    is_active               BOOLEAN         NOT NULL DEFAULT TRUE,

    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by              VARCHAR(100),
    deleted_at              TIMESTAMPTZ,

    CONSTRAINT pk_posting_rule PRIMARY KEY (id),
    CONSTRAINT uq_posting_rule_event UNIQUE (source_event_type),
    CONSTRAINT fk_posting_rule_debit  FOREIGN KEY (debit_account_code)  REFERENCES chart_of_account (code),
    CONSTRAINT fk_posting_rule_credit FOREIGN KEY (credit_account_code) REFERENCES chart_of_account (code),
    CONSTRAINT ck_posting_rule_distinct_accounts CHECK (debit_account_code <> credit_account_code)
);

CREATE INDEX idx_posting_rule_event ON posting_rule (source_event_type) WHERE deleted_at IS NULL AND is_active = TRUE;
