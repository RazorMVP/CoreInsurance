package com.nubeero.cia.portal.session;

import com.nubeero.cia.partner.config.RedisClientConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * Minimal {@code @SpringBootConfiguration} fixture so {@code @SpringBootTest} can bootstrap a
 * context for {@code RedisPortalSessionStoreIT} in isolation, mirroring
 * {@code PartnerPortalBffTestApplication} in the {@code grant} package.
 *
 * <p>{@code cia-partner-portal-bff} has no {@code @SpringBootApplication} of its own (that lives
 * in {@code cia-api}, which is not a dependency of this module). {@link Import} pulls in the real
 * {@link RedisClientConfig} (the {@code JedisPool} bean, reused as-is — no separate Redis client);
 * {@link ComponentScan} (defaulting to this class's own package) picks up whichever of
 * {@link InMemoryPortalSessionStore} / {@link RedisPortalSessionStore} the
 * {@code cia.partner-portal.store} property selects.
 *
 * <p>JPA/DataSource autoconfiguration is excluded — this module also carries
 * {@code spring-boot-starter-data-jpa} (for the unrelated {@code grant} package), and a bare
 * {@code @EnableAutoConfiguration} would otherwise try to build a {@code DataSource} with no
 * {@code spring.datasource.*} properties configured for this test.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
@Import(RedisClientConfig.class)
@ComponentScan
class RedisPortalSessionStoreTestApplication {
}
