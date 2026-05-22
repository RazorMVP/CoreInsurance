-- V53: per-policy agent attribution (Slice 84d, option A — mirror broker).
--
-- Closes Open Question #11 in PRD v2.7 (per-policy agent attribution).
-- Mirrors the existing broker columns 1:1 + adds a mutual-exclusivity CHECK
-- so a policy carries at most one external intermediary. Agents represent the
-- insurer (NAICOM-licensed under master data V48); brokers represent the
-- insured (master data V49). A policy is sold by one or the other, never
-- both in Nigerian general insurance practice.
--
-- Relationship-manager attribution is intentionally NOT in this slice — RM
-- commission routes through staff payables (account 2520) as an incentive
-- rather than a commission CN, so it needs a separate design conversation.

ALTER TABLE policies
  ADD COLUMN agent_id   UUID,
  ADD COLUMN agent_name VARCHAR(100);

ALTER TABLE policies
  ADD CONSTRAINT ck_policies_broker_xor_agent
  CHECK (broker_id IS NULL OR agent_id IS NULL);

-- Index mirrors idx_policies_broker_id (V6) for the same access pattern —
-- "find policies attributed to this agent".
CREATE INDEX idx_policies_agent_id ON policies (agent_id) WHERE deleted_at IS NULL;
