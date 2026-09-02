package com.nubeero.cia.partner.config;

import com.nubeero.cia.auth.TenantContextFilter;
import com.nubeero.cia.auth.TenantIssuerJwtAuthenticationManagerResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@RequiredArgsConstructor
public class PartnerSecurityConfig {

    private final TenantContextFilter tenantContextFilter;
    private final PartnerScopeFilter partnerScopeFilter;
    private final PartnerRateLimitFilter partnerRateLimitFilter;
    private final PartnerRequestMetricsFilter partnerRequestMetricsFilter;
    // Realm-per-tenant resolver (S141) — replaces the removed shared JwtDecoder
    // bean. Partner tokens (OAuth2 client-credentials) are validated against
    // their own realm's JWKS, same as the internal chain.
    private final TenantIssuerJwtAuthenticationManagerResolver authenticationManagerResolver;

    @Bean
    @Order(1)
    public SecurityFilterChain partnerFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/partner/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                new AntPathRequestMatcher("/partner/docs/**"),
                                new AntPathRequestMatcher("/partner/swagger-ui/**"),
                                new AntPathRequestMatcher("/partner/v3/api-docs/**")
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(authenticationManagerResolver)
                )
                .addFilterBefore(tenantContextFilter, UsernamePasswordAuthenticationFilter.class)
                // Metrics wraps EVERYTHING downstream (scope check, rate limit, the controller)
                // so it observes the true final response status — see the filter's own javadoc.
                .addFilterAfter(partnerRequestMetricsFilter, TenantContextFilter.class)
                .addFilterAfter(partnerScopeFilter, PartnerRequestMetricsFilter.class)
                // Rate limit after scope: tenant schema + validated JWT (client_id)
                // are both available, so the per-client bucket can size itself from
                // the partner's rateLimitRpm.
                .addFilterAfter(partnerRateLimitFilter, PartnerScopeFilter.class)
                .build();
    }
}
