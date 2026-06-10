package com.nubeero.cia.auth;

import com.nubeero.cia.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    static final String MDC_TENANT_KEY = "tenant";

    private final PlatformRealmProperties platformProps;
    private final TenantActivationLookup activationLookup;

    public TenantContextFilter(PlatformRealmProperties platformProps,
                               @Nullable TenantActivationLookup activationLookup) {
        this.platformProps = platformProps;
        this.activationLookup = activationLookup;
        if (platformProps.getTenantAllowlist().isEnabled() && activationLookup == null) {
            log.warn("cia.platform.tenant-allowlist.enabled=true but no TenantActivationLookup bean is "
                    + "present — all non-platform tenant requests will be rejected with 401 TENANT_INACTIVE");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                // Realm from the validated `iss` is authoritative; fall back to the tenant_id claim.
                String realm = KeycloakRealms.realmOf(jwt.getClaimAsString("iss"));
                String tenantId = realm;
                if (tenantId == null || tenantId.isBlank()) {
                    tenantId = jwt.getClaimAsString("tenant_id");
                }
                boolean isPlatform = realm != null && realm.equals(platformProps.getRealm());
                if (isPlatform) {
                    tenantId = "public";   // platform plane operates on the registry schema
                }
                // Allowlist gate: reject suspended/unknown tenant realms (platform exempt; off by default).
                // Fails CLOSED if enabled but no lookup is wired (activationLookup == null).
                if (!isPlatform && realm != null
                        && platformProps.getTenantAllowlist().isEnabled()
                        && (activationLookup == null || !activationLookup.isActive(realm))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setHeader("WWW-Authenticate", "Bearer error=\"inactive_tenant\"");
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"errors\":[{\"code\":\"TENANT_INACTIVE\","
                            + "\"message\":\"Tenant is suspended or unknown\"}]}");
                    return;   // finally still clears TenantContext + MDC
                }
                if (tenantId != null && !tenantId.isBlank()) {
                    TenantContext.setTenantId(tenantId);
                    MDC.put(MDC_TENANT_KEY, tenantId);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_TENANT_KEY);
        }
    }
}
