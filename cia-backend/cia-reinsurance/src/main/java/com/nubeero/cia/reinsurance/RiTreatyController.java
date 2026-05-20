package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.reinsurance.dto.*;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ri/treaties")
@Tag(name = "Reinsurance Treaties",
     description = "Treaty setup — Surplus / Quota Share / XOL. State machine: DRAFT → ACTIVE → EXPIRED → CANCELLED. Treaty year = policy start date year (not policy creation year). Each treaty has a participant list (reinsurance companies with their share percentages); lead reinsurer drives offer/acceptance.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class RiTreatyController {

    private final TreatyService service;

    @GetMapping
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    @Operation(summary = "List treaties (paginated, filterable)",
               description = "Filter by treaty type, status, and/or treaty year. Returns treaty headers with participants embedded.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Treaty page",
            content = @Content(schema = @Schema(implementation = TreatyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_VIEW", content = @Content)
    })
    public ApiResponse<Page<TreatyResponse>> list(
            @RequestParam(required = false) TreatyType type,
            @RequestParam(required = false) TreatyStatus status,
            @RequestParam(required = false) Integer year,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.list(type, status, year, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    @Operation(summary = "Get treaty detail",
               description = "Returns the treaty header plus all active participants (soft-deleted participants excluded).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Treaty found",
            content = @Content(schema = @Schema(implementation = TreatyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Treaty not found", content = @Content)
    })
    public ApiResponse<TreatyResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REINSURANCE_CREATE')")
    @Operation(summary = "Create a new treaty (DRAFT)",
               description = "Treaty-type-specific fields are validated by the service: Surplus requires retention + surplus capacity; QS requires per-participant shares summing to 100%; XOL requires per-risk retention + limit.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Treaty created",
            content = @Content(schema = @Schema(implementation = TreatyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (mismatched type fields, dates inverted, etc.)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_CREATE", content = @Content)
    })
    public ApiResponse<TreatyResponse> create(@Valid @RequestBody CreateTreatyRequest req) {
        return ApiResponse.success(toResponse(service.create(req)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    @Operation(summary = "Activate a DRAFT treaty",
               description = "Transitions DRAFT → ACTIVE. Validates the participant list (QS shares sum to 100%, lead reinsurer present, etc.). Active treaties become eligible for automatic RI allocation on new policies.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Treaty activated",
            content = @Content(schema = @Schema(implementation = TreatyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Participants invalid (shares mismatch, no lead, etc.)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Treaty not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Treaty not in DRAFT state", content = @Content)
    })
    public ApiResponse<TreatyResponse> activate(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.activate(id)));
    }

    @PostMapping("/{id}/expire")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    @Operation(summary = "Expire an ACTIVE treaty",
               description = "Transitions ACTIVE → EXPIRED. Used at end of treaty year. Allocations already made under this treaty remain valid (treaties protect by inception date, not by current status).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Treaty expired",
            content = @Content(schema = @Schema(implementation = TreatyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Treaty not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Treaty not ACTIVE", content = @Content)
    })
    public ApiResponse<TreatyResponse> expire(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.expire(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    @Operation(summary = "Cancel a treaty",
               description = "Transitions to CANCELLED from any non-terminal state. Stronger than expire — used when terminating the treaty mid-year due to commercial breakdown or insurer downgrade.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Treaty cancelled",
            content = @Content(schema = @Schema(implementation = TreatyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Treaty not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Treaty already in terminal state", content = @Content)
    })
    public ApiResponse<TreatyResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.cancel(id)));
    }

    // ─── Participants ──────────────────────────────────────────────────────

    @PostMapping("/{id}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    @Operation(summary = "Add a reinsurance company as a treaty participant",
               description = "Surplus treaty: participant gets a surplus_line slot. QS: participant gets a share percentage. Setting isLead=true designates this participant as the lead reinsurer (exactly one per treaty).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Participant added",
            content = @Content(schema = @Schema(implementation = TreatyParticipantResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Share would exceed 100%, surplus line conflict, or duplicate lead", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Treaty or reinsurance company not found", content = @Content)
    })
    public ApiResponse<TreatyParticipantResponse> addParticipant(
            @PathVariable UUID id,
            @Valid @RequestBody AddParticipantRequest req) {
        return ApiResponse.success(toParticipantResponse(service.addParticipant(id, req)));
    }

    @DeleteMapping("/{id}/participants/{participantId}")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    @Operation(summary = "Remove a treaty participant",
               description = "Soft-deletes the participant. Historical allocation lines that reference this participant are preserved (audit trail). Allowed in DRAFT or ACTIVE state.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Participant removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Treaty or participant not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cannot remove lead participant — promote another lead first", content = @Content)
    })
    public ApiResponse<Void> removeParticipant(
            @PathVariable UUID id,
            @PathVariable UUID participantId) {
        service.removeParticipant(id, participantId);
        return ApiResponse.success(null);
    }

    // ─── Mapping ──────────────────────────────────────────────────────────

    private TreatyResponse toResponse(RiTreaty t) {
        return new TreatyResponse(
                t.getId(), t.getTreatyType(), t.getStatus(), t.getTreatyYear(),
                t.getProductId(), t.getClassOfBusinessId(),
                t.getRetentionLimit(), t.getSurplusCapacity(),
                t.getXolPerRiskRetention(), t.getXolPerRiskLimit(),
                t.getCurrencyCode(), t.getEffectiveDate(), t.getExpiryDate(),
                t.getDescription(),
                t.getParticipants().stream()
                        .filter(p -> p.getDeletedAt() == null)
                        .map(this::toParticipantResponse)
                        .toList(),
                t.getCreatedAt()
        );
    }

    private TreatyParticipantResponse toParticipantResponse(RiTreatyParticipant p) {
        return new TreatyParticipantResponse(
                p.getId(), p.getReinsuranceCompanyId(), p.getReinsuranceCompanyName(),
                p.getSharePercentage(), p.getSurplusLine(), p.isLead(),
                p.getCommissionRate(), p.getCreatedAt()
        );
    }
}
