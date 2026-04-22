package com.nubeero.cia.partner.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Enforces OAuth2 scope requirements per partner API endpoint.
 * Runs after JWT authentication — the token is already validated by Spring Security.
 */
@Component
public class PartnerScopeFilter extends OncePerRequestFilter {

    private static final Map<String, String> SCOPE_MAP = Map.ofEntries(
            Map.entry("GET /partner/v1/products", "products:read"),
            Map.entry("GET /partner/v1/products/", "products:read"),
            Map.entry("POST /partner/v1/quotes", "quotes:create"),
            Map.entry("GET /partner/v1/quotes/", "quotes:read"),
            Map.entry("POST /partner/v1/customers", "customers:create"),
            Map.entry("GET /partner/v1/customers/", "customers:read"),
            Map.entry("POST /partner/v1/policies", "policies:create"),
            Map.entry("GET /partner/v1/policies/", "policies:read"),
            Map.entry("POST /partner/v1/policies/", "claims:create"),
            Map.entry("GET /partner/v1/claims/", "claims:read"),
            Map.entry("POST /partner/v1/webhooks", "webhooks:manage"),
            Map.entry("GET /partner/v1/webhooks", "webhooks:manage"),
            Map.entry("DELETE /partner/v1/webhooks/", "webhooks:manage")
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/partner/v1/")) {
            chain.doFilter(request, response);
            return;
        }

        String requiredScope = resolveRequiredScope(request.getMethod(), path);
        if (requiredScope == null) {
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getCredentials() instanceof Jwt jwt)) {
            forbidden(response, "No valid authentication");
            return;
        }

        List<String> scopes = jwt.getClaimAsStringList("scope");
        if (scopes == null || !scopes.contains(requiredScope)) {
            forbidden(response, "Missing required scope: " + requiredScope);
            return;
        }

        chain.doFilter(request, response);
    }

    private String resolveRequiredScope(String method, String path) {
        for (Map.Entry<String, String> entry : SCOPE_MAP.entrySet()) {
            String key = entry.getKey();
            int spaceIdx = key.indexOf(' ');
            String mapMethod = key.substring(0, spaceIdx);
            String mapPath = key.substring(spaceIdx + 1);
            if (method.equalsIgnoreCase(mapMethod)
                    && (path.equals(mapPath) || path.startsWith(mapPath))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void forbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("""
                {"errors":[{"code":"INSUFFICIENT_SCOPE","message":"%s"}]}
                """.formatted(message));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/partner/docs") || path.startsWith("/partner/v3/api-docs");
    }
}
