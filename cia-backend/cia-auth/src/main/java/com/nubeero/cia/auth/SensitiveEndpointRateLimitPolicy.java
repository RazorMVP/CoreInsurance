package com.nubeero.cia.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class SensitiveEndpointRateLimitPolicy {

    private final boolean enabled;
    private final int partnerRequestsPerMinute;
    private final int sensitiveRequestsPerMinute;
    private final int failedLoginRequestsPerMinute;

    public SensitiveEndpointRateLimitPolicy(
            @Value("${cia.rate-limit.enabled:true}") boolean enabled,
            @Value("${cia.rate-limit.partner-requests-per-minute:300}") int partnerRequestsPerMinute,
            @Value("${cia.rate-limit.sensitive-requests-per-minute:60}") int sensitiveRequestsPerMinute,
            @Value("${cia.rate-limit.failed-login-requests-per-minute:10}") int failedLoginRequestsPerMinute) {
        this.enabled = enabled;
        this.partnerRequestsPerMinute = partnerRequestsPerMinute;
        this.sensitiveRequestsPerMinute = sensitiveRequestsPerMinute;
        this.failedLoginRequestsPerMinute = failedLoginRequestsPerMinute;
    }

    public Optional<Rule> match(HttpServletRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        String method = request.getMethod();

        if (path.equals("/api/v1/auth/login/failed")) {
            return Optional.of(new Rule("failed-login", failedLoginRequestsPerMinute, Duration.ofMinutes(1)));
        }
        if (path.startsWith("/partner/v1/")) {
            return Optional.of(new Rule("partner-api", partnerRequestsPerMinute, Duration.ofMinutes(1)));
        }
        if (path.equals("/admin/v1/tenants") || path.startsWith("/admin/v1/tenants/")) {
            return Optional.of(new Rule("tenant-admin", sensitiveRequestsPerMinute, Duration.ofMinutes(1)));
        }
        if (path.startsWith("/api/v1/setup/")
                || path.startsWith("/api/v1/audit/export")
                || ("POST".equalsIgnoreCase(method) && path.startsWith("/api/v1/claims/") && path.contains("/documents"))
                || (("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))
                    && path.startsWith("/api/v1/customers"))) {
            return Optional.of(new Rule("sensitive-api", sensitiveRequestsPerMinute, Duration.ofMinutes(1)));
        }
        return Optional.empty();
    }

    public record Rule(String id, int capacity, Duration window) {
    }
}
