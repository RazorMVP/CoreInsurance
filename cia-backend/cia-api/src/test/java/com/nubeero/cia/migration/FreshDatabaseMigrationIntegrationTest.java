package com.nubeero.cia.migration;

import com.nubeero.cia.reports.domain.DataSource;
import com.nubeero.cia.reports.domain.ReportConfig;
import com.nubeero.cia.reports.domain.ReportConfigConverter;
import com.nubeero.cia.reports.domain.ReportDefinition;
import com.nubeero.cia.reports.service.ReportQueryBuilder;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FreshDatabaseMigrationIntegrationTest {

    private HikariDataSource adminDataSource;
    private HikariDataSource migrationDataSource;
    private JdbcTemplate adminJdbcTemplate;
    private JdbcTemplate migrationJdbcTemplate;
    private String databaseName;
    private boolean databaseCreated;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        databaseName = "cia_migration_it_" + suffix;

        adminDataSource = dataSource(baseUrl());
        adminJdbcTemplate = new JdbcTemplate(adminDataSource);
        assumeTrue(canConnect(adminJdbcTemplate), "Local Docker-backed PostgreSQL is not reachable for migration tests");

        adminJdbcTemplate.execute("CREATE DATABASE " + databaseName);
        databaseCreated = true;

        migrationDataSource = dataSource(databaseUrl(databaseName));
        migrationJdbcTemplate = new JdbcTemplate(migrationDataSource);
    }

    @AfterEach
    void tearDown() {
        close(migrationDataSource);
        if (adminJdbcTemplate != null && databaseName != null && databaseCreated) {
            adminJdbcTemplate.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
        }
        close(adminDataSource);
    }

    @Test
    void migratesFreshDatabaseToLatestVersion() {
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();

        assertThat(migrationJdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1",
                String.class
        )).isEqualTo("31");
        assertThat(regclass("public.tenants")).isNotNull();
        assertThat(regclass("public.policies")).isNotNull();
        assertThat(regclass("public.report_definition")).isNotNull();
        assertThat(regclass("template_.audit_log")).isNotNull();
        assertThat(regclass("public.idx_policies_created_at")).isNotNull();
    }

    @Test
    void seededSystemReportsExecuteAgainstFreshMigratedSchema() {
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();

        ReportConfigConverter converter = new ReportConfigConverter();
        ReportQueryBuilder queryBuilder = new ReportQueryBuilder(migrationJdbcTemplate);
        List<SeededReport> reports = migrationJdbcTemplate.query(
                """
                SELECT name, data_source, config::text
                FROM report_definition
                WHERE type = 'SYSTEM'
                  AND is_active = true
                  AND deleted_at IS NULL
                ORDER BY name
                """,
                (rs, rowNum) -> new SeededReport(
                        rs.getString("name"),
                        DataSource.valueOf(rs.getString("data_source")),
                        converter.convertToEntityAttribute(rs.getString("config"))
                )
        );

        assertThat(reports).hasSize(55);
        for (SeededReport report : reports) {
            ReportDefinition definition = ReportDefinition.builder()
                    .name(report.name())
                    .dataSource(report.dataSource())
                    .config(report.config())
                    .build();
            queryBuilder.execute(definition, Map.of(), 1);
        }
    }

    private boolean canConnect(JdbcTemplate jdbcTemplate) {
        try {
            jdbcTemplate.execute("SELECT 1");
            return true;
        } catch (CannotGetJdbcConnectionException ex) {
            return false;
        }
    }

    private String regclass(String relationName) {
        return migrationJdbcTemplate.queryForObject("SELECT to_regclass(?)", String.class, relationName);
    }

    private HikariDataSource dataSource(String url) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(System.getenv().getOrDefault("CIA_TEST_DB_USERNAME", "cia"));
        dataSource.setPassword(System.getenv().getOrDefault("CIA_TEST_DB_PASSWORD", "cia_dev"));
        dataSource.setConnectionInitSql("SET app.pii_key = 'test-pii-key'");
        dataSource.setMaximumPoolSize(2);
        return dataSource;
    }

    private String baseUrl() {
        return System.getenv().getOrDefault("CIA_TEST_DB_URL", "jdbc:postgresql://localhost:5434/cia");
    }

    private String databaseUrl(String targetDatabaseName) {
        String url = baseUrl();
        String query = "";
        int queryIndex = url.indexOf('?');
        if (queryIndex >= 0) {
            query = url.substring(queryIndex);
            url = url.substring(0, queryIndex);
        }
        int slashIndex = url.lastIndexOf('/');
        if (slashIndex < "jdbc:postgresql://".length()) {
            throw new IllegalArgumentException("Unsupported PostgreSQL JDBC URL: " + baseUrl());
        }
        return url.substring(0, slashIndex + 1) + targetDatabaseName + query;
    }

    private void close(HikariDataSource dataSource) {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private record SeededReport(String name, DataSource dataSource, ReportConfig config) {
    }
}
