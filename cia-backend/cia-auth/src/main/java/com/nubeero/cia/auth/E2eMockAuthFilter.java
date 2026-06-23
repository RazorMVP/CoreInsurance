package com.nubeero.cia.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * E2E-only mock authentication. When the {@code e2e} profile is active (always
 * paired with {@code dev}), this filter injects an authenticated principal
 * carrying every {@code @PreAuthorize} authority/role the internal controllers
 * check, so the full-stack Playwright golden paths can exercise protected
 * endpoints without a live Keycloak.
 *
 * <p><b>Why this exists:</b> {@code @EnableMethodSecurity} (on {@link SecurityConfig})
 * is profile-unconditional, so {@code @PreAuthorize} is enforced even under the
 * dev {@code permitAll} chain — with no authenticated principal in dev, every
 * protected endpoint returns 403. This filter is the test scaffolding that
 * satisfies method security for E2E; it is <b>never</b> wired in plain {@code dev}
 * or {@code prod} (see {@link DevSecurityConfig}, which only adds it when the
 * {@code e2e} profile is present). It is instantiated directly (not a bean), so
 * it is never auto-registered as a servlet filter.
 */
public class E2eMockAuthFilter extends OncePerRequestFilter {

    /** Plain authorities checked via {@code hasAuthority('...')}. */
    private static final List<String> AUTHORITIES = List.of(
            "FINANCE_UPDATE", "FINANCE_VIEW",
            "notification_templates:update", "notification_templates:view",
            "reports:create_custom", "reports:export_csv", "reports:export_pdf",
            "reports:manage_access", "reports:view",
            "setup:create", "setup:update", "setup:view");

    /** Role names checked via {@code hasRole/hasAnyRole('...')} — granted as {@code ROLE_*}. */
    private static final List<String> ROLES = List.of(
            "CLAIMS_APPROVE", "CLAIMS_CREATE", "CLAIMS_UPDATE", "CLAIMS_VIEW",
            "CUSTOMER_CREATE", "CUSTOMER_UPDATE", "CUSTOMER_VIEW", "DATA_PROTECTION",
            "FINANCE_APPROVE", "FINANCE_APPROVE_PPA", "FINANCE_CREATE", "FINANCE_REOPEN_PERIOD",
            "FINANCE_UPDATE", "FINANCE_VIEW", "PLATFORM_ADMIN",
            "QUOTATION_APPROVE", "QUOTATION_CREATE", "QUOTATION_UPDATE", "QUOTATION_VIEW",
            "REINSURANCE_APPROVE", "REINSURANCE_CREATE", "REINSURANCE_UPDATE", "REINSURANCE_VIEW",
            "SETUP_CREATE", "SETUP_DELETE", "SETUP_UPDATE", "SETUP_VIEW", "SUPER_ADMIN",
            "UNDERWRITING_APPROVE", "UNDERWRITING_CREATE", "UNDERWRITING_UPDATE", "UNDERWRITING_VIEW",
            "AUDIT_VIEW");

    private static final List<GrantedAuthority> GRANTED =
            Stream.concat(AUTHORITIES.stream(), ROLES.stream().map(r -> "ROLE_" + r))
                    .distinct()
                    .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                    .toList();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("e2e-user", "n/a", GRANTED));
        }
        chain.doFilter(request, response);
    }
}
