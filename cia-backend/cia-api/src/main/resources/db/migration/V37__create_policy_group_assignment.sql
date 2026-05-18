-- ─────────────────────────────────────────────────────────────────────────────
-- V37 — Policy → IFRS 17 Group Assignment
--
-- Module 12 (Period-End Closures) Phase 2 Slice 2.2 — schema only.
-- Adds the link table the ContractGroupingService writes to on every
-- PolicyApprovedEvent: it records which group_of_contracts a policy was
-- assigned to at initial recognition.
--
-- Why a separate table (not a column on policies):
--   • cia-policy owns the policies table; cia-finance writes IFRS-17
--     metadata. A column would force cross-module writes — same anti-
--     pattern already avoided by journal_entry_line.portfolio_id /
--     contract_group_id (also owned by cia-finance).
--   • Per IFRS 17 §22, assignment is permanent at initial recognition.
--     Storing it as its own row (with assigned_at, created_by) gives a
--     natural audit point and a clean place to add Type-2 SCD columns
--     later if reclassification (audit correction) is ever needed.
--
-- UNIQUE(policy_id) is the idempotency key: re-firing PolicyApprovedEvent
-- against an already-assigned policy attempts an INSERT that the DB
-- rejects, and the service short-circuits.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE policy_group_assignment (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    policy_id       UUID            NOT NULL,
    group_id        UUID            NOT NULL,
    assigned_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(100),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT pk_policy_group_assignment PRIMARY KEY (id),
    CONSTRAINT uq_policy_group_assignment_policy UNIQUE (policy_id),
    CONSTRAINT fk_policy_group_assignment_policy FOREIGN KEY (policy_id) REFERENCES policies (id),
    CONSTRAINT fk_policy_group_assignment_group  FOREIGN KEY (group_id)  REFERENCES group_of_contracts (id)
);

CREATE INDEX idx_policy_group_assignment_group ON policy_group_assignment (group_id) WHERE deleted_at IS NULL;

COMMENT ON TABLE policy_group_assignment IS
    'Records the IFRS 17 §22 permanent assignment of a policy to a group of '
    'contracts (portfolio × cohort_year × onerousness) at initial '
    'recognition. Written by ContractGroupingService (Slice 2.2) on '
    'PolicyApprovedEvent. UNIQUE(policy_id) makes the listener naturally '
    'idempotent — a duplicate-event-re-fire hits the constraint and the '
    'service short-circuits.';
