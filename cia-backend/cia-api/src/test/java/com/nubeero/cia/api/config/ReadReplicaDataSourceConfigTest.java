package com.nubeero.cia.api.config;

import com.nubeero.cia.common.datasource.ReplicaRoutingDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the conditional, additive contract of {@link ReadReplicaDataSourceConfig}
 * without touching a database (Hikari pools are lazy — built here, never connected):
 *
 * <ul>
 *   <li>no {@code cia.datasource.replica.url} → config inert, the {@code DataSource}
 *       is Boot's ordinary single pool, no {@link ReplicaRoutingDataSource} exists
 *       (byte-identical to today);</li>
 *   <li>{@code cia.datasource.replica.url} set → the {@code @Primary DataSource} is a
 *       {@link ReplicaRoutingDataSource}.</li>
 * </ul>
 */
class ReadReplicaDataSourceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(ReadReplicaDataSourceConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://primary-host:5432/cia",
                    "spring.datasource.username=cia",
                    "spring.datasource.password=secret");

    @Test
    void inertWhenNoReplicaUrl() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DataSource.class);
            assertThat(ctx).doesNotHaveBean(ReplicaRoutingDataSource.class);
            assertThat(ctx.getBean(DataSource.class)).isNotInstanceOf(ReplicaRoutingDataSource.class);
        });
    }

    @Test
    void routesWhenReplicaUrlSet() {
        runner.withPropertyValues("cia.datasource.replica.url=jdbc:postgresql://replica-host:5432/cia")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ReplicaRoutingDataSource.class);
                    // The @Primary DataSource (what MultiTenantConnectionProvider injects) is the router.
                    assertThat(ctx.getBean(DataSource.class)).isInstanceOf(ReplicaRoutingDataSource.class);
                });
    }
}
