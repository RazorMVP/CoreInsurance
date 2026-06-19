-- ─────────────────────────────────────────────────────────────────────────────
-- V74 — policies.selected_clauses (clause snapshot)
--
-- Module 3 (Policy). The point-in-time clause snapshot on policies. On bind, the
-- approved quote's frozen snapshot (V73) is carried over verbatim so the policy
-- document matches the issued quote; on direct entry / clause-edit, the selected
-- ids are resolved against the clause master (V72) and frozen here.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE policies
    ADD COLUMN selected_clauses JSONB NOT NULL DEFAULT '[]'::jsonb;
