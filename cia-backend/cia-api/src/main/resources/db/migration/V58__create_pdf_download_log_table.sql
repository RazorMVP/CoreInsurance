-- V58__create_pdf_download_log_table.sql
--
-- F11 — server-side PDF download history. Separate from audit_log so the
-- high-volume PDF download events don't pollute compliance auditing.
-- Rows are written by ReceiptController.downloadPdf and
-- PaymentController.downloadPdf after a successful storage.download.
-- Queried by GET /api/v1/finance/pdf-downloads. Purged weekly by
-- PdfDownloadLogRetentionWorkflow (30-day retention).

CREATE TABLE pdf_download_log (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(20)  NOT NULL,
    entity_id       UUID         NOT NULL,
    reference       VARCHAR(60)  NOT NULL,
    parent_id       UUID,
    parent_ref      VARCHAR(60),
    recipient_name  VARCHAR(200),
    downloaded_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(255)
);

CREATE INDEX idx_pdf_dl_user_time
    ON pdf_download_log (user_id, downloaded_at DESC);

CREATE INDEX idx_pdf_dl_retention
    ON pdf_download_log (downloaded_at);
