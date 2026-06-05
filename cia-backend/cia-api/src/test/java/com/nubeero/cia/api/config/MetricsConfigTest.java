package com.nubeero.cia.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MetricsConfigTest {

    @Test
    void appliesApplicationCommonTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MetricsConfig().commonTags().customize(registry);

        Counter counter = registry.counter("test.counter");
        assertThat(counter.getId().getTag("application")).isEqualTo("cia-api");
    }
}
