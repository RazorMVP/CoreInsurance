package com.nubeero.cia.api.partnerportal;

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
 * Base class for Partner Portal BFF <em>controller</em> integration tests
 * ({@link PartnerDeveloperControllerIT}).
 *
 * <p>Mirrors {@code FinanceWebItSupport} / {@code UnderwritingWebItSupport} (full
 * {@code @SpringBootTest} context + MockMvc, so {@code @PreAuthorize} method security is active),
 * with one deliberate difference: <b>no {@code spring.flyway.target} pin</b>. Those sibling base
 * classes pin an older target version to match whatever schema state their own module last
 * verified against; this module's table ({@code public.partner_portal_grant}, V80) is one of the
 * newest migrations in the reactor, so pinning to an old target would leave the table missing and
 * every repository call failing with "relation does not exist". Flyway's default target is
 * "latest", so omitting the property runs every migration up to and including V80.
 *
 * <h2>Auth in tests</h2>
 * Unlike {@code @WithMockUser}-based controller ITs, {@link PartnerDeveloperControllerIT} uses the
 * {@code jwt()} post-processor (see {@code PlatformTenantControllerIT} for the same pattern) —
 * {@code PartnerDeveloperService.invite} needs a real tenant resolved via
 * {@code TenantContext.getTenantId()}, which {@code TenantContextFilter} only populates for a
 * {@link org.springframework.security.oauth2.jwt.Jwt}-typed principal (an {@code iss} realm claim
 * is required). {@code @WithMockUser} authenticates with a plain {@code User} principal, so it
 * would leave {@code TenantContext} unset and the write would fail — {@code jwt()} is required
 * here, not a style choice.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "bucket4j.enabled=false",
        "cia.keycloak.admin.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test.invalid/realms/cia"
    }
)
@AutoConfigureMockMvc
public abstract class PartnerDeveloperWebItSupport {

    @MockBean JwtDecoder jwtDecoder;

    @MockBean WorkflowServiceStubs workflowServiceStubs;
    @MockBean WorkflowClient workflowClient;
    @MockBean WorkerFactory workerFactory;

    @MockBean DocumentStorageService documentStorageService;

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciapartnerportaltest")
            .withUsername("ciapartnerportaltest")
            .withPassword("ciapartnerportaltest");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }
}
