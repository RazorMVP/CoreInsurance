-- Module 7: Customer Number Format (tenant-wide singleton)

ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS customer_number VARCHAR(60) UNIQUE;

CREATE INDEX IF NOT EXISTS idx_customers_number
    ON customers (customer_number) WHERE deleted_at IS NULL;

-- Singleton configuration table (at most one row per tenant schema).
-- sequence_length defaults to 8 → supports up to 99,999,999 per type per year.
-- Generated format examples:
--   include_type=false  → CUST/2026/00000042        (shared sequence)
--   include_type=true   → CUST/2026/IND/00000001    (per-type sequences, start at 1)
--                       → CUST/2026/CORP/00000001
CREATE TABLE customer_number_format (
    id                          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    prefix                      VARCHAR(20) NOT NULL,
    include_year                BOOLEAN     NOT NULL DEFAULT TRUE,
    include_type                BOOLEAN     NOT NULL DEFAULT TRUE,
    sequence_length             INT         NOT NULL DEFAULT 8,
    last_sequence               BIGINT      NOT NULL DEFAULT 0,   -- used when include_type=false
    last_sequence_individual    BIGINT      NOT NULL DEFAULT 0,   -- used when include_type=true
    last_sequence_corporate     BIGINT      NOT NULL DEFAULT 0,   -- used when include_type=true
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ
);
