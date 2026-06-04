package com.nubeero.cia.api.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Real PostgreSQL shared by the data-plane provisioning ITs (no Spring context — units take a
 * DataSource directly). Container starts once for the JVM via the static initializer; Ryuk handles
 * teardown — mirrors FinanceWebItSupport so multiple IT subclasses share one container safely.
 * Each IT uses a distinct tenant schema name so they do not collide. V2 (which targets the shared
 * {@code template_} schema and has non-idempotent {@code CREATE INDEX} statements) is now baselined
 * out by {@code TenantSchemaMigrator}, so provisioning multiple tenants in the same DB is safe —
 * exactly what this shared container proves. ITs that touch the shared {@code public.tenants}
 * registry manage their own rows in {@code @BeforeEach}.
 */
abstract class TenantProvisioningItSupport {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciaprov")
            .withUsername("ciaprov")
            .withPassword("ciaprov");

    static final HikariDataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        DATA_SOURCE = new HikariDataSource(cfg);
    }

    DataSource dataSource() {
        return DATA_SOURCE;
    }
}
