package com.nubeero.cia.portal.auth;

import com.nubeero.cia.auth.PartnerPortalRealmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * A <strong>second</strong>, independent {@link SecurityFilterChain} matched only to
 * {@code /portal/**} — deliberately separate from {@code cia-auth}'s prod {@code SecurityConfig}
 * (JWT resource server, {@code /api/**} + {@code /partner/**}) and dev-only {@code
 * DevSecurityConfig}. Multiple {@code SecurityFilterChain} beans coexist safely in one
 * application context as long as each carries a distinct {@code securityMatcher} and Spring
 * Security tries them in {@code @Order}.
 *
 * <p><b>{@link #PORTAL_CHAIN_ORDER} = 0 is load-bearing, not cosmetic.</b> {@code
 * DevSecurityConfig} (active only under the {@code dev} profile — the standard local workflow per
 * {@code CLAUDE.md}) is {@code @Order(1)} with {@code securityMatcher("/**")}, i.e. it matches
 * literally every path including {@code /portal/**}. Spring Security tries chains in ascending
 * {@code @Order} and stops at the first one whose {@code securityMatcher} matches — so unless this
 * chain sorts strictly <em>before</em> {@code DevSecurityConfig}, every {@code /portal/**} request
 * under {@code dev} would hit {@code DevSecurityConfig}'s {@code anyRequest().permitAll()} instead
 * of this chain: {@link PortalSessionFilter} would never run (no session check, no CSRF, no
 * {@code public}-tenant pin), and {@code @AuthenticationPrincipal PortalPrincipal} in
 * {@code PortalAuthController#me} would resolve to {@code null} and NPE. {@code 0 < 1} fixes that.
 * {@code SecurityConfig}'s implicit last-resort catch-all (no explicit {@code @Order} =
 * {@code Ordered.LOWEST_PRECEDENCE}) and {@code PartnerSecurityConfig}'s {@code @Order(1)}
 * {@code securityMatcher("/partner/**")} both carry matchers that never overlap
 * {@code /portal/**}, so their relative order versus this chain doesn't matter for correctness —
 * only {@code DevSecurityConfig}'s catch-all does, because it's the only sibling chain whose
 * matcher is broad enough to also claim this chain's paths.
 *
 * <p>Session handling is entirely custom: no JWT, no {@code HttpSession} — {@link
 * PortalSessionFilter} resolves the opaque {@code cia_portal_session} cookie into a {@link
 * PortalPrincipal} authentication. {@code /portal/auth/login} and {@code /portal/auth/callback}
 * are the only permitted paths (they establish the session); everything else under
 * {@code /portal/**} requires one.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class PortalSecurityConfig {

    /**
     * Must sort strictly before {@code DevSecurityConfig}'s {@code @Order(1)} — see class javadoc.
     */
    static final int PORTAL_CHAIN_ORDER = 0;

    private final PortalSessionFilter portalSessionFilter;

    @Bean
    public CorsConfigurationSource portalCorsConfigurationSource(PartnerPortalRealmProperties portalProperties) {
        CorsConfiguration config = new CorsConfiguration();
        // Sourced from cia.partner-portal.allowed-origins (CSV-bindable, default the local
        // partner Vite origin) — distinct from appUrl, which is only the post-login/logout
        // redirect target. allowCredentials(true) (the session cookie) forbids the "*"
        // wildcard, so origins are always enumerated exactly, mirroring CorsConfig (cia-auth)'s
        // /api/** policy. Exposes nothing sensitive — no exposedHeaders() call.
        config.setAllowedOrigins(portalProperties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", PortalSessionFilter.CSRF_HEADER_NAME));
        // Cookies are the only credential the SPA ever carries — must be sent cross-origin.
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/portal/**", config);
        return source;
    }

    @Bean
    @Order(PORTAL_CHAIN_ORDER)
    public SecurityFilterChain portalSecurityFilterChain(
            HttpSecurity http, CorsConfigurationSource portalCorsConfigurationSource) throws Exception {
        return http
                .securityMatcher("/portal/**")
                .cors(cors -> cors.configurationSource(portalCorsConfigurationSource))
                // No Spring CSRF filter — the double-submit check runs manually in
                // PortalSessionFilter against the session-store csrfToken (not the
                // synchronizer-token pattern CsrfFilter implements).
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                new AntPathRequestMatcher("/portal/auth/login"),
                                new AntPathRequestMatcher("/portal/auth/callback")
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"errors\":[{\"code\":\"PORTAL_SESSION_REQUIRED\","
                                    + "\"message\":\"No valid portal session\"}]}");
                }))
                .addFilterBefore(portalSessionFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
