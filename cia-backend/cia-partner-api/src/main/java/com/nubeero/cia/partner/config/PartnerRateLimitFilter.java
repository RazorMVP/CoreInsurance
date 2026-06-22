package com.nubeero.cia.partner.config;

import com.nubeero.cia.partner.config.PartnerRateLimitService.RateLimitVerdict;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client rate-limit filter for {@code /partner/v1/**}. Runs after JWT auth +
 * {@code TenantContextFilter} (so the validated token and tenant schema are both
 * available), keys the bucket by the token's client_id, and sizes it to that
 * partner's {@code rateLimitRpm} via {@link PartnerRateLimitService}.
 *
 * <p>Emits {@code X-RateLimit-Limit/Remaining/Reset} on every limited request and
 * {@code 429} + {@code Retry-After} (+ a {@code RATE_LIMIT_EXCEEDED} error envelope)
 * when the bucket is empty — mirroring {@link PartnerScopeFilter}'s shape.
 */
@Component
public class PartnerRateLimitFilter extends OncePerRequestFilter {

    private final PartnerRateLimitService rateLimitService;
    private final PartnerRateLimitProperties properties;

    public PartnerRateLimitFilter(PartnerRateLimitService rateLimitService,
                                  PartnerRateLimitProperties properties) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String clientId = resolveClientId();
        if (clientId == null || clientId.isBlank()) {
            // No identifiable client (e.g. missing/odd token) — let the auth layer
            // be the arbiter; nothing to rate-limit against.
            chain.doFilter(request, response);
            return;
        }

        RateLimitVerdict verdict = rateLimitService.tryConsume(clientId);
        response.setHeader("X-RateLimit-Limit", Long.toString(verdict.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(verdict.remaining()));
        response.setHeader("X-RateLimit-Reset", Long.toString(verdict.resetEpochSeconds()));

        if (!verdict.allowed()) {
            response.setStatus(429); // 429 Too Many Requests
            response.setHeader("Retry-After", Long.toString(verdict.retryAfterSeconds()));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"errors\":[{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\""
                            + "Rate limit exceeded. Retry after " + verdict.retryAfterSeconds()
                            + " seconds.\"}]}");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Keycloak client-credentials tokens carry the client id as {@code client_id}
     * and/or {@code azp} (authorized party). Fall back to {@code sub} (the service
     * account's subject) so a bucket always has a stable key.
     */
    private String resolveClientId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getCredentials() instanceof Jwt jwt)) {
            return null;
        }
        String clientId = jwt.getClaimAsString("client_id");
        if (clientId == null || clientId.isBlank()) {
            clientId = jwt.getClaimAsString("azp");
        }
        if (clientId == null || clientId.isBlank()) {
            clientId = jwt.getSubject();
        }
        return clientId;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/partner/v1/")
                || path.startsWith("/partner/docs")
                || path.startsWith("/partner/v3/api-docs");
    }
}
