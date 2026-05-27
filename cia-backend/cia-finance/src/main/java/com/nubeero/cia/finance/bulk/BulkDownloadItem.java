package com.nubeero.cia.finance.bulk;

import com.nubeero.cia.finance.audit.PdfDocumentType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One entry in a {@link BulkDownloadRequest}.
 *
 * @since F11
 */
public record BulkDownloadItem(
        @NotNull PdfDocumentType type,
        @NotNull UUID id
) {
}
