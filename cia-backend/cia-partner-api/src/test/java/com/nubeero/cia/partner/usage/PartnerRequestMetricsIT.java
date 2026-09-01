package com.nubeero.cia.partner.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.partner.config.PartnerRequestMetricsFilter;
import com.nubeero.cia.partner.usage.PartnerUsageRollupStore.DailyCounts;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * IT for Task 9 Step 1: {@link PartnerRequestMetricsFilter} — proves the filter records the RIGHT
 * per-(tenant, clientId) counters for a real HTTP round trip (2xx/4xx/5xx), scoped correctly by
 * tenant and by client, and skips paths outside {@code /partner/v1/**}.
 *
 * <h2>Why a bare MockMvc standalone setup, not a full {@code @SpringBootTest}</h2>
 * The filter's ENTIRE job is: read {@code client_id} off the already-authenticated JWT + read the
 * already-resolved {@link TenantContext}, then increment {@link PartnerUsageRollupStore}. None of
 * that needs a real Postgres, a real Keycloak, or the rest of {@code PartnerSecurityConfig}'s
 * chain (JWT signature validation, scope enforcement, rate limiting are Task 5/8's own concerns,
 * already covered by their own tests) — so this IT drives the filter directly against a trivial
 * stub controller via {@link MockMvcBuilders#standaloneSetup}, pre-populating
 * {@link SecurityContextHolder} and {@link TenantContext} exactly as the real filter chain would
 * have left them by the time this filter runs (it's registered directly after
 * {@code TenantContextFilter} — see the filter's own javadoc). No Docker/Testcontainers needed.
 */
class PartnerRequestMetricsIT {

    /** Trivial stub — returns whatever status the path asks for, so tests can drive 2xx/4xx/5xx at will. */
    @RestController
    static class EchoController {
        @GetMapping("/partner/v1/echo/{status}")
        ResponseEntity<String> echoPartner(@PathVariable int status) {
            return ResponseEntity.status(status).body("ok");
        }

        @GetMapping("/api/v1/echo/{status}")
        ResponseEntity<String> echoInternal(@PathVariable int status) {
            return ResponseEntity.status(status).body("ok");
        }
    }

    private InMemoryPartnerUsageRollupStore rollupStore;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        rollupStore = new InMemoryPartnerUsageRollupStore();
        PartnerRequestMetricsFilter filter = new PartnerRequestMetricsFilter(rollupStore);
        mvc = MockMvcBuilders.standaloneSetup(new EchoController())
                .addFilter(filter)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static void authenticateAs(String clientId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("client_id", clientId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void recordsTotalAndPerStatusClassCounters_forOneTenantAndClient() throws Exception {
        TenantContext.setTenantId("tenant_acme");
        authenticateAs("insurtech-app-acme");

        mvc.perform(get("/partner/v1/echo/200")).andExpect(status().isOk());
        mvc.perform(get("/partner/v1/echo/201")).andExpect(status().isCreated());
        mvc.perform(get("/partner/v1/echo/404")).andExpect(status().isNotFound());
        mvc.perform(get("/partner/v1/echo/429")).andExpect(status().isTooManyRequests());
        mvc.perform(get("/partner/v1/echo/500")).andExpect(status().isInternalServerError());
        mvc.perform(get("/partner/v1/echo/503")).andExpect(status().isServiceUnavailable());

        DailyCounts counts = rollupStore.snapshot("tenant_acme", "insurtech-app-acme", LocalDate.now());
        assertThat(counts.total()).isEqualTo(6);
        assertThat(counts.success()).isEqualTo(2);
        assertThat(counts.clientError()).isEqualTo(2);
        assertThat(counts.serverError()).isEqualTo(2);
    }

    @Test
    void scopesCountersByTenant_sameClientIdDifferentTenants_neverCrossContaminate() throws Exception {
        TenantContext.setTenantId("tenant_acme");
        authenticateAs("insurtech-app-shared");
        mvc.perform(get("/partner/v1/echo/200")).andExpect(status().isOk());

        TenantContext.setTenantId("tenant_leadway");
        // Same client_id, different tenant — Keycloak client_ids are only unique per realm.
        mvc.perform(get("/partner/v1/echo/200")).andExpect(status().isOk());
        mvc.perform(get("/partner/v1/echo/500")).andExpect(status().isInternalServerError());

        DailyCounts acme = rollupStore.snapshot("tenant_acme", "insurtech-app-shared", LocalDate.now());
        DailyCounts leadway = rollupStore.snapshot("tenant_leadway", "insurtech-app-shared", LocalDate.now());

        assertThat(acme.total()).isEqualTo(1);
        assertThat(acme.success()).isEqualTo(1);
        assertThat(leadway.total()).isEqualTo(2);
        assertThat(leadway.success()).isEqualTo(1);
        assertThat(leadway.serverError()).isEqualTo(1);
    }

    @Test
    void scopesCountersByClientId_withinSameTenant() throws Exception {
        TenantContext.setTenantId("tenant_acme");

        authenticateAs("insurtech-app-a");
        mvc.perform(get("/partner/v1/echo/200")).andExpect(status().isOk());
        mvc.perform(get("/partner/v1/echo/200")).andExpect(status().isOk());

        authenticateAs("insurtech-app-b");
        mvc.perform(get("/partner/v1/echo/404")).andExpect(status().isNotFound());

        assertThat(rollupStore.snapshot("tenant_acme", "insurtech-app-a", LocalDate.now()).total()).isEqualTo(2);
        assertThat(rollupStore.snapshot("tenant_acme", "insurtech-app-b", LocalDate.now()).total()).isEqualTo(1);
        assertThat(rollupStore.snapshot("tenant_acme", "insurtech-app-b", LocalDate.now()).clientError()).isEqualTo(1);
    }

    @Test
    void nonPartnerPath_isNeverCounted() throws Exception {
        TenantContext.setTenantId("tenant_acme");
        authenticateAs("insurtech-app-acme");

        mvc.perform(get("/api/v1/echo/200")).andExpect(status().isOk());

        DailyCounts counts = rollupStore.snapshot("tenant_acme", "insurtech-app-acme", LocalDate.now());
        assertThat(counts).isEqualTo(DailyCounts.ZERO);
        assertThat(rollupStore.keysForDate(LocalDate.now())).isEmpty();
    }

    @Test
    void noAuthentication_notCounted_butRequestStillSucceeds() throws Exception {
        TenantContext.setTenantId("tenant_acme");
        // Deliberately no authenticateAs() call — SecurityContext is empty.

        mvc.perform(get("/partner/v1/echo/200")).andExpect(status().isOk());

        assertThat(rollupStore.keysForDate(LocalDate.now())).isEmpty();
    }

    @Test
    void statusClassBoundaries_299and399AreSuccess_400And500AreTheirRespectiveErrorClasses() {
        assertThat(PartnerUsageRollupStore.StatusClass.fromHttpStatus(200))
                .isEqualTo(PartnerUsageRollupStore.StatusClass.SUCCESS);
        assertThat(PartnerUsageRollupStore.StatusClass.fromHttpStatus(HttpStatus.PERMANENT_REDIRECT.value()))
                .isEqualTo(PartnerUsageRollupStore.StatusClass.SUCCESS);
        assertThat(PartnerUsageRollupStore.StatusClass.fromHttpStatus(400))
                .isEqualTo(PartnerUsageRollupStore.StatusClass.CLIENT_ERROR);
        assertThat(PartnerUsageRollupStore.StatusClass.fromHttpStatus(499))
                .isEqualTo(PartnerUsageRollupStore.StatusClass.CLIENT_ERROR);
        assertThat(PartnerUsageRollupStore.StatusClass.fromHttpStatus(500))
                .isEqualTo(PartnerUsageRollupStore.StatusClass.SERVER_ERROR);
    }
}
