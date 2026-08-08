-- ─────────────────────────────────────────────────────────────────────────────
-- V77 — Polymorphic contract_group_assignment (replaces policy_group_assignment)
--
-- FAC / IFRS-17 PAA workstream, Task 1 — data model only.
-- Generalises V37's policy-only policy_group_assignment link table into a
-- polymorphic (contract_type, contract_id) pair so the same IFRS 17 §22
-- permanent-assignment table can carry direct policies today and
-- facultative reinsurance contracts (FAC_INWARD / FAC_OUTWARD) in a later
-- slice of this workstream — without a second parallel assignment table.
--
-- contract_id deliberately carries NO foreign key: it can point at
-- policies.id today, and at a cia-reinsurance FAC contract id once that
-- writer lands, so a single-target FK is impossible. This mirrors the
-- already-established loose-coupling pattern used elsewhere in the PAA
-- schema (journal_entry_line.portfolio_id / contract_group_id also carry
-- FKs only to PAA-owned tables, never into other modules).
--
-- UNIQUE (contract_type, contract_id) replaces V37's UNIQUE(policy_id) as
-- the idempotency key + permanence guard (§22): a duplicate-event re-fire
-- for the same (type, id) pair hits the constraint and the service
-- short-circuits, exactly as before for the POLICY case.
--
-- One migration, three steps: create the new table, backfill every
-- existing policy_group_assignment row as a POLICY-typed row (preserving
-- id / assigned_at / audit columns so no assignment history is lost), then
-- drop the old table. Direct-policy behaviour is unchanged — only the
-- storage shape generalises.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE contract_group_assignment (
  id            UUID PRIMARY KEY,
  contract_type VARCHAR(20) NOT NULL,
  contract_id   UUID        NOT NULL,
  group_id      UUID        NOT NULL,
  assigned_at   TIMESTAMPTZ,
  created_at    TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by VARCHAR(255), deleted_at TIMESTAMPTZ,
  CONSTRAINT uq_cga_type_contract UNIQUE (contract_type, contract_id),
  CONSTRAINT ck_cga_contract_type CHECK (contract_type IN ('POLICY','FAC_INWARD','FAC_OUTWARD')),
  CONSTRAINT ck_cga_contract_id_present CHECK (contract_id IS NOT NULL),
  CONSTRAINT fk_cga_group FOREIGN KEY (group_id) REFERENCES group_of_contracts (id)
);
CREATE INDEX idx_cga_group ON contract_group_assignment (group_id) WHERE deleted_at IS NULL;

INSERT INTO contract_group_assignment
  (id, contract_type, contract_id, group_id, assigned_at, created_at, updated_at, created_by, deleted_at)
SELECT id, 'POLICY', policy_id, group_id, assigned_at, created_at, updated_at, created_by, deleted_at
FROM policy_group_assignment;

DROP TABLE policy_group_assignment;

COMMENT ON TABLE contract_group_assignment IS
    'Records the IFRS 17 §22 permanent assignment of a contract (direct '
    'policy today; facultative reinsurance contract in a later slice) to a '
    'group of contracts (portfolio × cohort_year × onerousness) at initial '
    'recognition. Written by ContractGroupingService on PolicyApprovedEvent '
    'for contract_type = POLICY. UNIQUE(contract_type, contract_id) makes '
    'the listener naturally idempotent — a duplicate-event-re-fire hits the '
    'constraint and the service short-circuits. Replaces policy_group_assignment (V37).';
