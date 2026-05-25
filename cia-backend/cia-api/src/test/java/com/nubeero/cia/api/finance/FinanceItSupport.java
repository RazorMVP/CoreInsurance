package com.nubeero.cia.api.finance;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for Finance module integration tests.
 *
 * <p>Starts a shared Postgres 16 Testcontainers instance and wires its
 * datasource coordinates into the Spring test context via
 * {@link DynamicPropertySource}. All Flyway migrations run to the current
 * tip (V49) so subclasses have the full production schema available.
 *
 * <p>Multi-tenancy is disabled ({@code NONE}) because the IT schema is a
 * single-tenant in-process database — the same pattern used by every other
 * finance IT in this package (see {@code PeriodLockInterceptorIT},
 * {@code SubledgerPostingServiceIT}, etc.).
 *
 * @since Slice α — F7 Receipt/Payment visibility
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class FinanceItSupport {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.target", () -> "49");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }
}
