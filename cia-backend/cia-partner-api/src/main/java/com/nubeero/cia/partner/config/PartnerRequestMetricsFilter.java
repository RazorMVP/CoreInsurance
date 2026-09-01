package com.nubeero.cia.partner.config;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.partner.usage.PartnerUsageRollupStore;
import com.nubeero.cia.partner.usage.PartnerUsageRollupStore.StatusClass;
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
 * Per-app request telemetry for {@code /partner/v1/**} — records exactly one counter increment
 * per response into {@link PartnerUsageRollupStore}, powering the Usage Dashboard's
 * {@code GET /portal/apps/{id}/usage}.
 *
 * <h2>Filter order</h2>
 * Registered by {@link PartnerSecurityConfig} directly after {@code TenantContextFilter} — i.e.
 * BEFORE {@link PartnerScopeFilter} and {@link PartnerRateLimitFilter}, not after them. This is
 * deliberate: {@code chain.doFilter} below wraps the ENTIRE rest of the chain (scope check, rate
 * limit, the controller itself), so whatever those later filters/handlers ultimately write to
 * {@code response} — a 403 scope-denied, a 429 rate-limited, a genuine 2xx/4xx/5xx from the
 * controller — is what gets counted. Positioning this filter any later would blind it to
 * short-circuited responses those earlier filters produce. "After TenantContextFilter" is the
 * part that matters for correctness: {@code client_id} (from the validated JWT) and the tenant
 * schema (from {@link TenantContext}) must both be resolved before this filter can key a counter.
 */
@Component
public class PartnerRequestMetricsFilter extends OncePerRequestFilter {

    private final PartnerUsageRollupStore rollupStore;

    public PartnerRequestMetricsFilter(PartnerUsageRollupStore rollupStore) {
        this.rollupStore = rollupStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            recordMetric(response);
        }
    }

    private void recordMetric(HttpServletResponse response) {
        String clientId = resolveClientId();
        if (clientId == null || clientId.isBlank()) {
            // No identifiable client (e.g. request never authenticated) — nothing to attribute
            // the counter to.
            return;
        }
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        StatusClass statusClass = StatusClass.fromHttpStatus(response.getStatus());
        rollupStore.increment(tenantId, clientId, PartnerUsageRollupStore.today(), statusClass);
    }

    /**
     * Same resolution order as {@link PartnerRateLimitFilter#resolveClientId}: Keycloak
     * client-credentials tokens carry the client id as {@code client_id} and/or {@code azp}
     * (authorized party), falling back to {@code sub} (the service account's subject).
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
