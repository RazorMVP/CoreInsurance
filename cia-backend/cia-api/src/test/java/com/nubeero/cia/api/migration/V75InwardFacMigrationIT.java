package com.nubeero.cia.api.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies V75__inward_fac.sql: the ri_fac_inwards + ri_fac_inward_counters tables exist
 * and the two new inward COA leaves (4330 income / 5240 expense) are seeded correctly,
 * parented under 4300 / 5200 respectively. Pure JDBC + Flyway + Testcontainers — no Spring
 * context. Mirrors V72ClausesMigrationTest's @Testcontainers/@BeforeAll shape (there is no
 * shared IT base class for migration-only tests in this repo) and
 * PlatformAuditLogMigrationIT's HikariDataSource + JdbcTemplate wiring.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V75InwardFacMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    private JdbcTemplate jdbc;

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("75")
                .load()
                .migrate();

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        jdbc = new JdbcTemplate(new HikariDataSource(cfg));
    }

    @Test
    @DisplayName("ri_fac_inwards and ri_fac_inward_counters tables exist")
    void tablesExist() {
        Integer inwards = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'ri_fac_inwards'",
                Integer.class);
        assertThat(inwards).isEqualTo(1);

        Integer counters = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'ri_fac_inward_counters'",
                Integer.class);
        assertThat(counters).isEqualTo(1);
    }

    @Test
    @DisplayName("the two inward COA rows (4330 income / 5240 expense) exist and are not soft-deleted")
    void coaRowsExist() {
        Integer coa = jdbc.queryForObject(
                "SELECT count(*) FROM chart_of_account WHERE code IN ('4330','5240') AND deleted_at IS NULL",
                Integer.class);
        assertThat(coa).isEqualTo(2);
    }

    @Test
    @DisplayName("4330 is INCOME parented under 4300; 5240 is EXPENSE parented under 5200")
    void coaRowsParentedCorrectly() {
        String incomeParentCode = jdbc.queryForObject(
                "SELECT p.code FROM chart_of_account c JOIN chart_of_account p ON p.id = c.parent_id "
                        + "WHERE c.code = '4330'",
                String.class);
        assertThat(incomeParentCode).isEqualTo("4300");

        String incomeAccountType = jdbc.queryForObject(
                "SELECT account_type FROM chart_of_account WHERE code = '4330'", String.class);
        assertThat(incomeAccountType).isEqualTo("INCOME");

        String expenseParentCode = jdbc.queryForObject(
                "SELECT p.code FROM chart_of_account c JOIN chart_of_account p ON p.id = c.parent_id "
                        + "WHERE c.code = '5240'",
                String.class);
        assertThat(expenseParentCode).isEqualTo("5200");

        String expenseAccountType = jdbc.queryForObject(
                "SELECT account_type FROM chart_of_account WHERE code = '5240'", String.class);
        assertThat(expenseAccountType).isEqualTo("EXPENSE");
    }

    @Test
    @DisplayName("ri_fac_inward_counters seed insert works (PK on year, default last_sequence)")
    void counterTableAcceptsInsert() {
        jdbc.update("INSERT INTO ri_fac_inward_counters (year) VALUES (2026)");
        Long lastSequence = jdbc.queryForObject(
                "SELECT last_sequence FROM ri_fac_inward_counters WHERE year = 2026", Long.class);
        assertThat(lastSequence).isEqualTo(0L);
    }
}
