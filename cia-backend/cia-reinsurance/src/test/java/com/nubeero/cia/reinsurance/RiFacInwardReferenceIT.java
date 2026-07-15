package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reference-sequence IT for {@link RiNumberService#nextInwardFacReference()} against a real
 * PostgreSQL container (Docker via Testcontainers). Scope: <strong>only</strong> the counter /
 * reference-minting round-trip (year row is created, sequence increments, format is
 * {@code FAC-IN-YYYY-NNNNNN}).
 *
 * <p><strong>This is NOT a schema-drift guard.</strong> The schema here is built by Hibernate
 * {@code ddl-auto=create-drop} <em>from the entities themselves</em>, so it is tautological —
 * it cannot catch a {@code @Column} name/type mismatch against the real V75 {@code ri_fac_inwards}
 * migration, and this test never persists a {@link RiFacInward} row (only the counter). Entity ↔
 * V75 column validation lives in {@code cia-api}'s {@code RiFacInwardSchemaIT}, which runs the
 * <em>real</em> Flyway V1..V75 migrations ({@code ddl-auto=none}) and round-trips a fully
 * populated {@code RiFacInward}. Do not treat this module's {@code create-drop} harness as
 * "schema-verified" precedent.
 *
 * <p>Why {@code create-drop} here: {@code cia-reinsurance} has no {@code @SpringBootApplication}
 * or Flyway migrations of its own (both live in {@code cia-api}, which this module cannot depend
 * on — the reactor dependency runs the other way). {@link ReinsuranceTestApplication}, a minimal
 * {@code @SpringBootConfiguration} fixture colocated in this package, gives {@code @DataJpaTest}
 * something to bootstrap from; {@code create-drop} is pinned for this isolated test context only
 * (no Flyway substrate exists at this module's scope to migrate against). This does not relax the
 * project's {@code ddl-auto: none} runtime convention, which governs {@code cia-api}'s
 * Flyway-owned schema only.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CiaCommonAutoConfiguration.class, RiNumberService.class})
class RiFacInwardReferenceIT {

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired
    RiNumberService numberService;

    @Test
    void referencesAreSequentialWithinYear() {
        String a = numberService.nextInwardFacReference();
        String b = numberService.nextInwardFacReference();

        assertThat(a).matches("FAC-IN-\\d{4}-\\d{6}");
        assertThat(b).matches("FAC-IN-\\d{4}-\\d{6}");

        int seqA = Integer.parseInt(a.substring(a.length() - 6));
        int seqB = Integer.parseInt(b.substring(b.length() - 6));
        assertThat(seqB).isEqualTo(seqA + 1);
    }
}
