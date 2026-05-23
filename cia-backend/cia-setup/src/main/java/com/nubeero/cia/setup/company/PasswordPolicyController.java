package com.nubeero.cia.setup.company;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.company.dto.PasswordPolicyRequest;
import com.nubeero.cia.setup.company.dto.PasswordPolicyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/setup/password-policy")
@Tag(name = "Setup — Password Policy", description = "Per-tenant password-policy bookkeeping. Storage-only — actual login-time enforcement is owned by Keycloak's realm policy. The Setup → Password Policy page reads/writes this.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PasswordPolicyController {

    private final PasswordPolicyService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "Get password policy (tenant singleton; returns V3 defaults if never configured)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Password policy",
            content = @Content(schema = @Schema(implementation = PasswordPolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<PasswordPolicyResponse>> get() {
        return ResponseEntity.ok(ApiResponse.success(service.get()));
    }

    @PutMapping
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Upsert password policy")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Upserted",
            content = @Content(schema = @Schema(implementation = PasswordPolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<PasswordPolicyResponse>> upsert(
            @Valid @RequestBody PasswordPolicyRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.upsert(request)));
    }
}
