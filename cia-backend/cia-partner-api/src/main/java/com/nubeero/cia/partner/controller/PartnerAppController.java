package com.nubeero.cia.partner.controller;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.partner.app.PartnerApp;
import com.nubeero.cia.partner.app.PartnerAppService;
import com.nubeero.cia.partner.app.dto.CreatePartnerAppRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/partner-apps")
@Tag(name = "Partner App Management", description = "Internal: manage Insurtech partner app registrations")
@RequiredArgsConstructor
public class PartnerAppController {

    private final PartnerAppService partnerAppService;

    @GetMapping
    @PreAuthorize("hasAuthority('setup:view')")
    @Operation(summary = "List all partner app registrations")
    public ResponseEntity<ApiResponse<Page<PartnerApp>>> list(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(partnerAppService.list(pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('setup:view')")
    @Operation(summary = "Get partner app details")
    public ResponseEntity<ApiResponse<PartnerApp>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(partnerAppService.findOrThrow(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('setup:create')")
    @Operation(summary = "Register a new partner app",
               description = "Creates the partner record. Keycloak client creation is handled separately.")
    public ResponseEntity<ApiResponse<PartnerApp>> create(@Valid @RequestBody CreatePartnerAppRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(partnerAppService.create(request)));
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('setup:update')")
    @Operation(summary = "Enable or disable a partner app")
    public ResponseEntity<ApiResponse<PartnerApp>> toggleActive(
            @PathVariable UUID id, @RequestParam boolean active) {
        return ResponseEntity.ok(ApiResponse.success(partnerAppService.toggleActive(id, active)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('setup:update')")
    @Operation(summary = "Revoke a partner app (soft delete)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        partnerAppService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
