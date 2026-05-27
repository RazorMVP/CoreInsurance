package com.nubeero.cia.finance.bulk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/finance/pdfs/bulk-download}. The
 * {@code @Size(max=50)} bean-validation guard kicks in BEFORE the
 * controller method, so an oversize payload returns 400 with the
 * standard VALIDATION_ERROR envelope. The controller (Task 10) also
 * raises {@code BULK_DOWNLOAD_TOO_MANY} for clients that bypass bean
 * validation (e.g. malformed JSON).
 *
 * @since F11
 */
public record BulkDownloadRequest(
        @NotEmpty
        @Size(max = 50)
        @Valid
        List<BulkDownloadItem> items
) {
}
