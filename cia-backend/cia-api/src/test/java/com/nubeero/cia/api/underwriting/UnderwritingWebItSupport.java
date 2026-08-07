package com.nubeero.cia.api.underwriting;

import com.nubeero.cia.storage.DocumentStorageService;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for underwriting-module <em>controller</em> integration tests
 * (Policy / Quote / Endorsement list endpoints — S5.2 server pagination).
 *
 * <p>Mirrors {@code FinanceWebItSupport}: uses {@code @SpringBootTest} (full
 * application context + MockMvc) rather than {@code @DataJpaTest} so the Spring
 * Security filter chain and {@code @PreAuthorize} method security are active —
 * the underwriting controllers gate on {@code hasRole('UNDERWRITING_VIEW')} /
 * {@code hasRole('QUOTATION_VIEW')}, which only fires with the security chain
 * engaged.
 *
 * <h2>Auth in tests</h2>
 * {@code spring-security-test} is on the classpath, so subclasses authenticate
 * via {@code @WithMockUser} at class or method level. Because the controllers
 * use {@code hasRole(...)}, the mock authorities must carry the {@code ROLE_}
 * prefix (e.g. {@code ROLE_UNDERWRITING_VIEW}).
 *
 * <h2>External-service isolation</h2>
 * {@code @MockBean} replaces each external bean <em>by type</em> before any
 * production factory method runs — the verified set from
 * {@code FinanceWebItSupport} that keeps Temporal, MinIO, and Keycloak
 * un-contacted at startup:
 * <ul>
 *   <li>{@link JwtDecoder} — replaces {@code SecurityConfig.jwtDecoder()};
 *       never exercised because {@code @WithMockUser} pre-populates the
 *       security context ahead of the filter chain.</li>
 *   <li>{@link WorkflowServiceStubs} / {@link WorkflowClient} /
 *       {@link WorkerFactory} — replaces {@code TemporalConfig} beans so no
 *       gRPC dial to {@code localhost:7233} occurs.</li>
 *   <li>{@link DocumentStorageService} — satisfies document-generation wiring;
 *       there is no {@code local} storage impl so a no-op mock is required.</li>
 * </ul>
 *
 * <h2>Database</h2>
 * A JVM-singleton Postgres 16 Testcontainers instance migrated to V74 (matches
 * the {@code FinanceWebItSupport} contract — V74 adds {@code
 * policies.selected_clauses} + V73 {@code quotes.selected_clauses}, which the
 * Policy/Quote entities always map). Started once in a static initializer (no
 * {@code @Container}) so a Spring-cached {@code @SpringBootTest} context stays
 * valid across every IT that reuses it; Ryuk handles teardown.
 *
 * @since S5.2 — server pagination for the underwriting list endpoints
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        // Disable bucket4j's Redis-backed rate-limit filter — no Redis in IT.
        "bucket4j.enabled=false",
        // Disable Keycloak admin client wiring (default is false; explicit for clarity).
        "cia.keycloak.admin.enabled=false",
        // Satisfy SecurityConfig's issuer-uri @Value so the context can start.
        // The real JwtDecoder bean is replaced by @MockBean below, so this URI
        // is never contacted.
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test.invalid/realms/cia"
    }
)
@AutoConfigureMockMvc
public abstract class UnderwritingWebItSupport {

    // ── External-service mocks ─────────────────────────────────────────────

    /** Prevents SecurityConfig.jwtDecoder() from calling the OIDC discovery endpoint. */
    @MockBean JwtDecoder jwtDecoder;

    /** Prevents TemporalConfig from dialling localhost:7233. */
    @MockBean WorkflowServiceStubs workflowServiceStubs;
    @MockBean WorkflowClient workflowClient;
    @MockBean WorkerFactory workerFactory;

    /** Satisfies document-generation wiring; no "local" storage impl exists. */
    @MockBean DocumentStorageService documentStorageService;

    // ── Database — singleton, JVM-lifetime, see class Javadoc ──────────────

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciauwtest")
            .withUsername("ciauwtest")
            .withPassword("ciauwtest");

    static {
        // Start once for the JVM; Testcontainers' Ryuk handles teardown. No
        // @Container annotation = JUnit does NOT stop this between test classes,
        // so a Spring-cached @SpringBootTest context that references
        // POSTGRES.getJdbcUrl() stays valid for every IT that reuses it.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // V74 adds policies.selected_clauses (+ V73 quotes.selected_clauses),
        // which the Policy/Quote entities map unconditionally.
        registry.add("spring.flyway.target", () -> "74");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }
}
