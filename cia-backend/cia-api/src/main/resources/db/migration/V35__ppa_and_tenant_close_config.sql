-- V35 — Slice 1.7c follow-up to V31.
--
-- Adds the schema needed to make period-end closures audit-grade:
--   1) Prior-Period-Adjustment markers on journal_entry so audit-found
--      errors in closed periods land as IAS-8-style adjustments in the
--      currently-open period instead of forcing a reopen.
--   2) tenant_reopen_recipient — per-tenant CFO + compliance distro list
--      that replaces the v1 Spring property
--      cia.finance.period-reopen-recipients. The listener falls back to
--      the property only if no rows exist for the active tenant.
--   3) tenant_holiday — NAICOM working-days calendar so the 5-business-day
--      grace window honours public holidays in addition to weekends.

-- ── 1) Prior-Period-Adjustment markers on journal_entry ──────────────────────
ALTER TABLE journal_entry
    ADD COLUMN prior_period_adjustment        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN prior_period_adjustment_reason TEXT;

COMMENT ON COLUMN journal_entry.prior_period_adjustment IS
    'Flags the JE as an IAS-8-style prior-period adjustment. Set by the dedicated '
    'POST /journal-entries/prior-period-adjustment endpoint (gated by '
    'FINANCE_APPROVE_PPA). Audit reports filter on this column to surface every '
    'PPA without scanning narratives.';

COMMENT ON COLUMN journal_entry.prior_period_adjustment_reason IS
    'Audit-grade reason text for the prior-period adjustment — the narrative '
    'auditors expect when sampling closed periods. Required NOT NULL by the '
    'service-layer validation when prior_period_adjustment = TRUE; not enforced '
    'at the DB level because legitimate non-PPA rows leave this column NULL.';

-- Partial index so PPA-filtered audit queries don't scan the whole table.
CREATE INDEX idx_journal_entry_ppa
    ON journal_entry (business_date)
    WHERE prior_period_adjustment = TRUE;

-- ── 2) tenant_reopen_recipient — per-tenant CFO/compliance distro ────────────
CREATE TABLE tenant_reopen_recipient (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    recipient     VARCHAR(255) NOT NULL,
    role_label    VARCHAR(100),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    deleted_at    TIMESTAMPTZ,

    CONSTRAINT pk_tenant_reopen_recipient PRIMARY KEY (id),
    CONSTRAINT uq_tenant_reopen_recipient UNIQUE (recipient)
);

CREATE INDEX idx_tenant_reopen_recipient_active
    ON tenant_reopen_recipient (active)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE tenant_reopen_recipient IS
    'Per-tenant CFO + compliance email distribution for PeriodReopenedEvent. '
    'PeriodReopenedNotificationListener loads active rows first; falls back to '
    'the cia.finance.period-reopen-recipients Spring property only when no DB '
    'rows are configured. role_label is informational (e.g. ''CFO'', ''Compliance '
    'Officer'') — not used for routing.';

-- ── 3) tenant_holiday — NAICOM working-days calendar ─────────────────────────
CREATE TABLE tenant_holiday (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    holiday_date  DATE         NOT NULL,
    name          VARCHAR(200) NOT NULL,
    recurring     BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    deleted_at    TIMESTAMPTZ,

    CONSTRAINT pk_tenant_holiday PRIMARY KEY (id),
    CONSTRAINT uq_tenant_holiday_date UNIQUE (holiday_date)
);

CREATE INDEX idx_tenant_holiday_date
    ON tenant_holiday (holiday_date)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE tenant_holiday IS
    'NAICOM-aligned public holidays per tenant. PeriodLockService.addBusinessDays '
    'consults this table when computing the 5-business-day grace window after a '
    'soft close. ''recurring'' = TRUE means the date repeats annually (e.g. Jan 1) '
    '— v1 implementation will ignore the year part when matching; reserved for '
    'a future generator that materialises recurring entries into specific years.';
