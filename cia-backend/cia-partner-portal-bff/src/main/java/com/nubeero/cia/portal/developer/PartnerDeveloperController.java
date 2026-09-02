package com.nubeero.cia.portal.developer;

import com.nubeero.cia.auth.AuthenticatedUserService;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.portal.developer.dto.InviteDeveloperRequest;
import com.nubeero.cia.portal.developer.dto.PartnerDeveloperGrantResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Internal, System-Admin-gated endpoints for inviting/listing/revoking the human partner
 * developers who may manage a given Partner App via the Partner Portal.
 *
 * <p>A sibling of {@code PartnerAppController} (cia-partner-api) rather than an addition to it:
 * this controller needs {@code PartnerPortalGrantRepository}, which lives in
 * {@code cia-partner-portal-bff} — a module that already depends on {@code cia-partner-api} (for
 * {@link com.nubeero.cia.partner.app.PartnerAppService}). Adding the reverse dependency onto
 * {@code cia-partner-api} would create a module cycle, so the controller lives here instead. It
 * shares the same {@code /api/v1/partner-apps/{id}} base path and the same {@code setup:*}
 * authorities as {@code PartnerAppController}'s mutating endpoints, so the two read as one
 * logical API surface even though they're two classes.
 */
@RestController
@RequestMapping("/api/v1/partner-apps/{id}/developers")
@Tag(name = "Partner App Management", description = "Internal: manage Insurtech partner app registrations")
@RequiredArgsConstructor
public class PartnerDeveloperController {

    private final PartnerDeveloperService developerService;
    private final AuthenticatedUserService authenticatedUserService;

    @PostMapping
    @PreAuthorize("hasAuthority('setup:create')")
    @Operation(summary = "Invite a partner developer to manage this partner app",
               description = "Creates (or, if already granted, returns 409 on) a "
                       + "public.partner_portal_grant row for the given email + role.")
    public ResponseEntity<ApiResponse<PartnerDeveloperGrantResponse>> invite(
            @PathVariable UUID id, @Valid @RequestBody InviteDeveloperRequest request) {
        String actor = authenticatedUserService.currentUserName();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(developerService.invite(id, request, actor)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('setup:view')")
    @Operation(summary = "List developers granted access to this partner app")
    public ResponseEntity<ApiResponse<List<PartnerDeveloperGrantResponse>>> list(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(developerService.list(id)));
    }

    @DeleteMapping("/{grantId}")
    @PreAuthorize("hasAuthority('setup:update')")
    @Operation(summary = "Revoke a developer's grant on this partner app (soft delete)")
    public ResponseEntity<Void> revoke(@PathVariable UUID id, @PathVariable UUID grantId) {
        String actor = authenticatedUserService.currentUserName();
        developerService.revoke(id, grantId, actor);
        return ResponseEntity.noContent().build();
    }
}
