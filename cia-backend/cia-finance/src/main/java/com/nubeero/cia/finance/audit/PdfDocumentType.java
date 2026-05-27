package com.nubeero.cia.finance.audit;

/**
 * Type discriminator for {@link PdfDownloadLog} and bulk-download requests.
 * Distinct from {@link com.nubeero.cia.finance.FinanceEntityType} (which
 * discriminates DN/CN source entities — POLICY / CLAIM / etc.); here we
 * just say what kind of finance document the PDF is.
 *
 * @since F11 — PDF download UX + bulk operations
 */
public enum PdfDocumentType {
    RECEIPT,
    PAYMENT
}
