-- V55: per-quote agent attribution (Slice B1a — mirror of V53 for the
-- quote-side leg of broker XOR agent).
--
-- Closes the gap PolicyService.bindFromQuote called out at lines 117-119
-- when V53 shipped: "Quote does not yet carry agent attribution — Slice
-- 84d v1 ships agent only on direct-create policies. Quote-side support
-- is a follow-up slice that extends the Quote entity + DTOs in parallel."
--
-- After this migration:
--   • Approved quotes that were sold via an agent retain that attribution
--     through bind-from-quote (currently always broker-attributed).
--   • A quote carries at most one external intermediary, same invariant
--     V53 enforces on policies. Agents represent the insurer; brokers
--     represent the insured. A sale is one or the other, never both.
--
-- Relationship-manager attribution remains out of scope (V53 carve-out
-- still applies — RM commission routes through staff payables 2520).

ALTER TABLE quotes
  ADD COLUMN agent_id   UUID,
  ADD COLUMN agent_name VARCHAR(100);

ALTER TABLE quotes
  ADD CONSTRAINT ck_quotes_broker_xor_agent
  CHECK (broker_id IS NULL OR agent_id IS NULL);

-- Index mirrors idx_quotes_broker_id (V5) for the same access pattern —
-- "find quotes attributed to this agent".
CREATE INDEX idx_quotes_agent_id ON quotes (agent_id) WHERE deleted_at IS NULL;
