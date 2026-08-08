-- ─────────────────────────────────────────────────────────────────────────────
-- V76 — Portfolio contract_nature
--
-- FAC / IFRS-17 PAA workstream, Task 1 — data model only.
-- Adds a contract_nature dimension to portfolio: DIRECT (existing policies)
-- vs. FAC_INWARD / FAC_OUTWARD (facultative reinsurance contracts, wired by
-- a later slice of this workstream). Every portfolio created up to this
-- point is a direct-policy portfolio, so the column defaults DIRECT — the
-- direct path stays behaviourally identical.
--
-- uq_portfolio_code (V36) already segregates portfolios by their
-- nature-prefixed code (e.g. "COB-MOTOR-COMP" today; FAC portfolios will
-- carry their own prefix in a later slice), so no change to that
-- constraint is needed here.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE portfolio ADD COLUMN contract_nature VARCHAR(20) NOT NULL DEFAULT 'DIRECT';
ALTER TABLE portfolio ADD CONSTRAINT ck_portfolio_contract_nature
  CHECK (contract_nature IN ('DIRECT','FAC_INWARD','FAC_OUTWARD'));
-- code uniqueness (uq_portfolio_code) already segregates natures via the nature-prefixed code.
