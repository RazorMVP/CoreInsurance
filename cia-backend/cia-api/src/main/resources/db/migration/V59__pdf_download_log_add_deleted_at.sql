-- V59__pdf_download_log_add_deleted_at.sql
--
-- F11 / Task 7 fix — pdf_download_log was created by V58 without deleted_at,
-- but PdfDownloadLog extends BaseEntity which maps that column. Add it so
-- Hibernate's generated SELECT does not fail with "column does not exist".
--
-- pdf_download_log is an append-only audit table so deleted_at is never
-- set in practice; the column is carried purely to satisfy the @MappedSuperclass.

ALTER TABLE pdf_download_log
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
