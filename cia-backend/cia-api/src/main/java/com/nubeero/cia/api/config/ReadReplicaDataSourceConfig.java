package com.nubeero.cia.api.config;

import com.nubeero.cia.common.datasource.ReplicaRoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Wires a read-replica behind a {@link ReplicaRoutingDataSource} when (and only
 * when) {@code cia.datasource.replica.url} (env {@code CIA_DATASOURCE_REPLICA_URL})
 * is set. <b>Additive by construction:</b> with the property absent this whole
 * configuration is inert ({@code @ConditionalOnProperty} → not loaded), Spring
 * Boot auto-configures its usual single {@code DataSource}, and the runtime is
 * byte-identical to a deployment with no replica.
 *
 * <p>When active it takes over the datasource definition so it can build two
 * pools and route between them:
 * <ul>
 *   <li><b>primary</b> — rebuilt from {@code spring.datasource.*} +
 *       {@code spring.datasource.hikari.*} exactly as Boot would (Boot's pooled
 *       datasource backs off because a {@code DataSource} bean is now present);</li>
 *   <li><b>replica</b> — same {@code spring.datasource.hikari} namespace (so it
 *       inherits the pool tuning <em>and</em> {@code connection-init-sql} — the
 *       {@code SET app.pii_key} that lets {@code @ColumnTransformer} PII decrypt
 *       on replica reads too), with url/credentials from
 *       {@code cia.datasource.replica.*} (credentials default to the primary's);</li>
 *   <li><b>routing</b> ({@code @Primary}) — chooses replica vs primary per borrow
 *       via {@link ReplicaRoutingDataSource}; default = primary, so writes, Flyway
 *       migrations and every non-report read stay on the primary.</li>
 * </ul>
 *
 * <p>The {@code @Primary} routing datasource is what {@code MultiTenantConnection
 * Provider} injects, so {@code SET search_path} runs on whichever pool the borrow
 * resolved to — tenant isolation is unchanged on both.
 */
@Configuration
@ConditionalOnProperty(name = "cia.datasource.replica.url")
@EnableConfigurationProperties(ReplicaDataSourceProperties.class)
public class ReadReplicaDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource primaryDataSource(DataSourceProperties primaryProps) {
        // Mirrors Boot's auto datasource: spring.datasource.* via the builder,
        // then @ConfigurationProperties binds spring.datasource.hikari.* on top
        // (pool sizing + connection-init-sql pii-key).
        return primaryProps.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource replicaDataSource(DataSourceProperties primaryProps,
                                              ReplicaDataSourceProperties replicaProps) {
        String username = StringUtils.hasText(replicaProps.getUsername())
                ? replicaProps.getUsername() : primaryProps.determineUsername();
        String password = StringUtils.hasText(replicaProps.getPassword())
                ? replicaProps.getPassword() : primaryProps.determinePassword();
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(replicaProps.getUrl())
                .username(username)
                .password(password)
                .driverClassName(primaryProps.determineDriverClassName())
                .build();
        // @ConfigurationProperties("spring.datasource.hikari") then binds the same
        // pool tuning + connection-init-sql onto the replica pool.
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("primaryDataSource") HikariDataSource primary,
                                 @Qualifier("replicaDataSource") HikariDataSource replica) {
        ReplicaRoutingDataSource routing = new ReplicaRoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(ReplicaRoutingDataSource.PRIMARY, primary);
        targets.put(ReplicaRoutingDataSource.REPLICA, replica);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(primary);
        return routing;
    }
}
