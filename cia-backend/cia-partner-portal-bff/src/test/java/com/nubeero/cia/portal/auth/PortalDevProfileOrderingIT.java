package com.nubeero.cia.portal.auth;

import com.nubeero.cia.auth.CorsConfig;
import com.nubeero.cia.auth.DevSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fix round 1 — CRITICAL. Before the fix, {@code DevSecurityConfig} (dev-profile-only,
 * {@code @Order(1)}, {@code securityMatcher("/**")}, {@code anyRequest().permitAll()}) sorted
 * ahead of {@code PortalSecurityConfig} (previously {@code @Order(2)}) and — because Spring
 * Security tries chains in ascending {@code @Order} and stops at the first matcher hit — silently
 * won every {@code /portal/**} request under {@code SPRING_PROFILES_ACTIVE=dev}, the standard
 * local workflow per {@code CLAUDE.md}. {@link PortalSessionFilter} never ran (no session check,
 * no CSRF, no {@code public}-tenant pin), and {@code @AuthenticationPrincipal PortalPrincipal} in
 * a real controller would resolve to {@code null} and NPE.
 *
 * <p>{@code cia-partner-portal-bff}'s own module-scope IT ({@link PortalAuthFlowIT}) cannot prove
 * this by itself — {@code DevSecurityConfig} lives in {@code cia-auth} and only actually competes
 * with {@code PortalSecurityConfig} once both are present in the SAME application context (true in
 * the real {@code cia-api} assembly, which scans {@code com.nubeero.cia} broadly and always loads
 * both). So this test builds a minimal, dedicated context that {@code @Import}s both {@code
 * DevSecurityConfig} and its {@code CorsConfigurationSource} dependency ({@code CorsConfig}) from
 * {@code cia-auth} alongside the real {@code com.nubeero.cia.portal.auth} package (giving it the
 * real {@code PortalSecurityConfig} + {@code PortalSessionFilter}), activates the {@code dev}
 * profile, and asserts the request is rejected — proving {@code PortalSecurityConfig} answered it,
 * not {@code DevSecurityConfig}'s catch-all.
 *
 * <p>{@code PortalAuthController} (and its DB-backed dependency, {@code
 * PartnerPortalGrantRepository}) is deliberately excluded from this test's component scan — it's
 * unnecessary for what's being proven. With no valid session, {@code
 * PortalSecurityConfig}'s {@code anyRequest().authenticated()} rejects the request with 401 at the
 * filter-chain layer, before the {@code DispatcherServlet} would even attempt to resolve a
 * controller mapping — whether or not one exists downstream is irrelevant to this assertion.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = PortalDevProfileOrderingIT.DevProfileOrderingTestApplication.class,
        properties = "bucket4j.enabled=false"
)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PortalDevProfileOrderingIT {

    @Autowired
    MockMvc mvc;

    @Test
    void devProfile_portalChain_still401sUnauthenticatedMe_notDevCatchAllPermitAll() throws Exception {
        // Before the fix: DevSecurityConfig's anyRequest().permitAll() would have answered this
        // with 200 (no controller mapped in this narrow context → would actually be 404, since
        // DevSecurityConfig itself does no authorization — either way, NOT 401). After the fix,
        // PortalSecurityConfig's anyRequest().authenticated() answers first and rejects with 401.
        mvc.perform(get("/portal/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * No JPA/DataSource autoconfiguration — deliberately excluded, since {@code
     * PortalAuthController} (the only bean in this package that would need a repository) is
     * excluded from the scan too.
     *
     * <p><b>Named {@code ...TestApplication} deliberately</b> — {@link PortalAuthTestApplication}'s
     * own {@code @ComponentScan} of this same package excludes anything matching
     * {@code .*TestApplication$} specifically so a sibling {@code @EnableAutoConfiguration(exclude
     * = ...)} fixture like this one can't be swept in and silently disable JPA/DataSource
     * autoconfiguration for its context too (Spring merges every {@code @EnableAutoConfiguration}
     * found anywhere in a scanned context into one shared exclusion set — see {@code
     * RedisPortalSessionStoreTestApplication}'s identical javadoc note for the first time this bit
     * this module). A nested class here named anything else slips past that filter.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @ComponentScan(
            basePackages = {"com.nubeero.cia.portal.auth", "com.nubeero.cia.portal.session"},
            excludeFilters = {
                    @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*TestApplication$"),
                    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = PortalAuthController.class)
            }
    )
    @Import({DevSecurityConfig.class, CorsConfig.class})
    static class DevProfileOrderingTestApplication {
    }
}
