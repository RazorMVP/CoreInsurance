package com.nubeero.cia.api.reinsurance;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.reinsurance.RiFacInward;
import com.nubeero.cia.reinsurance.RiFacInwardRepository;
import com.nubeero.cia.reinsurance.RiFacInwardStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real schema-drift guard for {@link RiFacInward} against the <em>actual</em> V75
 * {@code ri_fac_inwards} table, built by the real Flyway migration chain (V1..V75)
 * against a PostgreSQL container with {@code ddl-auto=none}.
 *
 * <p>This is what {@code cia-reinsurance}'s {@code RiFacInwardReferenceIT} deliberately
 * cannot be: that test builds its schema with {@code ddl-auto=create-drop} <em>from the
 * entity</em> (tautological — it cannot catch a {@code @Column} name/type mismatch) and
 * never persists a {@code RiFacInward} row. Here the schema comes from V75, Hibernate never
 * touches DDL, and a fully-populated {@code RiFacInward} is saved through
 * {@link RiFacInwardRepository} and read back — so any divergence between an
 * {@code @Column(name=...)}/precision/scale/length on the entity and the V75 DDL surfaces as
 * a persistence failure right here.
 *
 * <p>Pattern mirrors {@code QuoteRiskGrossPremiumColumnIT} / {@code PolicyRmConstraintIT}:
 * {@code @DataJpaTest} bootstrapped off {@code CiaApplication}'s config (package
 * {@code com.nubeero.cia}, so the default entity + repository scan covers the cia-reinsurance
 * aggregate) + {@code @AutoConfigureTestDatabase(NONE)} + Testcontainers + {@code spring.flyway.target}
 * pinned to 75. {@code @Import(CiaCommonAutoConfiguration.class)} enables JPA auditing so
 * {@code created_at}/{@code updated_at} populate (they are NOT NULL in V75).
 *
 * @since Inward FAC (v1) Task 2 — entity ↔ V75 schema validation
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CiaCommonAutoConfiguration.class)
class RiFacInwardSchemaIT {

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
        // Real Flyway chain builds the schema; V75 creates ri_fac_inwards.
        registry.add("spring.flyway.target", () -> "75");
        // JPA talks to the default public schema where Flyway ran.
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired
    RiFacInwardRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    @Test
    void fullyPopulatedInwardRoundTripsAgainstV75Table() {
        UUID cedingId = UUID.randomUUID();
        UUID classId = UUID.randomUUID();

        // Non-default values on every defaulted column (status / currencyCode /
        // commissionRate / commissionAmount) so a wrong @Column mapping can't be
        // masked by the entity/DDL defaults happening to agree.
        RiFacInward inward = RiFacInward.builder()
            .facInwardReference("FAC-IN-2026-000042")
            .cedingCompanyId(cedingId)
            .cedingCompanyName("Leadway Assurance")
            .classOfBusinessId(classId)
            .classOfBusinessName("Fire & Special Perils")
            .riskDescription("Warehouse block C, Apapa")
            .sumInsured(new BigDecimal("250000000.00"))
            .ourSharePct(new BigDecimal("35.0000"))
            .acceptedSumInsured(new BigDecimal("87500000.00"))
            .premiumRate(new BigDecimal("1.250000"))
            .grossPremium(new BigDecimal("1093750.00"))
            .commissionRate(new BigDecimal("12.5000"))
            .commissionAmount(new BigDecimal("136718.75"))
            .netPremium(new BigDecimal("957031.25"))
            .currencyCode("USD")
            .coverFrom(LocalDate.of(2026, 1, 1))
            .coverTo(LocalDate.of(2026, 12, 31))
            .status(RiFacInwardStatus.RENEWED)
            .guarantyDocumentPath("ri/fac-inward/2026/FAC-IN-2026-000042.pdf")
            .build();

        RiFacInward saved = repository.saveAndFlush(inward);
        UUID id = saved.getId();
        assertThat(id).isNotNull();
        assertThat(saved.getCreatedAt()).as("JPA auditing populated created_at").isNotNull();

        // Detach so the read-back is a real SELECT against ri_fac_inwards, not a
        // first-level-cache hit.
        em.clear();

        RiFacInward reloaded = repository.findByIdAndDeletedAtIsNull(id).orElseThrow();
        assertThat(reloaded.getFacInwardReference()).isEqualTo("FAC-IN-2026-000042");
        assertThat(reloaded.getCedingCompanyId()).isEqualTo(cedingId);
        assertThat(reloaded.getCedingCompanyName()).isEqualTo("Leadway Assurance");
        assertThat(reloaded.getClassOfBusinessId()).isEqualTo(classId);
        assertThat(reloaded.getClassOfBusinessName()).isEqualTo("Fire & Special Perils");
        assertThat(reloaded.getRiskDescription()).isEqualTo("Warehouse block C, Apapa");
        assertThat(reloaded.getSumInsured()).isEqualByComparingTo("250000000.00");
        assertThat(reloaded.getOurSharePct()).isEqualByComparingTo("35.0000");
        assertThat(reloaded.getAcceptedSumInsured()).isEqualByComparingTo("87500000.00");
        assertThat(reloaded.getPremiumRate()).isEqualByComparingTo("1.250000");
        assertThat(reloaded.getGrossPremium()).isEqualByComparingTo("1093750.00");
        assertThat(reloaded.getCommissionRate()).isEqualByComparingTo("12.5000");
        assertThat(reloaded.getCommissionAmount()).isEqualByComparingTo("136718.75");
        assertThat(reloaded.getNetPremium()).isEqualByComparingTo("957031.25");
        assertThat(reloaded.getCurrencyCode()).isEqualTo("USD");
        assertThat(reloaded.getCoverFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(reloaded.getCoverTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(reloaded.getStatus()).isEqualTo(RiFacInwardStatus.RENEWED);
        assertThat(reloaded.getGuarantyDocumentPath())
            .isEqualTo("ri/fac-inward/2026/FAC-IN-2026-000042.pdf");

        // Booking-date anchor resolves from the persisted created_at (FIX 1),
        // NOT from cover_from.
        assertThat(reloaded.getLockDate())
            .isEqualTo(reloaded.getCreatedAt().atOffset(ZoneOffset.UTC).toLocalDate())
            .isNotEqualTo(reloaded.getCoverFrom());

        // The status enum persisted as its STRING name into the real column.
        String statusCol = jdbc.queryForObject(
            "SELECT status FROM ri_fac_inwards WHERE id = ?", String.class, id);
        assertThat(statusCol).isEqualTo("RENEWED");
    }
}
