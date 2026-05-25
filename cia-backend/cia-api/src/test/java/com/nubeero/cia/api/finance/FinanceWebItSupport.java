package com.nubeero.cia.api.finance;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for Finance module <em>controller</em> integration tests.
 *
 * <p>Uses {@code @SpringBootTest} (full application context + MockMvc) rather
 * than {@code @DataJpaTest} so that the Spring Security filter chain and
 * {@code @PreAuthorize} method security are active — the same infrastructure
 * that {@link FinanceItSupport} ({@code @DataJpaTest}) intentionally skips
 * because its tests target the service/repository layer, not HTTP/auth.
 *
 * <h2>Auth in tests</h2>
 * There is no {@code spring-security-test} dependency on the classpath, so
 * {@code @WithMockUser} is not available. Subclasses set up authentication
 * by populating {@code SecurityContextHolder} directly in {@code @BeforeEach}
 * — the same pattern used by {@link ReceiptReverseAuditIT} and
 * {@link PaymentReverseAuditIT}.
 *
 * <h2>External-service isolation</h2>
 * {@code @MockBean} is used rather than a {@code @TestConfiguration @Primary}
 * approach because {@code SecurityConfig.jwtDecoder()} is an explicit factory
 * {@code @Bean} method that Spring instantiates before a secondary
 * {@code @Primary} override can take effect. {@code @MockBean} replaces the
 * bean <em>by type</em> in the application context before any factory method
 * runs, which is the only reliable way to prevent
 * {@code JwtDecoders.fromIssuerLocation()} from making an OIDC discovery HTTP
 * call at startup. The same mechanism is used for Temporal and storage beans.
 *
 * <ul>
 *   <li><b>{@link JwtDecoder}</b> — replaces {@code SecurityConfig.jwtDecoder()};
 *       never exercised because the IT sets {@code SecurityContextHolder}
 *       directly (pre-auth, bypasses the JWT filter).</li>
 *   <li><b>{@link WorkflowServiceStubs} / {@link WorkflowClient} /
 *       {@link WorkerFactory}</b> — replaces {@code TemporalConfig} beans so
 *       no gRPC dial to {@code localhost:7233} occurs.</li>
 *   <li><b>{@link DocumentStorageService}</b> — satisfies
 *       {@code DocumentTemplateService}; there is no {@code local} impl so a
 *       no-op mock is required even with {@code cia.storage.type=local}.</li>
 *   <li><b>Bucket4j / Redis</b> — disabled via {@code bucket4j.enabled=false}.</li>
 *   <li><b>Keycloak admin client</b> — gated on
 *       {@code cia.keycloak.admin.enabled=false} (the default).</li>
 * </ul>
 *
 * <h2>Database</h2>
 * A shared Postgres 16 Testcontainers instance migrated to V49, identical to
 * {@link FinanceItSupport}.
 *
 * @since Slice α — Task 7, ReceiptListController
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        // Disable bucket4j's Redis-backed rate-limit filter — no Redis in IT.
        "bucket4j.enabled=false",
        // Disable Keycloak admin client wiring (default is false; explicit for clarity).
        "cia.keycloak.admin.enabled=false",
        // Satisfy SecurityConfig's @Value("${spring.security.oauth2...issuer-uri}")
        // field so the context can start. The real JwtDecoder bean is replaced by
        // @MockBean below, so this URI is never contacted.
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test.invalid/realms/cia"
    }
)
@AutoConfigureMockMvc
public abstract class FinanceWebItSupport {

    // ── External-service mocks ─────────────────────────────────────────────
    // @MockBean replaces the bean by type before any production factory method
    // fires — the only approach that reliably prevents startup network calls.

    /** Prevents SecurityConfig.jwtDecoder() from calling the OIDC discovery endpoint. */
    @MockBean JwtDecoder jwtDecoder;

    /** Prevents TemporalConfig from dialling localhost:7233. */
    @MockBean WorkflowServiceStubs workflowServiceStubs;
    @MockBean WorkflowClient workflowClient;
    @MockBean WorkerFactory workerFactory;

    /**
     * Satisfies DocumentTemplateService (cia-documents). No "local" impl exists
     * in the storage module — all impls are conditional on minio/s3/gcs/azure.
     */
    @MockBean DocumentStorageService documentStorageService;

    // ── Database ───────────────────────────────────────────────────────────

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciawebtest")
            .withUsername("ciawebtest")
            .withPassword("ciawebtest");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.target", () -> "49");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }
}
