-- V56__add_pdf_path_to_receipts_payments.sql
--
-- Adds nullable pdf_path columns to receipts + payments for F7 slice β.
-- pdf_path stores the MinIO object path (e.g. "receipts/2026/05/<uuid>.pdf")
-- returned by DocumentStorageService.upload(...). NULL = PDF was never
-- generated (generator failure on post() leaves the column null and logs).
--
-- Bumped to V56 (not V50 as originally planned) because V50-V55 were
-- already taken by commission + agent attribution work in Sessions 84a-B1a.

ALTER TABLE receipts ADD COLUMN pdf_path VARCHAR(512);
ALTER TABLE payments ADD COLUMN pdf_path VARCHAR(512);
