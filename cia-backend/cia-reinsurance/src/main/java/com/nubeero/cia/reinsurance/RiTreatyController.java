package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.reinsurance.dto.*;
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
@RequiredArgsConstructor
public class RiTreatyController {

    private final TreatyService service;

    @GetMapping
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    public ApiResponse<Page<TreatyResponse>> list(
            @RequestParam(required = false) TreatyType type,
            @RequestParam(required = false) TreatyStatus status,
            @RequestParam(required = false) Integer year,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.list(type, status, year, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    public ApiResponse<TreatyResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REINSURANCE_CREATE')")
    public ApiResponse<TreatyResponse> create(@Valid @RequestBody CreateTreatyRequest req) {
        return ApiResponse.success(toResponse(service.create(req)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    public ApiResponse<TreatyResponse> activate(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.activate(id)));
    }

    @PostMapping("/{id}/expire")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    public ApiResponse<TreatyResponse> expire(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.expire(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    public ApiResponse<TreatyResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.cancel(id)));
    }

    // ─── Participants ──────────────────────────────────────────────────────

    @PostMapping("/{id}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    public ApiResponse<TreatyParticipantResponse> addParticipant(
            @PathVariable UUID id,
            @Valid @RequestBody AddParticipantRequest req) {
        return ApiResponse.success(toParticipantResponse(service.addParticipant(id, req)));
    }

    @DeleteMapping("/{id}/participants/{participantId}")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
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
