package com.nubeero.cia.setup.user;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.user.dto.UserRequest;
import com.nubeero.cia.setup.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Module 1 — Users. Backlog F1e. Proxies user CRUD + reset-password +
 * activate/deactivate over the Keycloak admin client; there is no local
 * {@code users} table.
 *
 * <p>When the admin client is disabled (dev environment without a Keycloak
 * instance), every endpoint returns HTTP 503 with a clear message.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/setup/users")
@Tag(name = "Setup — Users",
     description = "System user management (Module 1). Users live in Keycloak; this controller is a thin proxy over the Keycloak admin client. Access group assignment is stored as a Keycloak user attribute (accessGroupId).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class UserController {

    private final UserService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "List users")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User list",
            content = @Content(schema = @Schema(implementation = UserResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Keycloak admin client unavailable", content = @Content)
    })
    public ResponseEntity<ApiResponse<List<UserResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.list()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "Get user by id")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Found",
            content = @Content(schema = @Schema(implementation = UserResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Keycloak admin client unavailable", content = @Content)
    })
    public ResponseEntity<ApiResponse<UserResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SETUP_CREATE')")
    @Operation(summary = "Create a user",
               description = "Creates the user in Keycloak with the configured access group attribute, then triggers Keycloak's UPDATE_PASSWORD + VERIFY_EMAIL action emails. The user sets their password on first sign-in.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = UserResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Email already taken", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Keycloak admin client unavailable", content = @Content)
    })
    public ResponseEntity<ApiResponse<UserResponse>> create(@Valid @RequestBody UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Update user profile + access group",
               description = "Email is immutable on update — Keycloak treats it as the effective username and rotating it would invalidate existing JWTs. Status (ACTIVE/INACTIVE) and access group reassignment are honoured.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated",
            content = @Content(schema = @Schema(implementation = UserResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Keycloak admin client unavailable", content = @Content)
    })
    public ResponseEntity<ApiResponse<UserResponse>> update(@PathVariable String id, @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, request)));
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Send reset-password email",
               description = "Triggers Keycloak's UPDATE_PASSWORD action email. The user sets a new password via the emailed link. No temporary password is generated; nothing leaves the realm.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Email queued"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Keycloak admin client unavailable", content = @Content)
    })
    public ResponseEntity<ApiResponse<Void>> resetPassword(@PathVariable String id) {
        service.resetPassword(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Deactivate a user (enabled=false)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Deactivated",
            content = @Content(schema = @Schema(implementation = UserResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Keycloak admin client unavailable", content = @Content)
    })
    public ResponseEntity<ApiResponse<UserResponse>> deactivate(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(service.deactivate(id)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Re-activate a user (enabled=true)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Activated",
            content = @Content(schema = @Schema(implementation = UserResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Keycloak admin client unavailable", content = @Content)
    })
    public ResponseEntity<ApiResponse<UserResponse>> activate(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(service.activate(id)));
    }

    /**
     * Translate {@code KeycloakAdminUnavailableException} (thrown when the
     * admin client bean isn't present — dev mode) into a clean 503 with the
     * standard ApiResponse envelope.
     */
    @ExceptionHandler(UserService.KeycloakAdminUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleAdminUnavailable(UserService.KeycloakAdminUnavailableException e) {
        log.warn("UserController invoked with admin client disabled: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ApiResponse.error("KEYCLOAK_ADMIN_DISABLED", e.getMessage()));
    }
}
