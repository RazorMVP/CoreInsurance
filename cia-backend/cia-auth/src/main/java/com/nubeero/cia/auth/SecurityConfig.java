package com.nubeero.cia.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // activates @PreAuthorize / @PostAuthorize on @RestController methods
@RequiredArgsConstructor
public class SecurityConfig {

    private final TenantContextFilter tenantContextFilter;
    private final TenantIssuerJwtAuthenticationManagerResolver authenticationManagerResolver;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                new AntPathRequestMatcher("/actuator/health"),
                                new AntPathRequestMatcher("/actuator/info"),
                                // Swagger UI + OpenAPI specs — the actual mount is
                                // under /partner/* (see application.yml). The
                                // /internal/* paths are friendly redirect aliases
                                // for the internal-api GroupedOpenApi.
                                new AntPathRequestMatcher("/partner/docs/**"),
                                new AntPathRequestMatcher("/partner/docs"),
                                new AntPathRequestMatcher("/partner/swagger-ui/**"),
                                new AntPathRequestMatcher("/partner/v3/api-docs/**"),
                                new AntPathRequestMatcher("/internal/docs"),
                                new AntPathRequestMatcher("/internal/v3/api-docs"),
                                new AntPathRequestMatcher("/webjars/**"),
                                new AntPathRequestMatcher("/api/v1/auth/login/failed")
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(authenticationManagerResolver)
                )
                .addFilterBefore(tenantContextFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
