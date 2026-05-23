package com.nubeero.cia.quotation;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.quotation.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quotes")
@Tag(name = "Quotes (Module 2)",
     description = "Quote lifecycle — DRAFT → PENDING_APPROVAL → APPROVED → CONVERTED (when bound to a policy). Premium calculation supports per-item loadings/discounts + quote-level loadings/discounts; sequence is tenant-configurable (LOADING_FIRST / DISCOUNT_FIRST). Multi-risk and single-risk quotes use the same endpoint surface; the structure of risks differentiates them.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService    service;
    private final QuotePdfService pdfService;

    @GetMapping
    @PreAuthorize("hasRole('QUOTATION_VIEW')")
    @Operation(summary = "List quotes (paginated, filterable)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Quote page",
            content = @Content(schema = @Schema(implementation = QuoteSummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks QUOTATION_VIEW", content = @Content)
    })
    public ApiResponse<List<QuoteSummaryResponse>> list(
            @RequestParam(required = false) QuoteStatus status,
            @RequestParam(required = false) UUID customerId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.list(status, customerId, pageable).getContent());
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('QUOTATION_VIEW')")
    @Operation(summary = "Search quotes by free text",
               description = "Matches against quote number, customer name, product name. Case-insensitive substring search.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Matching quotes",
            content = @Content(schema = @Schema(implementation = QuoteSummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks QUOTATION_VIEW", content = @Content)
    })
    public ApiResponse<List<QuoteSummaryResponse>> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.search(q, pageable).getContent());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('QUOTATION_VIEW')")
    @Operation(summary = "Get quote detail",
               description = "Returns the quote header, risk items (with per-item loadings + discounts), quote-level loadings/discounts, selected clauses, and the full premium breakdown.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Quote found",
            content = @Content(schema = @Schema(implementation = QuoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks QUOTATION_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Quote not found", content = @Content)
    })
    public ApiResponse<QuoteResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('QUOTATION_CREATE')")
    @Operation(summary = "Create a quote",
               description = "Creates a DRAFT quote. Single-risk: one risk item. Multi-risk: multiple risk items with per-item loadings/discounts. Premium is computed live by the service per the tenant's quote_config (LOADING_FIRST | DISCOUNT_FIRST sequence).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Quote created",
            content = @Content(schema = @Schema(implementation = QuoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (customer blacklisted, product inactive, etc.)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks QUOTATION_CREATE", content = @Content)
    })
    public ApiResponse<QuoteResponse> create(@Valid @RequestBody QuoteRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('QUOTATION_CREATE')")
    @Operation(summary = "Duplicate a quote",
               description = "Deep-copies an existing quote into a new DRAFT — new quote_number, fresh expires_at, cleared approval/rejection metadata, current user as inputter. Risks, coinsurance participants, quote-level loadings/discounts and selected clauses are carried forward. Totals are re-computed against the current QuoteConfig.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Quote duplicated",
            content = @Content(schema = @Schema(implementation = QuoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks QUOTATION_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Source quote not found", content = @Content)
    })
    public ApiResponse<QuoteResponse> duplicate(@PathVariable UUID id) {
        return ApiResponse.success(service.duplicate(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('QUOTATION_UPDATE')")
    @Operation(summary = "Update a DRAFT quote",
               description = "Each update increments the quote's version number — version history is preserved on the QuoteDetailPage timeline. Submission locks the quote from this endpoint.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Quote updated (new version)",
            content = @Content(schema = @Schema(implementation = QuoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks QUOTATION_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Quote not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Quote not in DRAFT state", content = @Content)
    })
    public ApiResponse<QuoteResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody QuoteUpdateRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('QUOTATION_UPDATE')")
    @Operation(summary = "Submit quote for approval",
               description = "Transitions DRAFT → PENDING_APPROVAL. Starts the QuoteApprovalWorkflow Temporal workflow with multi-level escalation against the configured approval tiers.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Submitted for approval",
            content = @Content(schema = @Schema(implementation = QuoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks QUOTATION_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Quote not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Quote not in DRAFT state", content = @Content)
    })
    public ApiResponse<QuoteResponse> submit(@PathVariable UUID id) {
        return ApiResponse.success(service.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('QUOTATION_APPROVE')")
    @Operation(summary = "Approve a quote",
               description = "Transitions PENDING_APPROVAL → APPROVED. Approved quotes are eligible for /policies/bind-from-quote (Module 3).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Quote approved",
            content = @Content(schema = @Schema(implementation = QuoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks QUOTATION_APPROVE or amount exceeds approver tier", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Quote not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Quote not in PENDING_APPROVAL state", content = @Content)
    })
    public ApiResponse<QuoteResponse> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) QuoteApprovalRequest request) {
        return ApiResponse.success(service.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('QUOTATION_APPROVE')")
    @Operation(summary = "Reject a quote",
               description = "Transitions PENDING_APPROVAL → DRAFT with rejection notes — underwriter can edit and re-submit.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Quote rejected",
            content = @Content(schema = @Schema(implementation = QuoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks QUOTATION_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Quote not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Quote not in PENDING_APPROVAL state", content = @Content)
    })
    public ApiResponse<QuoteResponse> reject(
            @PathVariable UUID id,
            @RequestBody(required = false) QuoteApprovalRequest request) {
        return ApiResponse.success(service.reject(id, request));
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('QUOTATION_VIEW')")
    @Operation(summary = "Download the quote PDF",
               description = "Generates the quote PDF on-demand via QuotePdfService (PDFBox + the configured tenant template). Includes the premium breakdown, validity period from quote_config, inputter + approver signatures (when APPROVED). Allowed in APPROVED + CONVERTED states.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Quote PDF stream"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks QUOTATION_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Quote not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Quote not in APPROVED or CONVERTED state", content = @Content)
    })
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID id) {
        byte[] pdf = pdfService.generatePdf(id);
        Quote  q   = service.findOrThrow(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + q.getQuoteNumber() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
