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
 * {@code spring-security-test} is on the classpath, so subclasses authenticate
 * via {@code @WithMockUser} at class or method level. The
 * {@code TestSecurityContextHolderPostProcessor} hooks into MockMvc's request
 * lifecycle, pre-populating {@code SecurityContextHolder} before the filter
 * chain runs — the only reliable approach in a {@code @SpringBootTest} setup
 * (direct mutation in the test body is wiped by
 * {@code SecurityContextPersistenceFilter} before {@code @PreAuthorize} fires).
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
 *       never exercised because {@code @WithMockUser} pre-populates
 *       {@code SecurityContextHolder} ahead of the filter chain.</li>
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
 * A <em>singleton</em> Postgres 16 Testcontainers instance migrated to V59.
 * The container is started exactly once for the JVM (in a static initializer
 * block, no {@code @Container} / {@code @Testcontainers}) and is cleaned up by
 * the JVM-level shutdown hook Testcontainers installs via Ryuk.
 *
 * <p>Why the singleton pattern instead of {@code @Container} (as
 * {@code FinanceItSupport} uses): {@code @SpringBootTest} contexts are cached
 * across test classes by Spring's {@code ContextCache}. The first IT extending
 * this base ({@code ReceiptListControllerIT}) caches a context whose
 * {@code DataSource} resolves to whatever URL Testcontainers exposed at that
 * moment. JUnit's {@code @Testcontainers} extension then stops the
 * {@code @Container static} field at the end of the test class, killing the
 * Postgres. When the next IT extending this base ({@code PaymentListControllerIT})
 * begins, Spring reuses the cached context — but its DataSource still points
 * at the now-stopped container, producing {@code Connection refused} on every
 * test. Promoting the container to JVM-singleton lifetime (no JUnit lifecycle
 * binding) keeps the URL stable across all reusing tests.
 *
 * @since Slice α — Task 7, ReceiptListController (singleton-container fix added in Task 8)
 */
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

    // ── Database — singleton, JVM-lifetime, see class Javadoc ──────────────

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciawebtest")
            .withUsername("ciawebtest")
            .withPassword("ciawebtest");

    static {
        // Start once for the JVM; Testcontainers' Ryuk handles teardown.
        // No @Container annotation = JUnit does NOT stop this between
        // test classes, so a Spring-cached @SpringBootTest context that
        // references POSTGRES.getJdbcUrl() stays valid for every IT that
        // reuses the cached context.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.target", () -> "63");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }
}
