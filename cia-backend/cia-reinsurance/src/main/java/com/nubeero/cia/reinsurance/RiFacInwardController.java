package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.reinsurance.dto.CancelFacInwardRequest;
import com.nubeero.cia.reinsurance.dto.CreateFacInwardRequest;
import com.nubeero.cia.reinsurance.dto.ExtendFacInwardRequest;
import com.nubeero.cia.reinsurance.dto.FacInwardResponse;
import com.nubeero.cia.reinsurance.dto.RenewFacInwardRequest;
import com.nubeero.cia.storage.DocumentStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ri/fac-inwards")
@Tag(name = "Inward Facultative Reinsurance",
     description = "Inward FAC — accept a share of another insurer's risk. Lifecycle: ACTIVE → RENEWED/EXPIRED/CANCELLED. "
             + "Creation fires RiFacInwardAcceptedEvent → cia-finance cascades a DebitNote receivable + JE (Dr 1330 / Dr 5240 / Cr 4330).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class RiFacInwardController {

    private final RiFacInwardService service;
    private final DocumentStorageService storageService;

    @GetMapping
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    @Operation(summary = "List inward FAC covers (paginated, filterable)",
               description = "Filter by cedingCompanyId, classOfBusinessId, and/or status.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inward FAC cover page",
            content = @Content(schema = @Schema(implementation = FacInwardResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<FacInwardResponse>> list(
            @RequestParam(required = false) UUID cedingCompanyId,
            @RequestParam(required = false) UUID classOfBusinessId,
            @RequestParam(required = false) RiFacInwardStatus status,
            @PageableDefault(size = 2000) Pageable pageable) {
        var page = service.list(cedingCompanyId, classOfBusinessId, status, pageable);
        return ApiResponse.success(page.map(this::toResponse).getContent(),
                ApiMeta.builder()
                        .total(page.getTotalElements())
                        .page(page.getNumber())
                        .size(page.getSize())
                        .build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    @Operation(summary = "Get inward FAC cover detail")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inward FAC cover found",
            content = @Content(schema = @Schema(implementation = FacInwardResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Inward FAC cover not found", content = @Content)
    })
    public ApiResponse<FacInwardResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REINSURANCE_CREATE')")
    @Operation(summary = "Create (accept) an inward FAC cover — status ACTIVE",
               description = "Generates a fac-inward reference and an ACTIVE cover, and immediately generates the guaranty document. "
                       + "Fires RiFacInwardAcceptedEvent → cia-finance cascades a DebitNote receivable + JE.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Inward FAC cover created",
            content = @Content(schema = @Schema(implementation = FacInwardResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (negative amounts, dates inverted, share % out of range)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Ceding company or class of business not found", content = @Content)
    })
    public ApiResponse<FacInwardResponse> create(@Valid @RequestBody CreateFacInwardRequest req) {
        return ApiResponse.success(toResponse(service.create(req)));
    }

    @PostMapping("/{id}/renew")
    @PreAuthorize("hasRole('REINSURANCE_CREATE')")
    @Operation(summary = "Renew an ACTIVE inward FAC cover for a new term",
               description = "Creates a new cover row linked via renewedFromId, carrying over premium terms from the source and "
                       + "marking the source RENEWED.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inward FAC cover renewed",
            content = @Content(schema = @Schema(implementation = FacInwardResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (dates inverted)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Inward FAC cover not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Source cover not in ACTIVE state", content = @Content)
    })
    public ApiResponse<FacInwardResponse> renew(@PathVariable UUID id, @Valid @RequestBody RenewFacInwardRequest req) {
        return ApiResponse.success(toResponse(service.renew(id, req)));
    }

    @PostMapping("/{id}/extend")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    @Operation(summary = "Extend an ACTIVE inward FAC cover's period (incremental pro-rata premium)",
               description = "Moves coverTo forward and publishes a second RiFacInwardAcceptedEvent for the incremental pro-rata "
                       + "premium delta only.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inward FAC cover extended",
            content = @Content(schema = @Schema(implementation = FacInwardResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (newCoverTo not after current coverTo)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Inward FAC cover not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Inward FAC cover not in ACTIVE state", content = @Content)
    })
    public ApiResponse<FacInwardResponse> extend(@PathVariable UUID id, @Valid @RequestBody ExtendFacInwardRequest req) {
        return ApiResponse.success(toResponse(service.extend(id, req)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    @Operation(summary = "Cancel an inward FAC cover (reason required)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inward FAC cover cancelled",
            content = @Content(schema = @Schema(implementation = FacInwardResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Inward FAC cover not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Inward FAC cover already cancelled", content = @Content)
    })
    public ApiResponse<FacInwardResponse> cancel(@PathVariable UUID id, @Valid @RequestBody CancelFacInwardRequest req) {
        return ApiResponse.success(toResponse(service.cancel(id, req.reason())));
    }

    @GetMapping("/{id}/document")
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    @Operation(summary = "Download the guaranty document (PDF)",
               description = "Streams the guaranty PDF generated at create/renew time. 404 if the cover has no guaranty document "
                       + "path yet (generation failure — creation still succeeds so a document render failure never blocks accepting a risk).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Guaranty PDF stream",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE)),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Inward FAC cover not found, or no guaranty document available", content = @Content)
    })
    public ResponseEntity<byte[]> document(@PathVariable UUID id) throws Exception {
        RiFacInward cover = service.findOrThrow(id);
        if (cover.getGuarantyDocumentPath() == null) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream is = storageService.download(TenantContext.getTenantId(), cover.getGuarantyDocumentPath())) {
            byte[] bytes = is.readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + cover.getFacInwardReference() + "-guaranty.pdf\"")
                    .body(bytes);
        }
    }

    // ─── Mapping ──────────────────────────────────────────────────────────

    private FacInwardResponse toResponse(RiFacInward f) {
        return new FacInwardResponse(
                f.getId(), f.getFacInwardReference(),
                f.getCedingCompanyId(), f.getCedingCompanyName(),
                f.getClassOfBusinessId(), f.getClassOfBusinessName(),
                f.getRiskDescription(), f.getSumInsured(), f.getOurSharePct(),
                f.getAcceptedSumInsured(), f.getPremiumRate(), f.getGrossPremium(),
                f.getCommissionRate(), f.getCommissionAmount(), f.getNetPremium(),
                f.getCurrencyCode(), f.getCoverFrom(), f.getCoverTo(),
                f.getStatus(), f.getRenewedFromId(), f.getGuarantyDocumentPath(),
                f.getCancelledBy(), f.getCancelledAt(), f.getCancellationReason(),
                f.getCreatedAt());
    }
}
