package com.nubeero.cia.finance;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.CreditNoteResponse;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/credit-notes")
@Tag(name = "Credit Notes (Module 8)",
     description = "Payables-side documents. Generated automatically when a claim DV is approved, a FAC outward is confirmed, or commission/RI commission becomes payable (SubledgerPostingService listens for ClaimApproved / FacPremiumCeded events). State machine: ISSUED → PARTIALLY_PAID → SETTLED → CANCELLED.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class CreditNoteController {

    private final CreditNoteService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "List credit notes (paginated, filterable)",
               description = "Filter by status or entityId (the underlying claim / FAC / commission record). Outstanding amount is computed inline (totalAmount − paidAmount).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Credit note page",
            content = @Content(schema = @Schema(implementation = CreditNoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<CreditNoteResponse>> list(
            @RequestParam(required = false) CreditNoteStatus status,
            @RequestParam(required = false) UUID entityId,
            @PageableDefault(size = 2000) Pageable pageable) {
        Page<CreditNote> page = status != null
                ? service.findByStatus(status, pageable)
                : entityId != null
                        ? service.findByEntity(entityId, pageable)
                        : service.findAll(pageable);
        return ApiResponse.success(page.map(this::toResponse).getContent(),
                ApiMeta.builder()
                        .total(page.getTotalElements())
                        .page(page.getNumber())
                        .size(page.getSize())
                        .build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get credit note detail")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Credit note found",
            content = @Content(schema = @Schema(implementation = CreditNoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Credit note not found", content = @Content)
    })
    public ApiResponse<CreditNoteResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('FINANCE_UPDATE')")
    @Operation(summary = "Cancel a credit note",
               description = "Transitions the CN to CANCELLED. Allowed only when no payments have been posted (paidAmount = 0). Reverse the payments first if cancellation is needed after payment.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Credit note cancelled"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Credit note not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cannot cancel — payments exist or CN already in terminal state", content = @Content)
    })
    public ApiResponse<Void> cancel(@PathVariable UUID id) {
        service.cancel(id);
        return ApiResponse.success(null);
    }

    private CreditNoteResponse toResponse(CreditNote cn) {
        BigDecimal outstanding = cn.getTotalAmount().subtract(cn.getPaidAmount());
        return new CreditNoteResponse(
                cn.getId(),
                cn.getCreditNoteNumber(),
                cn.getStatus(),
                cn.getEntityType(),
                cn.getEntityId(),
                cn.getEntityReference(),
                cn.getBeneficiaryId(),
                cn.getBeneficiaryName(),
                cn.getDescription(),
                cn.getAmount(),
                cn.getTaxAmount(),
                cn.getTotalAmount(),
                cn.getPaidAmount(),
                outstanding,
                cn.getCurrencyCode(),
                cn.getDueDate(),
                cn.getCreatedAt() != null ? cn.getCreatedAt().atOffset(ZoneOffset.UTC) : null
        );
    }
}
