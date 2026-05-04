package com.nubeero.cia.partner.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Enforces OAuth2 scope requirements per partner API endpoint.
 * Runs after JWT authentication — the token is already validated by Spring Security.
 *
 * <p>Routes are evaluated in declaration order; the first match wins. Most-specific
 * patterns must precede broader ones (e.g. {@code /policies/&#42;/claims} before
 * {@code /policies}). Patterns use Spring's {@link AntPathMatcher} where {@code *}
 * matches a single path segment.
 */
@Component
public class PartnerScopeFilter extends OncePerRequestFilter {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private record Route(String method, String pattern, String scope) {}

    /**
     * Order matters: longer / more specific patterns must come first. The first
     * route whose method+pattern matches the request wins. This avoids the
     * collision where {@code POST /partner/v1/policies/{id}/claims} could
     * otherwise be resolved as either {@code policies:create} or {@code claims:create}.
     */
    private static final List<Route> ROUTES = List.of(
            // Claims under a policy — must come before plain /policies
            new Route("POST",   "/partner/v1/policies/*/claims",  "claims:create"),
            new Route("GET",    "/partner/v1/policies/*/document", "policies:read"),

            // Customers — typed-create routes before generic id route
            new Route("POST",   "/partner/v1/customers/individual", "customers:create"),
            new Route("POST",   "/partner/v1/customers/corporate",  "customers:create"),
            new Route("GET",    "/partner/v1/customers/*",          "customers:read"),

            // Products
            new Route("GET",    "/partner/v1/products/*/classes",   "products:read"),
            new Route("GET",    "/partner/v1/products/*",           "products:read"),
            new Route("GET",    "/partner/v1/products",             "products:read"),

            // Quotes
            new Route("POST",   "/partner/v1/quotes",               "quotes:create"),
            new Route("GET",    "/partner/v1/quotes/*",             "quotes:read"),

            // Policies
            new Route("POST",   "/partner/v1/policies",             "policies:create"),
            new Route("GET",    "/partner/v1/policies/*",           "policies:read"),

            // Claims (top-level lookup)
            new Route("GET",    "/partner/v1/claims/*",             "claims:read"),

            // Webhooks
            new Route("POST",   "/partner/v1/webhooks",             "webhooks:manage"),
            new Route("GET",    "/partner/v1/webhooks",             "webhooks:manage"),
            new Route("DELETE", "/partner/v1/webhooks/*",           "webhooks:manage")
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
     * Keycloak issues the OAuth2 {@code scope} claim as a space-delimited string per
     * RFC 8693 (e.g. {@code "products:read quotes:create"}). Some configurations may
     * serialise it as a JSON array. Handle both shapes; never throw on a malformed
     * claim — return an empty list and let the missing-scope check reject the request.
     */
    private List<String> extractScopes(Jwt jwt) {
        try {
            Object raw = jwt.getClaims().get("scope");
            if (raw instanceof String s) {
                if (s.isBlank()) return Collections.emptyList();
                return Arrays.asList(s.trim().split("\\s+"));
            }
            if (raw instanceof List<?>) {
                List<String> typed = jwt.getClaimAsStringList("scope");
                return typed != null ? typed : Collections.emptyList();
            }
        } catch (RuntimeException ignored) {
            // Malformed claim — fall through to empty list, will be rejected as insufficient scope.
        }
        return Collections.emptyList();
    }

    String resolveRequiredScope(String method, String path) {
        for (Route route : ROUTES) {
            if (route.method.equalsIgnoreCase(method) && MATCHER.match(route.pattern, path)) {
                return route.scope;
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
