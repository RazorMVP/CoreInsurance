-- ─────────────────────────────────────────────────────────────────────────────
-- V47 — Add reason TEXT column to audit_log
--
-- Module 10 (Audit & Compliance) — generalises the KYC-update reason
-- pattern (Module 7) to any action that should record WHY it happened.
-- Initially populated on DELETE actions across the back-office; reusable
-- for future reasoned actions (REJECT, OVERRIDE, REOPEN_PERIOD, etc.).
--
-- Nullable — existing audit_log rows have no reason, and CREATE / UPDATE
-- actions don't require one. Only DELETE / REJECT / OVERRIDE flows enforce
-- non-blank at the API layer.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE audit_log
    ADD COLUMN reason TEXT;

-- Indexed because audit reports filter by reason ("show all blacklist
-- deletions in March"). Partial index so the index doesn't bloat with
-- the (very common) NULL rows from CREATE / UPDATE actions.
CREATE INDEX idx_audit_log_reason
    ON audit_log (reason)
    WHERE reason IS NOT NULL;
