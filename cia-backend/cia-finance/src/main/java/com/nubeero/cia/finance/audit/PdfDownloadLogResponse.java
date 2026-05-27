package com.nubeero.cia.finance.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection DTO for {@code GET /api/v1/finance/pdf-downloads}.
 *
 * @since F11
 */
public record PdfDownloadLogResponse(
        UUID id,
        PdfDocumentType entityType,
        UUID entityId,
        String reference,
        UUID parentId,
        String parentRef,
        String recipientName,
        Instant downloadedAt
) {
    public static PdfDownloadLogResponse from(PdfDownloadLog log) {
        return new PdfDownloadLogResponse(
                log.getId(),
                log.getEntityType(),
                log.getEntityId(),
                log.getReference(),
                log.getParentId(),
                log.getParentRef(),
                log.getRecipientName(),
                log.getDownloadedAt());
    }
}
