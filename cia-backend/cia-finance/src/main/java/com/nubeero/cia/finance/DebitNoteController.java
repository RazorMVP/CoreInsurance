package com.nubeero.cia.finance;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.DebitNoteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/debit-notes")
@Tag(name = "Debit Notes (Module 8)",
     description = "Receivables-side documents. Generated automatically when a policy or endorsement is approved (SubledgerPostingService listens for PolicyApproved / EndorsementApproved events). State machine: ISSUED → PARTIALLY_PAID → SETTLED → CANCELLED / VOID.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class DebitNoteController {

    private final DebitNoteService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "List debit notes (paginated, filterable)",
               description = "Filter by status, customerId, or entityId (the underlying policy / endorsement). All omitted returns all. Outstanding amount is computed inline (totalAmount − paidAmount).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Debit note page",
            content = @Content(schema = @Schema(implementation = DebitNoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<DebitNoteResponse>> list(
            @RequestParam(required = false) DebitNoteStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID entityId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<DebitNote> page = status != null
                ? service.findByStatus(status, pageable)
                : customerId != null
                        ? service.findByCustomer(customerId, pageable)
                        : entityId != null
                                ? service.findByEntity(entityId, pageable)
                                : service.findAll(pageable);
        return ApiResponse.success(page.map(this::toResponse).getContent());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get debit note detail")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Debit note found",
            content = @Content(schema = @Schema(implementation = DebitNoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Debit note not found", content = @Content)
    })
    public ApiResponse<DebitNoteResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('FINANCE_UPDATE')")
    @Operation(summary = "Cancel a debit note",
               description = "Transitions the DN to CANCELLED. Allowed only when no receipts have been posted (paidAmount = 0). Rejected with 409 otherwise; reverse the receipts first.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Debit note cancelled"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Debit note not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cannot cancel — receipts exist or DN already in terminal state", content = @Content)
    })
    public ApiResponse<Void> cancel(@PathVariable UUID id) {
        service.cancel(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasRole('FINANCE_UPDATE')")
    @Operation(summary = "Mark a debit note VOID",
               description = "VOID is a stronger terminal state than CANCELLED — used when the underlying business event itself was reversed (policy rejection after approval, etc.). Cascades a contra JE via SubledgerPostingService.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Debit note voided"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Debit note not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already VOID/CANCELLED", content = @Content)
    })
    public ApiResponse<Void> markVoid(@PathVariable UUID id) {
        service.markVoid(id);
        return ApiResponse.success(null);
    }

    private DebitNoteResponse toResponse(DebitNote dn) {
        BigDecimal outstanding = dn.getTotalAmount().subtract(dn.getPaidAmount());
        return new DebitNoteResponse(
                dn.getId(),
                dn.getDebitNoteNumber(),
                dn.getStatus(),
                dn.getEntityType(),
                dn.getEntityId(),
                dn.getEntityReference(),
                dn.getCustomerId(),
                dn.getCustomerName(),
                dn.getBrokerId(),
                dn.getBrokerName(),
                dn.getProductName(),
                dn.getDescription(),
                dn.getAmount(),
                dn.getTaxAmount(),
                dn.getTotalAmount(),
                dn.getPaidAmount(),
                outstanding,
                dn.getCurrencyCode(),
                dn.getDueDate(),
                dn.getCreatedAt()
        );
    }
}
