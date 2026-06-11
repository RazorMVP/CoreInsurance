package com.nubeero.cia.api.platform;

import com.nubeero.cia.api.platform.dto.OnboardTenantRequest;
import com.nubeero.cia.api.platform.dto.OnboardTenantResponse;
import com.nubeero.cia.api.platform.dto.PagedResult;
import com.nubeero.cia.api.platform.dto.TenantDetailResponse;
import com.nubeero.cia.api.platform.dto.TenantStats;
import com.nubeero.cia.api.platform.dto.TenantSummary;
import com.nubeero.cia.auth.KeycloakRealms;
import com.nubeero.cia.auth.PlatformRealmProperties;
import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-tenant platform-admin REST surface under {@code /api/v1/platform}.
 *
 * <p>Gated by {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} PLUS a defense-in-depth
 * realm assertion ({@link #assertPlatformRealm}) so a stray same-named role minted by
 * a tenant realm can never reach these endpoints.
 */
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformTenantController {

    private final PlatformTenantService service;
    private final PlatformAuditService audit;
    private final PlatformRealmProperties platformProps;

    /** Default page index/size for paginated platform list endpoints. */
    private static final String DEFAULT_PAGE = "0";
    private static final String DEFAULT_SIZE = "50";
    /** Hard cap on page size, regardless of the requested size. */
    private static final int MAX_PAGE_SIZE = 500;

    @PostMapping("/tenants")
    public ResponseEntity<ApiResponse<OnboardTenantResponse>> onboard(
            @Valid @RequestBody OnboardTenantRequest req,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        assertPlatformRealm(jwt);
        var resp = service.onboard(req, actor(jwt), realm(jwt), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(resp));
    }

    @GetMapping("/tenants")
    public ApiResponse<List<TenantSummary>> list(
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size,
            @AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt);
        PagedResult<TenantSummary> result = service.list(page, Math.min(size, MAX_PAGE_SIZE));
        return ApiResponse.success(result.items(), ApiMeta.builder()
                .total(result.total()).page(result.page()).size(result.size()).build());
    }

    @GetMapping("/tenants/{schema}")
    public ApiResponse<TenantDetailResponse> detail(@PathVariable String schema, @AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt);
        return ApiResponse.success(service.detail(schema).orElseThrow(() -> new TenantNotFoundException(schema)));
    }

    @GetMapping("/stats")
    public ApiResponse<TenantStats> stats(@AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt);
        return ApiResponse.success(service.stats());
    }

    @PostMapping("/tenants/{schema}/suspend")
    public ApiResponse<Void> suspend(@PathVariable String schema, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        assertPlatformRealm(jwt);
        service.suspend(schema, actor(jwt), realm(jwt), http.getRemoteAddr());
        return ApiResponse.success(null);
    }

    @PostMapping("/tenants/{schema}/activate")
    public ApiResponse<Void> activate(@PathVariable String schema, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        assertPlatformRealm(jwt);
        service.activate(schema, actor(jwt), realm(jwt), http.getRemoteAddr());
        return ApiResponse.success(null);
    }

    @GetMapping("/audit")
    public ApiResponse<List<PlatformAuditService.PlatformAuditEntry>> auditTrail(
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size,
            @RequestParam(required = false) String targetSchema,
            @AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt);
        int capped = Math.min(size, MAX_PAGE_SIZE);
        var rows = audit.recent(page, capped, targetSchema);
        long total = audit.count(targetSchema);
        return ApiResponse.success(rows, ApiMeta.builder().total(total).page(page).size(capped).build());
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
