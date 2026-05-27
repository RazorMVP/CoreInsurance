package com.nubeero.cia.finance;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.finance.audit.PdfDocumentType;
import com.nubeero.cia.finance.audit.PdfDownloadLogService;
import com.nubeero.cia.finance.dto.PaymentResponse;
import com.nubeero.cia.finance.dto.PostPaymentRequest;
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
@RequestMapping("/api/v1/credit-notes/{creditNoteId}/payments")
@Tag(name = "Payments (Module 8)",
     description = "Outbound payments against a credit note (claim DV, FAC outward, commission, etc.). Posting a payment subtracts from CN outstanding_amount; full settlement flips the CN to SETTLED. Reversal undoes the payment and posts a contra JE via SubledgerPostingService.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService service;
    private final DocumentStorageService storage;
    private final PdfDownloadLogService pdfDownloadLogService;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "List payments for a credit note (paginated)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payment page",
            content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<PaymentResponse>> list(
            @PathVariable UUID creditNoteId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(
                service.findByCreditNote(creditNoteId, pageable).map(this::toResponse).getContent());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get payment detail")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payment found",
            content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Payment not found", content = @Content)
    })
    public ApiResponse<PaymentResponse> get(
            @PathVariable UUID creditNoteId,
            @PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FINANCE_CREATE')")
    @Operation(summary = "Post a new payment against the credit note",
               description = "Generates a payment number, subtracts from CN outstanding, posts the JE via SubledgerPostingService (Payment event), and flips CN status to SETTLED when paidAmount == totalAmount.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Payment posted",
            content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or amount exceeds outstanding", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Credit note not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Target period is closed", content = @Content)
    })
    public ApiResponse<PaymentResponse> post(
            @PathVariable UUID creditNoteId,
            @Valid @RequestBody PostPaymentRequest req) {
        Payment payment = service.post(
                creditNoteId, req.amount(), req.paymentDate(), req.paymentMethod(),
                req.bankId(), req.bankName(), req.bankAccountName(),
                req.bankAccountNumber(), req.narration());
        return ApiResponse.success(toResponse(payment));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasRole('FINANCE_UPDATE')")
    @Operation(summary = "Reverse a payment",
               description = "Marks the payment REVERSED with a reason. Re-opens the parent CN's outstanding amount and posts a contra JE. Reversal carve-out applies — can cross a closed period.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payment reversed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Payment not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Payment already reversed", content = @Content)
    })
    public ApiResponse<Void> reverse(
            @PathVariable UUID creditNoteId,
            @PathVariable UUID id,
            @Valid @RequestBody ReverseRequest req) {
        service.reverse(id, req.reason());
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAuthority('FINANCE_VIEW')")
    @Operation(summary = "Download the payment voucher PDF",
               description = "Streams the generated voucher PDF for the payment from object storage. 404 when pdfPath IS NULL.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF bytes"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Payment not found OR pdfPath is null", content = @Content)
    })
    public ResponseEntity<Resource> downloadPdf(@PathVariable UUID creditNoteId,
                                                  @PathVariable UUID id) {
        Payment payment = service.findOrThrow(id);
        if (payment.getPdfPath() == null) {
            throw new ResourceNotFoundException("Payment", id);
        }
        String tenantId = TenantContext.getTenantId();
        InputStream stream = storage.download(tenantId, payment.getPdfPath());

        // F11 — server-side download history. Service swallows failures
        // (REQUIRES_NEW + try/catch) so an audit-log hiccup never blocks
        // the download response.
        CreditNote cn = payment.getCreditNote();
        pdfDownloadLogService.log(
                PdfDocumentType.PAYMENT,
                payment.getId(),
                payment.getPaymentNumber(),
                cn != null ? cn.getId() : null,
                cn != null ? cn.getCreditNoteNumber() : null,
                cn != null ? cn.getBeneficiaryName() : null);

        String filename = "PAY-" + payment.getPaymentNumber() + ".pdf";

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"")
            .body(new InputStreamResource(stream));
    }

    @PostMapping("/{id}/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('FINANCE_UPDATE')")
    @Operation(summary = "Email the payment voucher PDF to the beneficiary",
               description = "Starts a Temporal workflow (NOTIFICATIONS_QUEUE) that downloads the PDF, composes the body, and delivers via the configured EmailService provider. Preflight: pdfPath != null and BeneficiaryEmailResolverDispatcher returns a non-blank email for the credit note's entityType. 202 with workflow id on enqueue; 422 with errorCode (PAYMENT_PDF_UNAVAILABLE / PAYMENT_RECIPIENT_UNRESOLVED) when preflight fails.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Workflow enqueued; body carries workflowId"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Payment not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Preflight failed — PDF unavailable or recipient unresolved", content = @Content)
    })
    public ApiResponse<Map<String, String>> requestEmail(@PathVariable UUID creditNoteId,
                                                          @PathVariable UUID id) {
        String workflowId = service.requestEmail(id);
        return ApiResponse.success(Map.of("workflowId", workflowId));
    }

    @PostMapping("/{id}/email/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('FINANCE_UPDATE')")
    @Operation(summary = "Cancel an in-flight payment-voucher email workflow",
               description = "Signals the SendPaymentEmailWorkflow to cancel. Best-effort — if the activity-dispatch has already happened, the email send completes normally. Used by the BulkEmailSheet Cancel button.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Cancel signal sent"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "WORKFLOW_NOT_FOUND — already finished or never started", content = @Content)
    })
    public ApiResponse<Map<String, Boolean>> cancelEmail(@PathVariable UUID creditNoteId,
                                                          @PathVariable UUID id) {
        service.cancelEmail(id);
        return ApiResponse.success(Map.of("cancelled", true));
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getPaymentNumber(),
                p.getCreditNote().getId(),
                p.getCreditNote().getCreditNoteNumber(),
                p.getAmount(),
                p.getPaymentDate(),
                p.getPaymentMethod(),
                p.getBankId(),
                p.getBankName(),
                p.getBankAccountName(),
                p.getBankAccountNumber(),
                p.getNarration(),
                p.getPostedBy(),
                p.getStatus(),
                p.getReversalReason(),
                p.getReversedAt(),
                p.getReversedBy(),
                p.getCreatedAt()
        );
    }
}
