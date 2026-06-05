package com.nubeero.cia.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Validates application-prod.yml without booting the app: the YAML parses, the
 * ${ENV:default} placeholders resolve to their intended defaults, and the
 * actuator exposure list adds prometheus while keeping metrics off the web
 * surface. Binder.get(env) wires a placeholder resolver over the loaded source,
 * so ${DB_POOL_MAX:10} binds to 10 when the env var is absent.
 */
class ProdProfileConfigTest {

    private Binder prodBinder() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources =
            loader.load("application-prod", new ClassPathResource("application-prod.yml"));
        StandardEnvironment env = new StandardEnvironment();
        // Drop the ambient system sources so a DB_POOL_* (etc.) var set in CI or a
        // dev shell can't shadow the YAML's ${VAR:default} — these assertions
        // verify the committed defaults, deterministically.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.forEach(env.getPropertySources()::addLast);
        return Binder.get(env);
    }

    @Test
    void hikariTuningBindsWithEnvDefaults() throws Exception {
        Binder b = prodBinder();
        assertThat(b.bind("spring.datasource.hikari.maximum-pool-size", Integer.class).get()).isEqualTo(10);
        assertThat(b.bind("spring.datasource.hikari.minimum-idle", Integer.class).get()).isEqualTo(10);
        assertThat(b.bind("spring.datasource.hikari.max-lifetime", Long.class).get()).isEqualTo(1740000L);
        assertThat(b.bind("spring.datasource.hikari.keepalive-time", Long.class).get()).isEqualTo(300000L);
        assertThat(b.bind("spring.datasource.hikari.leak-detection-threshold", Long.class).get()).isEqualTo(60000L);
        assertThat(b.bind("spring.datasource.hikari.connection-timeout", Long.class).get()).isEqualTo(30000L);
    }

    @Test
    void structuredLoggingIsEcs() throws Exception {
        assertThat(prodBinder().bind("logging.structured.format.console", String.class).get())
            .isEqualTo("ecs");
    }

    @Test
    void rootLogLevelIsWarn() throws Exception {
        assertThat(prodBinder().bind("logging.level.root", String.class).get())
            .isEqualToIgnoringCase("warn");
    }

    @Test
    void actuatorExposesExactlyHealthInfoPrometheus() throws Exception {
        String include = prodBinder()
            .bind("management.endpoints.web.exposure.include", String.class).get();
        // Exact token set — a security-relevant list, so adding/removing any
        // endpoint (e.g. re-introducing metrics/env/beans to the web surface) fails.
        assertThat(Arrays.asList(include.split(",")))
            .containsExactlyInAnyOrder("health", "info", "prometheus");
    }
}
