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
 * Security tries them in {@code @Order} — this chain's {@link #PORTAL_CHAIN_ORDER} sits ahead of
 * {@code SecurityConfig}'s implicit last-resort catch-all (no explicit {@code @Order} =
 * {@code Ordered.LOWEST_PRECEDENCE}) so {@code /portal/**} is claimed here first, and it never
 * widens its matcher, so {@code /api/**} and {@code /partner/**} traffic is never touched by it.
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

    /** Runs ahead of {@code SecurityConfig}'s unordered (= lowest-precedence) catch-all chain. */
    static final int PORTAL_CHAIN_ORDER = 2;

    private final PortalSessionFilter portalSessionFilter;

    @Bean
    public CorsConfigurationSource portalCorsConfigurationSource(PartnerPortalRealmProperties portalProperties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(portalProperties.getAppUrl()));
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
