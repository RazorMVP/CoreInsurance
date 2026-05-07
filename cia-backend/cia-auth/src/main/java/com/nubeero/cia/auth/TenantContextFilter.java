package com.nubeero.cia.auth;

import com.nubeero.cia.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantRegistry tenantRegistry;

    public TenantContextFilter(TenantRegistry tenantRegistry) {
        this.tenantRegistry = tenantRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                String tenantClaim = jwt.getClaimAsString("tenant_id");
                if (tenantClaim == null || tenantClaim.isBlank()) {
                    if (isTenantlessPlatformRequest(request)) {
                        filterChain.doFilter(request, response);
                        return;
                    }
                    forbidden(response, "Missing tenant_id claim");
                    return;
                }

                var schema = tenantRegistry.resolveActiveTenantSchema(tenantClaim);
                if (schema.isEmpty()) {
                    forbidden(response, "Unknown or inactive tenant");
                    return;
                }
                TenantContext.setTenantId(schema.get());
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private boolean isTenantlessPlatformRequest(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        return path.equals("/admin/v1/tenants") || path.startsWith("/admin/v1/tenants/");
    }

    private void forbidden(HttpServletResponse response, String message) throws IOException {
        log.warn("Tenant resolution failed: {}", message);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"tenant_resolution_failed\",\"message\":\"" + message + "\"}");
    }
}
