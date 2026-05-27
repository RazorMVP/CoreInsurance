package com.nubeero.cia.finance;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.finance.audit.PdfDocumentType;
import com.nubeero.cia.finance.audit.PdfDownloadLogService;
import com.nubeero.cia.finance.dto.PostReceiptRequest;
import com.nubeero.cia.finance.dto.ReceiptResponse;
import com.nubeero.cia.finance.dto.ReverseRequest;
import com.nubeero.cia.storage.DocumentStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/debit-notes/{debitNoteId}/receipts")
@Tag(name = "Receipts (Module 8)",
     description = "Customer receipts against a debit note. Posting a receipt subtracts from outstanding_amount; full settlement flips the debit note status to SETTLED. Reversal undoes the payment (re-opens the DN) and posts a contra entry via SubledgerPostingService.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptService service;
    private final DocumentStorageService storage;
    private final PdfDownloadLogService pdfDownloadLogService;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "List receipts for a debit note (paginated)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Receipt page",
            content = @Content(schema = @Schema(implementation = ReceiptResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<ReceiptResponse>> list(
            @PathVariable UUID debitNoteId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(
                service.findByDebitNote(debitNoteId, pageable).map(this::toResponse).getContent());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get receipt detail")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Receipt found",
            content = @Content(schema = @Schema(implementation = ReceiptResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Receipt not found", content = @Content)
    })
    public ApiResponse<ReceiptResponse> get(
            @PathVariable UUID debitNoteId,
            @PathVariable UUID id) {
        Receipt receipt = service.findOrThrow(id);
        return ApiResponse.success(toResponse(receipt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FINANCE_CREATE')")
    @Operation(summary = "Post a new receipt against the debit note",
               description = "Generates a receipt number, subtracts from DN outstanding, posts the JE via SubledgerPostingService (Receipt event), and flips DN status to SETTLED when paidAmount == totalAmount.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Receipt posted",
            content = @Content(schema = @Schema(implementation = ReceiptResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or amount exceeds outstanding", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Debit note not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Target period is closed", content = @Content)
    })
    public ApiResponse<ReceiptResponse> post(
            @PathVariable UUID debitNoteId,
            @Valid @RequestBody PostReceiptRequest req) {
        Receipt receipt = service.post(
                debitNoteId, req.amount(), req.paymentDate(), req.paymentMethod(),
                req.bankId(), req.bankName(), req.chequeNumber(), req.narration());
        return ApiResponse.success(toResponse(receipt));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasRole('FINANCE_UPDATE')")
    @Operation(summary = "Reverse a receipt",
               description = "Marks the receipt REVERSED with a reason. Re-opens the parent DN's outstanding amount and posts a contra JE. Reversal carve-out applies — can cross a closed period.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Receipt reversed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Receipt not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Receipt already reversed", content = @Content)
    })
    public ApiResponse<Void> reverse(
            @PathVariable UUID debitNoteId,
            @PathVariable UUID id,
            @Valid @RequestBody ReverseRequest req) {
        service.reverse(id, req.reason());
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAuthority('FINANCE_VIEW')")
    @Operation(summary = "Download the receipt PDF",
               description = "Streams the generated PDF for the receipt from object storage. 404 when pdfPath IS NULL (PDF was never generated or generation failed).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF bytes"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Receipt not found OR pdfPath is null", content = @Content)
    })
    public ResponseEntity<Resource> downloadPdf(@PathVariable UUID debitNoteId,
                                                  @PathVariable UUID id) {
        Receipt receipt = service.findOrThrow(id);
        if (receipt.getPdfPath() == null) {
            throw new ResourceNotFoundException("Receipt", id);
        }
        String tenantId = TenantContext.getTenantId();
        InputStream stream = storage.download(tenantId, receipt.getPdfPath());

        // F11 — server-side download history. The service swallows write
        // failures (REQUIRES_NEW + try/catch) so an audit-log hiccup never
        // blocks the actual download response.
        DebitNote dn = receipt.getDebitNote();
        pdfDownloadLogService.log(
                PdfDocumentType.RECEIPT,
                receipt.getId(),
                receipt.getReceiptNumber(),
                dn != null ? dn.getId() : null,
                dn != null ? dn.getDebitNoteNumber() : null,
                dn != null ? dn.getCustomerName() : null);

        String filename = "REC-" + receipt.getReceiptNumber() + ".pdf";

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"")
            .body(new InputStreamResource(stream));
    }

    @PostMapping("/{id}/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('FINANCE_UPDATE')")
    @Operation(summary = "Email the receipt PDF to the customer",
               description = "Starts a Temporal workflow (EMAIL_QUEUE) that downloads the PDF, composes the body, and delivers via the configured EmailService provider. Preflight checks pdfPath != null and customers.email != null. 202 with workflow id on enqueue; 422 with errorCode (RECEIPT_PDF_UNAVAILABLE / RECEIPT_RECIPIENT_UNRESOLVED) when preflight fails.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Workflow enqueued; body carries workflowId"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Receipt not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Preflight failed — PDF unavailable or recipient unresolved", content = @Content)
    })
    public ApiResponse<Map<String, String>> requestEmail(@PathVariable UUID debitNoteId,
                                                          @PathVariable UUID id) {
        String workflowId = service.requestEmail(id);
        return ApiResponse.success(Map.of("workflowId", workflowId));
    }

    @PostMapping("/{id}/email/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('FINANCE_UPDATE')")
    @Operation(summary = "Cancel an in-flight receipt-email workflow",
               description = "Signals the SendReceiptEmailWorkflow to cancel. Best-effort — if the activity-dispatch has already happened, the email send completes normally. Used by the BulkEmailSheet Cancel button.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Cancel signal sent"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "WORKFLOW_NOT_FOUND — already finished or never started", content = @Content)
    })
    public ApiResponse<Map<String, Boolean>> cancelEmail(@PathVariable UUID debitNoteId,
                                                          @PathVariable UUID id) {
        service.cancelEmail(id);
        return ApiResponse.success(Map.of("cancelled", true));
    }

    private ReceiptResponse toResponse(Receipt r) {
        return new ReceiptResponse(
                r.getId(),
                r.getReceiptNumber(),
                r.getDebitNote().getId(),
                r.getDebitNote().getDebitNoteNumber(),
                r.getAmount(),
                r.getPaymentDate(),
                r.getPaymentMethod(),
                r.getBankId(),
                r.getBankName(),
                r.getChequeNumber(),
                r.getNarration(),
                r.getPostedBy(),
                r.getStatus(),
                r.getReversalReason(),
                r.getReversedAt(),
                r.getReversedBy(),
                r.getCreatedAt()
        );
    }
}
