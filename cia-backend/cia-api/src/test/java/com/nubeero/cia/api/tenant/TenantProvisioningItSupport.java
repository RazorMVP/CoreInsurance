package com.nubeero.cia.api.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Spins up a real PostgreSQL once for the data-plane provisioning ITs and exposes a DataSource.
 * No Spring context — these units take a DataSource directly, so the IT is fast and focused.
 */
abstract class TenantProvisioningItSupport {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciaprov")
            .withUsername("ciaprov")
            .withPassword("ciaprov");

    static HikariDataSource dataSource;

    @BeforeAll
    static void startDb() {
        POSTGRES.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(cfg);
    }

    @AfterAll
    static void stopDb() {
        if (dataSource != null) dataSource.close();
    }

    DataSource dataSource() {
        return dataSource;
    }
}
