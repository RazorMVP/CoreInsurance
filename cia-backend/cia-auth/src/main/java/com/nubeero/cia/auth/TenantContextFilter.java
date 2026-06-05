package com.nubeero.cia.auth;

import com.nubeero.cia.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                // Realm parsed from the validated `iss` is authoritative (it is
                // what the signature was checked against); fall back to the
                // tenant_id claim for tokens without a parseable realm issuer.
                String tenantId = KeycloakRealms.realmOf(jwt.getClaimAsString("iss"));
                if (tenantId == null || tenantId.isBlank()) {
                    tenantId = jwt.getClaimAsString("tenant_id");
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
