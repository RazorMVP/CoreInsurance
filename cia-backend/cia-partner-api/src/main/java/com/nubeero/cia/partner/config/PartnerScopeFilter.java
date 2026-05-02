package com.nubeero.cia.partner.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
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

        List<String> scopes = extractScopes(jwt);
        if (!scopes.contains(requiredScope)) {
            forbidden(response, "Missing required scope: " + requiredScope);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Keycloak issues the OAuth2 `scope` claim as a space-delimited string per
     * RFC 8693 (e.g. "products:read quotes:create"). Some configurations may
     * serialise it as a JSON array. Handle both shapes.
     */
    private List<String> extractScopes(Jwt jwt) {
        Object raw = jwt.getClaims().get("scope");
        if (raw instanceof String s) {
            return Arrays.asList(s.trim().split("\\s+"));
        }
        if (raw instanceof List<?>) {
            List<String> typed = jwt.getClaimAsStringList("scope");
            return typed != null ? typed : Collections.emptyList();
        }
        return Collections.emptyList();
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
        response.getWriter().write(
                "{\"errors\":[{\"code\":\"INSUFFICIENT_SCOPE\",\"message\":\""
                        + jsonEscape(message) + "\"}]}");
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/partner/docs") || path.startsWith("/partner/v3/api-docs");
    }
}
