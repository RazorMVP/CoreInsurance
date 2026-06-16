package com.nubeero.cia.api.platform;

import com.nubeero.cia.api.platform.dto.InviteSuperAdminRequest;
import com.nubeero.cia.api.platform.dto.InviteSuperAdminResponse;
import com.nubeero.cia.api.platform.dto.SuperAdminSummary;
import com.nubeero.cia.auth.KeycloakRealms;
import com.nubeero.cia.auth.PlatformRealmProperties;
import com.nubeero.cia.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform super-admin lifecycle REST surface under {@code /api/v1/platform/super-admins}.
 *
 * <p>Separate controller from {@link PlatformTenantController} (identity lifecycle is a distinct
 * concern from tenant lifecycle) but the SAME double gate: {@code hasRole('SUPER_ADMIN')} PLUS
 * the defense-in-depth {@link #assertPlatformRealm} so a stray same-named role minted by a tenant
 * realm can never reach these endpoints. The 409/404 from the {@link SuperAdminExceptions}
 * {@code CiaException} subclasses route through the global handler; the Keycloak-disabled marker is
 * mapped here to HTTP 503 by a controller-local {@code @ExceptionHandler} (mirrors {@code UserController}).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform/super-admins")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformSuperAdminController {

    private final PlatformSuperAdminService service;
    private final PlatformRealmProperties platformProps;

    @GetMapping
    public ApiResponse<List<SuperAdminSummary>> list(@AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt);
        return ApiResponse.success(service.list());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<InviteSuperAdminResponse>> invite(
            @Valid @RequestBody InviteSuperAdminRequest req,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        assertPlatformRealm(jwt);
        var resp = service.invite(req, actor(jwt), realm(jwt), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(resp));
    }

    @DeleteMapping("/{username}")
    public ApiResponse<Void> revoke(@PathVariable String username,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        assertPlatformRealm(jwt);
        service.revoke(username, actor(jwt), realm(jwt), http.getRemoteAddr());
        return ApiResponse.success(null);
    }

    /** Keycloak admin client disabled (dev without Keycloak) → 503. Mirrors UserController. */
    @ExceptionHandler(SuperAdminExceptions.KeycloakAdminDisabled.class)
    public ResponseEntity<ApiResponse<Void>> handleAdminDisabled(SuperAdminExceptions.KeycloakAdminDisabled e) {
        log.warn("Super-admin endpoint invoked with admin client disabled: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("KEYCLOAK_ADMIN_DISABLED", e.getMessage()));
    }

    /** Defense-in-depth: SUPER_ADMIN should only ever be minted by the platform realm,
     *  but verify the validated iss realm anyway so a stray same-named role elsewhere
     *  cannot reach these endpoints. */
    private void assertPlatformRealm(Jwt jwt) {
        String realm = jwt == null ? null : KeycloakRealms.realmOf(jwt.getClaimAsString("iss"));
        if (realm == null || !realm.equals(platformProps.getRealm())) {
            throw new AccessDeniedException("Not a platform-realm token");
        }
    }

    private static String actor(Jwt jwt) {
        return jwt.getClaimAsString("preferred_username");
    }

    private static String realm(Jwt jwt) {
        return KeycloakRealms.realmOf(jwt.getClaimAsString("iss"));
    }
}
