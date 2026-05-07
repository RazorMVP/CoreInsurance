package com.nubeero.cia.tenant;

import com.nubeero.cia.common.tenant.MultiTenantConnectionProvider;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TenantProvisioningServiceIntegrationTest {

    private HikariDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TenantProvisioningService provisioningService;
    private MultiTenantConnectionProvider connectionProvider;
    private String alphaSchema;
    private String betaSchema;
    private String alphaSubdomain;
    private String betaSubdomain;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        alphaSchema = "tenant_it_" + suffix + "_a";
        betaSchema = "tenant_it_" + suffix + "_b";
        alphaSubdomain = "it-" + suffix + "-a";
        betaSubdomain = "it-" + suffix + "-b";

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(System.getenv().getOrDefault("CIA_TEST_DB_URL", "jdbc:postgresql://localhost:5434/cia"));
        dataSource.setUsername(System.getenv().getOrDefault("CIA_TEST_DB_USERNAME", "cia"));
        dataSource.setPassword(System.getenv().getOrDefault("CIA_TEST_DB_PASSWORD", "cia_dev"));
        dataSource.setConnectionInitSql("SET app.pii_key = 'test-pii-key'");

        assumeTrue(canConnect(), "Local Docker-backed PostgreSQL is not reachable for tenant isolation test");

        jdbcTemplate = new JdbcTemplate(dataSource);
        prepareDatabase();

        TenantSchemaMigrator tenantSchemaMigrator = new TenantSchemaMigrator(dataSource, jdbcTemplate);
        provisioningService = new TenantProvisioningService(jdbcTemplate, tenantSchemaMigrator);
        connectionProvider = new MultiTenantConnectionProvider(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (jdbcTemplate != null) {
            cleanupTestTenants();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Test
    void provisionsTenantSchemaRegistryRowAndBusinessTables() {
        TenantProvisionResponse response = provisioningService.provision(
                new TenantProvisionRequest(alphaSchema, alphaSubdomain, "Alpha Insurance")
        );

        assertThat(response.active()).isTrue();
        assertThat(response.schemaName()).isEqualTo(alphaSchema);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active FROM public.tenants WHERE schema_name = ?",
                Boolean.class,
                alphaSchema
        )).isTrue();
        assertThat(regclass(alphaSchema + ".customers")).isEqualTo(alphaSchema + ".customers");
        assertThat(regclass(alphaSchema + ".flyway_schema_history")).isEqualTo(alphaSchema + ".flyway_schema_history");
        assertThat(regclass(alphaSchema + ".tenants")).isNull();
    }

    @Test
    void keepsTwoTenantDataIsolatedThroughSchemaRouting() throws Exception {
        provisioningService.provision(new TenantProvisionRequest(alphaSchema, alphaSubdomain, "Alpha Insurance"));
        provisioningService.provision(new TenantProvisionRequest(betaSchema, betaSubdomain, "Beta Insurance"));

        insertCustomer(alphaSchema, "INDIVIDUAL");
        insertCustomer(betaSchema, "CORPORATE");

        assertThat(customerCount(alphaSchema)).isEqualTo(1);
        assertThat(customerCount(betaSchema)).isEqualTo(1);

        insertCustomer(alphaSchema, "CORPORATE");

        assertThat(customerCount(alphaSchema)).isEqualTo(2);
        assertThat(customerCount(betaSchema)).isEqualTo(1);
    }

    @Test
    void rerunsMigrationsForTenantSchemasWithoutCrossTenantSideEffects() throws Exception {
        provisioningService.provision(new TenantProvisionRequest(alphaSchema, alphaSubdomain, "Alpha Insurance"));
        provisioningService.provision(new TenantProvisionRequest(betaSchema, betaSubdomain, "Beta Insurance"));
        insertCustomer(alphaSchema, "INDIVIDUAL");
        int publicCustomerCountBefore = publicCustomerCount();

        TenantSchemaMigrator tenantSchemaMigrator = new TenantSchemaMigrator(dataSource, jdbcTemplate);
        tenantSchemaMigrator.migrateTenantSchema(alphaSchema);
        tenantSchemaMigrator.migrateTenantSchema(betaSchema);

        assertThat(customerCount(alphaSchema)).isEqualTo(1);
        assertThat(customerCount(betaSchema)).isZero();
        assertThat(publicCustomerCount()).isEqualTo(publicCustomerCountBefore);
    }

    private boolean canConnect() {
        try (Connection ignored = dataSource.getConnection()) {
            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    private void prepareDatabase() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS public.tenants (
                    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    schema_name VARCHAR(63)  NOT NULL UNIQUE,
                    name        VARCHAR(255) NOT NULL,
                    subdomain   VARCHAR(63)  NOT NULL UNIQUE,
                    active      BOOLEAN      NOT NULL DEFAULT TRUE,
                    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
                )
                """
        );
        cleanupTestTenants();
    }

    private void cleanupTestTenants() {
        if (alphaSchema != null) {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + alphaSchema + " CASCADE");
            jdbcTemplate.update("DELETE FROM public.tenants WHERE schema_name = ? OR subdomain = ?",
                    alphaSchema, alphaSubdomain);
        }
        if (betaSchema != null) {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + betaSchema + " CASCADE");
            jdbcTemplate.update("DELETE FROM public.tenants WHERE schema_name = ? OR subdomain = ?",
                    betaSchema, betaSubdomain);
        }
    }

    private String regclass(String relationName) {
        return jdbcTemplate.queryForObject("SELECT to_regclass(?)", String.class, relationName);
    }

    private void insertCustomer(String tenantSchema, String customerType) throws Exception {
        withTenantConnection(tenantSchema, connection -> {
            var statement = connection.prepareStatement("INSERT INTO customers (customer_type) VALUES (?)");
            statement.setString(1, customerType);
            return statement.executeUpdate();
        });
    }

    private int customerCount(String tenantSchema) throws Exception {
        return withTenantConnection(tenantSchema, connection -> {
            var resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) FROM customers");
            resultSet.next();
            return resultSet.getInt(1);
        });
    }

    private int publicCustomerCount() {
        if (regclass("public.customers") == null) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.customers", Integer.class);
        return count == null ? 0 : count;
    }

    private <T> T withTenantConnection(String tenantSchema, SqlFunction<T> function) throws Exception {
        Connection connection = connectionProvider.getConnection(tenantSchema);
        try {
            return function.apply(connection);
        } finally {
            connectionProvider.releaseConnection(tenantSchema, connection);
        }
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }
}
