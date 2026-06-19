-- ─────────────────────────────────────────────────────────────────────────────
-- V73 — quotes.selected_clauses (clause snapshot)
--
-- Module 2 (Quotation). Adds the point-in-time clause snapshot alongside the
-- existing selected_clause_ids (V22). At quote create, the selected clause IDs
-- are resolved against the clause master (V72) and the {id,title,text,type} is
-- frozen here, so the quote PDF renders the exact clause text it was issued with
-- regardless of later edits to the clause bank.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE quotes
    ADD COLUMN selected_clauses JSONB NOT NULL DEFAULT '[]'::jsonb;
