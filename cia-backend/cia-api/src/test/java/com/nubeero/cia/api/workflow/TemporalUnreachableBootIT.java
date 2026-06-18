package com.nubeero.cia.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.storage.DocumentStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Decisive startup-resilience proof for the {@code temporal-eager-boot-dial} concern: the full
 * cia-api {@link ApplicationContext} must boot to completion even when Temporal is unreachable.
 *
 * <p>Unlike every other full-boot IT (which {@code @MockBean}s the three Temporal beans so no gRPC
 * dial happens), this one deliberately uses the <b>real</b> {@code TemporalConfig} beans and points
 * {@code cia.temporal.host} at {@code localhost:1} (nothing listening). If any Temporal connect-point
 * were a fatal eager dial during context refresh, this test would error on context load. Reaching the
 * assertion at all is the proof that startup tolerates a down Temporal.
 *
 * <p>Mirrors the {@code FinanceWebItSupport} harness (singleton Postgres, the same boot properties,
 * the same storage + jwt mocks) minus the Temporal mocks — keeping the only meaningful delta the
 * unreachable Temporal host.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "bucket4j.enabled=false",
        "cia.keycloak.admin.enabled=false",
        // Point Temporal at an unreachable target — localhost:1 refuses fast (no connect timeout).
        "cia.temporal.host=localhost:1"
    }
)
class TemporalUnreachableBootIT {

    // Real WorkflowServiceStubs / WorkflowClient / WorkerFactory — intentionally NOT mocked.
    @MockBean JwtDecoder jwtDecoder;                          // mirror the known-good harness
    @MockBean DocumentStorageService documentStorageService; // no "local" storage impl exists

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciabacktest")
            .withUsername("ciabacktest")
            .withPassword("ciabacktest");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.target", () -> "66");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired ApplicationContext context;

    @Test
    void contextBootsWhenTemporalUnreachable() {
        assertThat(context).isNotNull();
    }
}
