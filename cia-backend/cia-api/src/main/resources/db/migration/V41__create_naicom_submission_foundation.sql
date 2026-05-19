-- ─────────────────────────────────────────────────────────────────────────────
-- V41 — NAICOM Submission Foundation
--
-- Module 12 (Period-End Closures) Phase 4 Slice 4.1 — schema only.
-- The submission engines in slices 4.2–4.8 generate payloads against these
-- tables; the orchestrator + REST surface in slice 4.9 drives the state
-- machine; slice 4.10 renders + uploads artifacts.
--
-- Tables:
--   1. naicom_submission           (one row per (submission_type, period_id) per tenant)
--   2. naicom_submission_artifact  (PDF / CSV blob references per submission)
--   3. naicom_submission_event     (append-only state-transition history — Type-2 SCD)
--
-- Design invariants this schema establishes (load-bearing):
--   • Submissions are READ-SIDE aggregates over already-posted ledger state.
--     They never post a JE; the JE gateway is not involved.
--   • Idempotency triple = (submission_type, period_id) under
--     deleted_at IS NULL → exactly one live submission per (type, period).
--     Re-running the engine for an existing DRAFT updates the payload.
--     Re-running against a SUBMITTED row is rejected at the service layer
--     (payload is frozen once submitted; auditors require this).
--   • Period-lock precondition: submission generation requires the fiscal
--     period to be HARD_CLOSED. Enforced by NaicomSubmissionService (slice
--     4.9), not by DB constraint — the DB has no view onto the latest
--     period_lock row.
--   • State machine (enforced in service layer):
--          (none) ──► DRAFT ──► SUBMITTED ──► ACKNOWLEDGED ──► ARCHIVED
--                       ▲          │
--                       └──────────┘  (re-generate while DRAFT)
--                                  │
--                                  └──► RETRACTED  (terminal)
--     DB CHECK constraints enforce the static field-required-per-state
--     invariants (e.g. SUBMITTED ⇒ submitted_at NOT NULL); the transition
--     graph itself lives in NaicomSubmissionService.
--   • Audit history is via naicom_submission_event (Type-2 SCD pattern,
--     mirrors period_lock from V31). Append-only, no soft-delete on events.
--
-- Money columns: DECIMAL(18,2) — matches V31/V36/V39.
-- Soft-delete pattern: deleted_at TIMESTAMPTZ + partial indexes.
-- Constraint naming: pk_*, uq_*, fk_*, ck_*.
--
-- N.B. The Slice 1.4 JournalEntryService is NOT touched. Phase 4 has zero
-- write-side ledger impact; that is the entire point of running submissions
-- against HARD_CLOSED periods only.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. NAICOM submission ─────────────────────────────────────────────────────
-- One row per regulator submission instance. The (submission_type, period_id)
-- pair is the idempotency key (partial UNIQUE under deleted_at IS NULL). The
-- payload column holds the engine's structured output as JSONB so the schema
-- itself does not have to evolve every time a submission engine's output
-- shape changes — that's a deliberate trade-off matching paa_config's
-- accounting-policy storage pattern.
--
-- naicom_uid is populated only on transition to ACKNOWLEDGED. The structured
-- payload survives ARCHIVED state — auditors need to retrieve historical
-- submissions years after archival.
CREATE TABLE naicom_submission (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    submission_type     VARCHAR(40)     NOT NULL,
    period_id           UUID            NOT NULL,
    period_start        DATE            NOT NULL,
    period_end          DATE            NOT NULL,
    state               VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    payload             JSONB           NOT NULL DEFAULT '{}'::jsonb,

    -- State transition timestamps. Each becomes NOT NULL only when the
    -- corresponding transition occurs; the CK constraints below enforce that.
    submitted_at        TIMESTAMPTZ,
    submitted_by        VARCHAR(100),
    acknowledged_at     TIMESTAMPTZ,
    acknowledged_by     VARCHAR(100),
    naicom_uid          VARCHAR(64),
    archived_at         TIMESTAMPTZ,
    retracted_at        TIMESTAMPTZ,
    retracted_by        VARCHAR(100),
    retraction_reason   TEXT,
    notes               TEXT,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT pk_naicom_submission PRIMARY KEY (id),
    CONSTRAINT fk_naicom_submission_period
        FOREIGN KEY (period_id) REFERENCES fiscal_period (id),
    -- 10 submission types. Order matches the N01–N08 + 2 IFRS disclosures
    -- enumerated in the Phase 4 plan.
    CONSTRAINT ck_naicom_submission_type CHECK (
        submission_type IN (
            'ANNUAL_REVENUE_ACCOUNT',
            'BALANCE_SHEET',
            'PRUDENTIAL_RETURN',
            'RI_QUARTERLY_RETURN',
            'PREMIUM_BORDEREAUX',
            'CLAIMS_BORDEREAUX',
            'NIID_STATUS_SNAPSHOT',
            'INVESTMENT_STATEMENT',
            'IFRS17_DISCLOSURE',
            'IFRS9_DISCLOSURE'
        )
    ),
    CONSTRAINT ck_naicom_submission_state CHECK (
        state IN ('DRAFT', 'SUBMITTED', 'ACKNOWLEDGED', 'ARCHIVED', 'RETRACTED')
    ),
    -- State ⇒ required-field invariants. The state machine itself
    -- (DRAFT→SUBMITTED→ACKNOWLEDGED→ARCHIVED, with RETRACTED as a terminal
    -- branch off SUBMITTED) is enforced in NaicomSubmissionService.
    CONSTRAINT ck_naicom_submission_submitted_fields CHECK (
        state NOT IN ('SUBMITTED', 'ACKNOWLEDGED', 'ARCHIVED')
        OR (submitted_at IS NOT NULL AND submitted_by IS NOT NULL)
    ),
    CONSTRAINT ck_naicom_submission_acknowledged_fields CHECK (
        state NOT IN ('ACKNOWLEDGED', 'ARCHIVED')
        OR (acknowledged_at IS NOT NULL AND naicom_uid IS NOT NULL)
    ),
    CONSTRAINT ck_naicom_submission_retracted_fields CHECK (
        state != 'RETRACTED'
        OR (retracted_at IS NOT NULL AND retracted_by IS NOT NULL)
    ),
    CONSTRAINT ck_naicom_submission_archived_fields CHECK (
        state != 'ARCHIVED' OR archived_at IS NOT NULL
    ),
    -- Period dates must agree with each other.
    CONSTRAINT ck_naicom_submission_period_range CHECK (period_end >= period_start)
);

-- Idempotency: one live submission per (type, period). DRAFTs can be
-- re-generated in place; once SUBMITTED, the row is the canonical record
-- for that (type, period) forever — re-submission requires a RETRACTION
-- + new submission (which gets a fresh id, satisfying the UNIQUE).
CREATE UNIQUE INDEX uq_naicom_submission_type_period
    ON naicom_submission (submission_type, period_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_naicom_submission_period       ON naicom_submission (period_id)        WHERE deleted_at IS NULL;
CREATE INDEX idx_naicom_submission_state        ON naicom_submission (state)            WHERE deleted_at IS NULL;
CREATE INDEX idx_naicom_submission_submitted_at ON naicom_submission (submitted_at DESC) WHERE deleted_at IS NULL AND submitted_at IS NOT NULL;
CREATE INDEX idx_naicom_submission_naicom_uid   ON naicom_submission (naicom_uid)        WHERE deleted_at IS NULL AND naicom_uid IS NOT NULL;

COMMENT ON TABLE naicom_submission IS
    'Module 12 Phase 4 — one row per (submission_type, period_id) NAICOM '
    'regulator submission. Read-side aggregate; never posts JEs. Generation '
    'requires HARD_CLOSED period (enforced in NaicomSubmissionService).';

-- ── 2. NAICOM submission artifact ────────────────────────────────────────────
-- Each submission can have multiple rendered artifacts (PDF for the auditor
-- canonical form, CSV for NAICOM e-portal ingestion). Storage paths point to
-- DocumentStorageService (MinIO / S3 / etc.). Artifacts are pinned once their
-- parent submission moves out of DRAFT — sha256_hex provides tamper evidence
-- across the storage layer.
CREATE TABLE naicom_submission_artifact (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    submission_id       UUID            NOT NULL,
    format              VARCHAR(10)     NOT NULL,
    storage_path        VARCHAR(500)    NOT NULL,
    size_bytes          BIGINT          NOT NULL,
    sha256_hex          VARCHAR(64)     NOT NULL,
    rendered_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    rendered_by         VARCHAR(100),

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT pk_naicom_submission_artifact PRIMARY KEY (id),
    CONSTRAINT fk_naicom_submission_artifact_submission
        FOREIGN KEY (submission_id) REFERENCES naicom_submission (id) ON DELETE CASCADE,
    CONSTRAINT ck_naicom_submission_artifact_format CHECK (
        format IN ('PDF', 'CSV', 'JSON', 'XML')
    ),
    CONSTRAINT ck_naicom_submission_artifact_size_nonneg CHECK (size_bytes >= 0),
    -- SHA-256 hex is exactly 64 lowercase hex characters.
    CONSTRAINT ck_naicom_submission_artifact_sha256_length CHECK (
        char_length(sha256_hex) = 64
    )
);

-- Exactly one live artifact per (submission, format). Re-rendering replaces
-- via soft-delete + insert so the audit trail of every rendering attempt
-- survives.
CREATE UNIQUE INDEX uq_naicom_submission_artifact_format
    ON naicom_submission_artifact (submission_id, format)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_naicom_submission_artifact_submission
    ON naicom_submission_artifact (submission_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE naicom_submission_artifact IS
    'Rendered PDF / CSV artifacts per submission. Storage paths reference '
    'DocumentStorageService blobs. sha256_hex provides tamper evidence '
    'across the storage layer.';

-- ── 3. NAICOM submission event (Type-2 SCD state history) ────────────────────
-- Append-only state-transition history. NO soft delete — events are immutable
-- audit evidence. Mirrors the period_lock-row-sequence pattern from V31:
-- the row sequence IS the audit history, no separate history table.
--
-- The initial DRAFT-creation event has from_state IS NULL; all subsequent
-- rows have both from_state and to_state populated and the chain
-- naicom_submission_event(submission, occurred_at ASC) must reproduce the
-- state machine path the submission took. Auditors traverse this chain.
CREATE TABLE naicom_submission_event (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    submission_id       UUID            NOT NULL,
    from_state          VARCHAR(20),
    to_state            VARCHAR(20)     NOT NULL,
    reason              TEXT,
    actor               VARCHAR(100)    NOT NULL,
    occurred_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_naicom_submission_event PRIMARY KEY (id),
    CONSTRAINT fk_naicom_submission_event_submission
        FOREIGN KEY (submission_id) REFERENCES naicom_submission (id) ON DELETE CASCADE,
    CONSTRAINT ck_naicom_submission_event_from_state CHECK (
        from_state IS NULL OR from_state IN ('DRAFT', 'SUBMITTED', 'ACKNOWLEDGED', 'ARCHIVED', 'RETRACTED')
    ),
    CONSTRAINT ck_naicom_submission_event_to_state CHECK (
        to_state IN ('DRAFT', 'SUBMITTED', 'ACKNOWLEDGED', 'ARCHIVED', 'RETRACTED')
    ),
    -- An initial-creation event has from_state IS NULL; all transitions
    -- have from_state NOT NULL. A no-op transition (from == to) is allowed
    -- only for DRAFT (re-generation while still in DRAFT updates payload
    -- and produces a DRAFT→DRAFT event capturing the re-generation reason).
    CONSTRAINT ck_naicom_submission_event_no_op_only_draft CHECK (
        from_state IS NULL OR from_state != to_state OR from_state = 'DRAFT'
    )
);

CREATE INDEX idx_naicom_submission_event_submission_occurred
    ON naicom_submission_event (submission_id, occurred_at);

COMMENT ON TABLE naicom_submission_event IS
    'Append-only Type-2 SCD state history for naicom_submission. Row '
    'sequence IS the audit trail. No soft delete — events are immutable.';
