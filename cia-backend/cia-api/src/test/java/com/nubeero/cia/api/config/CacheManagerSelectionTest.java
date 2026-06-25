package com.nubeero.cia.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.cache.RedisCacheManager;

/**
 * Guards the {@code spring.cache.type=simple} fix.
 *
 * <p><b>The bug:</b> {@code @EnableCaching} is on the app, and Redis is on the
 * classpath (partner rate-limiting, Keycloak sessions). With no
 * {@code spring.cache.type}, Spring Boot auto-selects {@code RedisCacheManager}
 * with the default <em>JDK</em> serializer. The cached entities
 * ({@code PostingRule}, {@code ChartOfAccount}) are <b>not</b> {@code Serializable},
 * so every cache write — e.g. {@code PostingRuleService.findByEventType} on the
 * policy/claim/endorsement-approval GL-posting path — throws a
 * {@code SerializationException} → HTTP 500. The full IT suite missed it because
 * {@code @SpringBootTest} has no Redis, so caching already falls back to
 * {@code simple}. It only breaks where Redis is the real cache backend (local
 * dev + prod).
 *
 * <p>These caches are seeded, read-only reference data (no runtime edit
 * endpoints), so the intended in-memory {@code ConcurrentMapCacheManager} is
 * correct — pinned via {@code spring.cache.type=simple} so Redis can't take over.
 */
class CacheManagerSelectionTest {

    /**
     * A Redis connection factory bean is created from these properties (no live
     * Redis needed) — exactly the condition that makes {@code CacheAutoConfiguration}
     * prefer {@code RedisCacheManager}.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RedisAutoConfiguration.class, CacheAutoConfiguration.class))
            .withUserConfiguration(CachingEnabled.class)
            .withPropertyValues("spring.data.redis.host=localhost", "spring.data.redis.port=6380");

    @Test
    void redisPresentWithoutCacheType_autoSelectsRedisCacheManager_theBugCondition() {
        runner.run(ctx -> assertThat(ctx.getBean(CacheManager.class))
                .as("Redis on the classpath + no spring.cache.type => RedisCacheManager (JDK "
                        + "serializer; breaks on non-Serializable cached entities)")
                .isInstanceOf(RedisCacheManager.class));
    }

    @Test
    void cacheTypeSimple_keepsInMemoryCacheManagerEvenWithRedisPresent_theFix() {
        runner.withPropertyValues("spring.cache.type=simple")
                .run(ctx -> assertThat(ctx.getBean(CacheManager.class))
                        .as("spring.cache.type=simple pins the intended in-memory cache despite Redis")
                        .isInstanceOf(ConcurrentMapCacheManager.class));
    }

    @Test
    void applicationYaml_pinsCacheTypeToSimple() throws Exception {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertThat(loaded.get(0).getProperty("spring.cache.type"))
                .as("application.yml must pin spring.cache.type=simple (the fix)")
                .isEqualTo("simple");
    }

    @Configuration
    @EnableCaching
    static class CachingEnabled {
    }
}
