package com.nubeero.cia.finance.bulk;

import com.nubeero.cia.common.exception.CiaException;
import com.nubeero.cia.common.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/v1/finance/pdfs")
@Tag(name = "Bulk PDF Download (Module 8)",
     description = "Single-request multi-PDF download. POSTs a list of {type, id} items; backend resolves each, streams a ZIP, returns application/zip. 50-item cap per request.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class BulkPdfDownloadController {

    private static final int MAX_ITEMS = 50;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private final PdfZipService zipService;

    @PostMapping("/bulk-download")
    @PreAuthorize("hasAuthority('FINANCE_VIEW')")
    @Operation(summary = "Download N PDFs as a single ZIP",
               description = "Streams a ZIP of resolved receipts + payment vouchers. Items with null pdf_path are silently skipped (server-side WARN). Each resolved item writes a pdf_download_log row.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ZIP bytes (application/zip)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error: BULK_DOWNLOAD_TOO_MANY (>50) or BULK_DOWNLOAD_EMPTY", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ResponseEntity<byte[]> bulkDownload(@Valid @RequestBody BulkDownloadRequest request) {
        if (request.items().isEmpty()) {
            throw new CiaException("BULK_DOWNLOAD_EMPTY",
                    "bulk-download items list is empty", HttpStatus.BAD_REQUEST);
        }
        if (request.items().size() > MAX_ITEMS) {
            throw new CiaException("BULK_DOWNLOAD_TOO_MANY",
                    "bulk-download accepts at most " + MAX_ITEMS + " items per request",
                    HttpStatus.BAD_REQUEST);
        }

        String tenantId = TenantContext.getTenantId();
        byte[] zipBytes = zipService.buildZip(tenantId, request);

        String filename = "cia-pdfs-" + LocalDateTime.now().format(TS_FMT) + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(zipBytes);
    }
}
