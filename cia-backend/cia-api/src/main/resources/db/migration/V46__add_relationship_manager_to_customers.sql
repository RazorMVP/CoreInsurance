-- ─────────────────────────────────────────────────────────────────────────────
-- V46 — Add relationship_manager_id FK to customers
--
-- Module 1 (Setup) / Module 7 (Customer Onboarding) — surfaces the
-- relationship_managers master data on customer records. Every customer
-- is expected to have an assigned Relationship Manager.
--
-- Nullable for migration safety — existing customers were onboarded before
-- the RM field existed and would otherwise fail the constraint. The
-- frontend onboarding forms enforce required-on-create; existing records
-- can be backfilled via Edit Customer.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE customers
    ADD COLUMN relationship_manager_id UUID,
    ADD CONSTRAINT fk_customers_relationship_manager
        FOREIGN KEY (relationship_manager_id)
        REFERENCES relationship_managers (id);

CREATE INDEX idx_customers_relationship_manager
    ON customers (relationship_manager_id)
    WHERE deleted_at IS NULL AND relationship_manager_id IS NOT NULL;
