package com.nubeero.cia.portal.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link RedisPortalSessionStore} against a real Redis container (Docker via
 * Testcontainers) — re-runs the same contract {@code PortalSessionStoreTest} exercises against
 * {@link InMemoryPortalSessionStore}, proving the two implementations agree.
 *
 * <p>{@code cia.partner-portal.store=redis} (set below via {@code @DynamicPropertySource}) makes
 * {@link RedisPortalSessionStore} the only bean whose {@code @ConditionalOnProperty} matches, so
 * {@code @Autowired PortalSessionStore} resolves to it unambiguously.
 */
@Testcontainers
@SpringBootTest(classes = RedisPortalSessionStoreTestApplication.class)
class RedisPortalSessionStoreIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("cia.partner-portal.store", () -> "redis");
        // cia-partner-api's bucket4j-spring-boot-starter autoconfiguration is on this module's
        // classpath transitively; its ApplicationReadyEvent listener asserts a cache is
        // configured, which this narrow test context doesn't provide. Disabled the same way
        // every other full-context IT in the reactor disables it (see e.g. FinanceWebItSupport).
        registry.add("bucket4j.enabled", () -> "false");
    }

    @Autowired
    private PortalSessionStore store;

    private static PortalSession newSession(Instant idleExpiry, Instant absoluteExpiry) {
        return new PortalSession(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "dev@insurtech.example",
                "Dev One",
                "access-token-abc",
                "refresh-token-xyz",
                absoluteExpiry,
                idleExpiry,
                "csrf-token-123");
    }

    @Test
    void isRedisBackedStore() {
        assertThat(store).isInstanceOf(RedisPortalSessionStore.class);
    }

    @Test
    void create_thenGet_roundTrips() {
        PortalSession session = newSession(Instant.now().plusSeconds(900), Instant.now().plusSeconds(28_800));

        String id = store.create(session);

        assertThat(id).isEqualTo(session.id());
        Optional<PortalSession> found = store.get(id);
        assertThat(found).contains(session);
    }

    @Test
    void get_missingId_returnsEmpty() {
        assertThat(store.get(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void get_afterAbsoluteExpiry_returnsEmpty() {
        PortalSession session = newSession(Instant.now().plusSeconds(900), Instant.now().minusSeconds(1));
        store.create(session);

        assertThat(store.get(session.id())).isEmpty();
    }

    @Test
    void get_afterIdleExpiry_returnsEmpty() {
        PortalSession session = newSession(Instant.now().minusSeconds(1), Instant.now().plusSeconds(28_800));
        store.create(session);

        assertThat(store.get(session.id())).isEmpty();
    }

    @Test
    void touch_extendsIdleExpiry_cappedAtAbsolute() {
        Instant idle = Instant.now().plusSeconds(60);
        Instant absolute = Instant.now().plusSeconds(28_800);
        PortalSession session = newSession(idle, absolute);
        store.create(session);

        store.touch(session.id());

        PortalSession touched = store.get(session.id()).orElseThrow();
        assertThat(touched.idleExpiry()).isAfter(idle);
        assertThat(touched.idleExpiry()).isBeforeOrEqualTo(absolute);
    }

    @Test
    void touch_capsIdleExpiryAtAbsoluteExpiry() {
        Instant absolute = Instant.now().plusSeconds(30);
        PortalSession session = newSession(Instant.now().plusSeconds(10), absolute);
        store.create(session);

        store.touch(session.id());

        PortalSession touched = store.get(session.id()).orElseThrow();
        assertThat(touched.idleExpiry()).isEqualTo(absolute);
    }

    @Test
    void touch_onMissingId_isNoOp() {
        store.touch(UUID.randomUUID().toString());
    }

    @Test
    void delete_removesSession() {
        PortalSession session = newSession(Instant.now().plusSeconds(900), Instant.now().plusSeconds(28_800));
        store.create(session);

        store.delete(session.id());

        assertThat(store.get(session.id())).isEmpty();
    }

    @Test
    void delete_onMissingId_isNoOp() {
        store.delete(UUID.randomUUID().toString());
    }
}
