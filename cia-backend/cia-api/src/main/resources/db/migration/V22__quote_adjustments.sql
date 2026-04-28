-- Add per-item and quote-level adjustment columns to support loadings, discounts,
-- and clause selection on quotes.

-- ── quote_risks: add rate + per-item adjustment JSONB columns ─────────────────
ALTER TABLE quote_risks
    ADD COLUMN rate        DECIMAL(10, 4) NOT NULL DEFAULT 0,
    ADD COLUMN loadings    JSONB          NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN discounts   JSONB          NOT NULL DEFAULT '[]'::jsonb;

-- ── quotes: add quote-level adjustments + clause selection ────────────────────
ALTER TABLE quotes
    ADD COLUMN quote_loadings      JSONB    NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN quote_discounts     JSONB    NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN selected_clause_ids JSONB    NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN inputter_name       VARCHAR(200),
    ADD COLUMN approver_name       VARCHAR(200);

-- Back-fill inputter_name from created_by for existing rows
UPDATE quotes SET inputter_name = created_by WHERE inputter_name IS NULL;
