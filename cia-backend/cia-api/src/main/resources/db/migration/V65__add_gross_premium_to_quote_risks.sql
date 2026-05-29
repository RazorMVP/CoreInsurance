-- Add the gross_premium column to quote_risks to match the QuoteRisk entity.
--
-- QuoteRisk maps `grossPremium` (Hibernate default column name `gross_premium`)
-- — the per-item premium BEFORE loadings/discounts, computed by QuoteService as
-- sum_insured × rate / 100. No migration ever created the column: V5 created
-- quote_risks; V22 added only rate / loadings / discounts. Every Hibernate fetch
-- of a QuoteRisk therefore emits `SELECT ... r.gross_premium ...` and fails on a
-- clean schema with `column quote_risks.gross_premium does not exist`.
--
-- Backfill existing rows from the same formula QuoteService uses at create time,
-- then drop the default so the shape matches the sibling `premium` column
-- (NOT NULL, no default — the application always supplies the value).

ALTER TABLE quote_risks ADD COLUMN gross_premium DECIMAL(18, 2) NOT NULL DEFAULT 0;

UPDATE quote_risks SET gross_premium = ROUND(sum_insured * rate / 100, 2);

ALTER TABLE quote_risks ALTER COLUMN gross_premium DROP DEFAULT;
