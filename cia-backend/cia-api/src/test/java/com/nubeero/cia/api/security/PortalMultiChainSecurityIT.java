package com.nubeero.cia.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Task 11 fold-in 2 (carried from Task 5) — boots the FULL {@code cia-api} application context
 * (not a narrow module-scope fixture, unlike {@code PortalDevProfileOrderingIT}'s dedicated
 * two-bean fixture) and proves the real, fully-assembled set of {@code SecurityFilterChain} beans
 * coexist correctly.
 *
 * <p><b>All four chains are genuinely active here</b> — {@code application.yml}'s
 * {@code spring.profiles.active: ${SPRING_PROFILES_ACTIVE:dev}} defaults to {@code dev} whenever
 * the env var is unset, which is the case for both local {@code mvn verify} and this repo's CI (no
 * workflow sets {@code SPRING_PROFILES_ACTIVE} ahead of the backend build), so {@code
 * DevSecurityConfig} (its own {@code @Profile("dev")} gate satisfied) is on the classpath AND
 * registered as a bean alongside the other three:
 *
 * <ul>
 *   <li>{@code SecurityConfig} (cia-auth, {@code /api/**}, JWT resource server) — no explicit
 *       {@code @Order} = {@code Ordered.LOWEST_PRECEDENCE}, so it never actually answers a request
 *       under {@code dev} (every path it would match is also matched by a lower-order chain below)</li>
 *   <li>{@code DevSecurityConfig} (cia-auth, {@code /**}, {@code @Profile("dev")},
 *       {@code @Order(1)}) — {@code anyRequest().permitAll()} at the filter-chain level; still
 *       subject to {@code @EnableMethodSecurity}'s profile-unconditional {@code @PreAuthorize}</li>
 *   <li>{@code PartnerSecurityConfig} (cia-partner-api, {@code /partner/**}, {@code @Order(1)},
 *       deliberately CORS-free) — ties {@code DevSecurityConfig} on {@code @Order} but its more
 *       specific matcher wins for {@code /partner/**} (existing, tested behaviour — see
 *       {@code PortalSecurityConfig}'s own class javadoc)</li>
 *   <li>{@code PortalSecurityConfig} (cia-partner-portal-bff, {@code /portal/**}, {@code @Order(0)},
 *       opaque-cookie session, {@code /portal/**} CORS) — sorts strictly ahead of {@code
 *       DevSecurityConfig} by design (see its class javadoc); {@code PortalDevProfileOrderingIT}
 *       is the dedicated, isolated proof of that ordering fact alone</li>
 * </ul>
 *
 * <p>The three status-code tests below each exercise a <em>different</em> rejection mechanism
 * (portal: chain-level {@code anyRequest().authenticated()} genuinely excludes the anonymous
 * principal → 401 before any controller runs; internal: {@code DevSecurityConfig} lets the
 * anonymous principal through at chain level, {@code @PreAuthorize("hasRole(...)")} denies it at
 * the controller → 403; partner: {@code PartnerScopeFilter} runs ahead of the OAuth2 filters in
 * {@code PartnerSecurityConfig}'s chain and rejects a null {@code Authentication} directly → 403,
 * never reaching {@code AuthorizationFilter} or the controller) — proving each of the three
 * non-default chains keeps its own distinct, pre-existing behaviour with all four chains present
 * together, not just that /portal/** newly works in isolation.
 *
 * <p>The context booting at all is itself the proof that {@code corsConfigurationSource}
 * (cia-auth's {@code CorsConfig}, wired into {@code SecurityConfig} + {@code DevSecurityConfig})
 * and {@code portalCorsConfigurationSource} ({@code PortalSecurityConfig}) — two beans of the same
 * {@link CorsConfigurationSource} type — coexist without an ambiguous-bean autowiring failure: each
 * chain's {@code HttpSecurity} bean-method parameter is named to match its own bean exactly
 * (Spring's by-name tiebreak for multiple same-type candidates), so autowiring resolves
 * deterministically rather than by accident.
 */
class PortalMultiChainSecurityIT extends FinanceWebItSupport {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext applicationContext;

    @Test
    void bothCorsConfigurationSourceBeansCoexist_noAmbiguity() {
        // The context is already up by the time this test runs (proof #1 — no ambiguous-bean
        // BeanCreationException at startup, despite two CorsConfigurationSource beans). This
        // asserts the two distinct beans explicitly.
        assertThat(applicationContext.getBean("corsConfigurationSource", CorsConfigurationSource.class))
                .isNotNull();
        assertThat(applicationContext.getBean("portalCorsConfigurationSource", CorsConfigurationSource.class))
                .isNotNull();
    }

    @Test
    void portalAuthMe_withoutSession_401sViaPortalChain() throws Exception {
        // PortalSecurityConfig's custom authenticationEntryPoint answers this, not
        // DevSecurityConfig's permitAll catch-all — proves the portal chain (@Order 0) actually
        // claims /portal/** ahead of DevSecurityConfig (@Order 1) in the real, fully-assembled
        // context (not just the narrow two-bean fixture PortalDevProfileOrderingIT builds).
        mvc.perform(get("/portal/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalApi_withoutJwt_403sViaExistingAuth() throws Exception {
        // DevSecurityConfig's anyRequest().permitAll() answers /api/** at the chain level (dev
        // profile is active — see class javadoc), so the anonymous principal reaches
        // CustomerController; @EnableMethodSecurity's @PreAuthorize("hasRole('CUSTOMER_VIEW')")
        // then denies it. The point under test is that /api/** keeps this pre-existing behaviour
        // unchanged by the portal/partner chains' presence — 403 is that unchanged behaviour, not
        // an assertion about JWT auth specifically (contrast with /portal/auth/me above, whose own
        // chain never delegates to a permitAll catch-all).
        mvc.perform(get("/api/v1/customers"))
                .andExpect(status().isForbidden());
    }

    @Test
    void partnerApi_withoutJwt_403sViaExistingAuth() throws Exception {
        // A third, distinct mechanism, same unchanged-behaviour point: PartnerSecurityConfig's
        // matcher wins the @Order(1) tie against DevSecurityConfig for /partner/** (existing,
        // tested behaviour), and PartnerScopeFilter sits ahead of BearerTokenAuthenticationFilter
        // in that chain (addFilterAfter(tenantContextFilter, UsernamePasswordAuthenticationFilter
        // .class) anchors the whole custom-filter run before the OAuth2 filters) — with no
        // Authorization header it sees a null Authentication and writes its own 403
        // {"errors":[{"code":"INSUFFICIENT_SCOPE",...}]} directly, never reaching
        // AnonymousAuthenticationFilter, AuthorizationFilter, or the controller.
        mvc.perform(get("/partner/v1/products"))
                .andExpect(status().isForbidden());
    }
}
