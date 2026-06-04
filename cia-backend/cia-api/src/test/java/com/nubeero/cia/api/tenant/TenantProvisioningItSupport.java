package com.nubeero.cia.api.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real PostgreSQL per IT subclass — each concrete class gets its own container so that the shared
 * template_ schema created by V2 during migration never collides across sibling IT classes.
 * Containers start lazily on first use and are cleaned up by Ryuk on JVM exit.
 * Each IT uses a distinct tenant schema name within its own isolated container.
 */
abstract class TenantProvisioningItSupport {

    private static final ConcurrentHashMap<Class<?>, HikariDataSource> DATA_SOURCES =
        new ConcurrentHashMap<>();

    DataSource dataSource() {
        return DATA_SOURCES.computeIfAbsent(getClass(), ignored -> {
            @SuppressWarnings("resource")
            PostgreSQLContainer<?> postgres =
                new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciaprov")
                    .withUsername("ciaprov")
                    .withPassword("ciaprov");
            postgres.start();

            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(postgres.getJdbcUrl());
            cfg.setUsername(postgres.getUsername());
            cfg.setPassword(postgres.getPassword());
            cfg.setMaximumPoolSize(4);
            return new HikariDataSource(cfg);
        });
    }
}
