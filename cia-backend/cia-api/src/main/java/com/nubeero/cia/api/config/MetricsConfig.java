package com.nubeero.cia.api.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tags every meter with {@code application=cia-api} so a shared Prometheus can
 * distinguish this service's series from others. Active in all profiles
 * (harmless in dev — the autoconfigured PrometheusMeterRegistry collects but is
 * not scraped). The {@code /actuator/prometheus} endpoint is not in the base
 * exposure list; the prod profile (application-prod.yml) adds it.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config().commonTags("application", "cia-api");
    }
}
