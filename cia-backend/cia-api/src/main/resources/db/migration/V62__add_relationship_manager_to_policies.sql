-- V62 — Relationship-Manager commission. Per-policy RM snapshot (id + name),
-- mirroring the broker/agent snapshot (V51/V53). RM is an exclusive third
-- commission source: at most one of broker / agent / RM per policy.

ALTER TABLE policies ADD COLUMN relationship_manager_id   UUID;
ALTER TABLE policies ADD COLUMN relationship_manager_name VARCHAR(100);

ALTER TABLE policies
  ADD CONSTRAINT fk_policies_relationship_manager
  FOREIGN KEY (relationship_manager_id) REFERENCES relationship_managers (id);

ALTER TABLE policies DROP CONSTRAINT ck_policies_broker_xor_agent;

ALTER TABLE policies
  ADD CONSTRAINT ck_policies_commission_source_one
  CHECK (
        (CASE WHEN broker_id               IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN agent_id                IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN relationship_manager_id IS NOT NULL THEN 1 ELSE 0 END) <= 1
  );

ALTER TABLE policies
  ADD CONSTRAINT ck_policies_rm_source_requires_rm
  CHECK (commission_source_type <> 'RELATIONSHIP_MANAGER'
         OR relationship_manager_id IS NOT NULL);
