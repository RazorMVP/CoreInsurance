-- ─────────────────────────────────────────────────────────────────────────────
-- V42 — promote class_of_business onto journal_entry_line
--
-- Module 12 (Period-End Closures) Slice 1.10a — GL substrate enrichment.
-- Phase 4 Slice 4.3 (AnnualRevenueAccountEngine / N01) needed per-class
-- breakdown of premium written and claims incurred. It read from `policies`
-- + `claims` directly because `journal_entry_line` had no `class_of_business`
-- dimension. This migration removes that gap.
--
-- After this migration + the matching SubledgerPostingService refactor in
-- the same slice, every newly posted JE for a class-bearing event
-- (PolicyApproved, ClaimApproved, ClaimSettled, ClaimExpenseApproved,
-- EndorsementApproved, FacPremiumCeded) carries the originating policy's
-- class_of_business_id on its lines. AnnualRevenueAccountEngine can then
-- read class-broken-down totals from the GL instead of source tables
-- (Slice 1.10b re-implementation).
--
-- Phase 2 (PAA) and Phase 3 (IFRS-9) engines also pass class_of_business
-- through (Phase 2 resolves it via the policies in the contract group;
-- Phase 3 leaves it null because investments have no class_of_business
-- semantics).
--
-- ── Design decisions ────────────────────────────────────────────────────────
--   • Nullable. Historical rows posted before this migration have null;
--     the backfill in V42b populates them. Investment-related Phase 3 JEs
--     never set this (no class_of_business semantics) and remain null
--     permanently. Manual / treasury / payroll JEs also remain null.
--
--   • NO foreign-key constraint to classes_of_business. The master data
--     lives in cia-setup; cia-finance must not entangle itself with
--     cia-setup at the DB level (the schema-per-tenant model means
--     classes_of_business is in the same schema, but the module
--     dependency is asymmetric — cia-finance is a downstream consumer of
--     events, not a transactional collaborator). Same pattern used by
--     `journal_entry_line.portfolio_id` (V31 → V36) and
--     `journal_entry_line.contract_group_id` — promoted UUID columns
--     without cross-module FKs.
--
--   • Partial index. The vast majority of class-bearing JEs are written
--     by SubledgerPostingService against deleted_at IS NULL rows.
--     Filtering both NULL deleted_at AND NOT NULL class_of_business_id
--     keeps the index lean — Phase 3 investment JEs and reversed JEs
--     don't waste index space.
--
--   • Idempotent. The CHECK on partial-index existence and ADD COLUMN
--     IF NOT EXISTS pattern aren't supported uniformly across PostgreSQL
--     versions for indexes; Flyway's per-version one-shot semantics
--     guarantee single execution, so no defensive IF NOT EXISTS is
--     needed.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE journal_entry_line
    ADD COLUMN class_of_business_id UUID;

CREATE INDEX idx_journal_entry_line_class_of_business
    ON journal_entry_line (class_of_business_id)
    WHERE deleted_at IS NULL
      AND class_of_business_id IS NOT NULL;

COMMENT ON COLUMN journal_entry_line.class_of_business_id IS
    'IFRS-17 / NAICOM class-of-business dimension. Populated by '
    'SubledgerPostingService from the originating policy''s '
    'class_of_business_id at posting time. NULL for: pre-V42 historical '
    'rows (before backfill ran), Phase 3 IFRS-9 investment JEs, manual / '
    'treasury / payroll JEs. NO FK constraint to classes_of_business — '
    'cia-finance is a downstream event consumer, not a transactional '
    'collaborator with cia-setup. Slice 1.10a (Module 12).';
