-- Phase 4: indexes for report, dashboard, and audit-style read paths.
-- These complement the existing status/entity indexes with date and grouping
-- access paths used by ReportQueryBuilder and DashboardService.

CREATE INDEX IF NOT EXISTS idx_policies_created_at
    ON policies (created_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_claims_created_at
    ON claims (created_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_claims_class_of_business_id
    ON claims (class_of_business_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_debit_notes_created_at
    ON debit_notes (created_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_debit_notes_due_date
    ON debit_notes (due_date)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_ri_alloc_created_at
    ON ri_allocations (created_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_customers_created_at
    ON customers (created_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_endorsements_created_at
    ON endorsements (created_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_endorsements_class_of_business_id
    ON endorsements (class_of_business_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_endorsements_product_id
    ON endorsements (product_id)
    WHERE deleted_at IS NULL;
