package com.nubeero.cia.partner.config;

import com.nubeero.cia.auth.JwtAuthConverter;
import com.nubeero.cia.auth.TenantContextFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class PartnerSecurityConfig {

    private final JwtAuthConverter jwtAuthConverter;
    private final TenantContextFilter tenantContextFilter;
    private final PartnerScopeFilter partnerScopeFilter;

    @Bean
    @Order(1)
    public SecurityFilterChain partnerFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/partner/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/partner/docs/**", "/partner/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                )
                .addFilterBefore(tenantContextFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(partnerScopeFilter, TenantContextFilter.class)
                .build();
    }
}
