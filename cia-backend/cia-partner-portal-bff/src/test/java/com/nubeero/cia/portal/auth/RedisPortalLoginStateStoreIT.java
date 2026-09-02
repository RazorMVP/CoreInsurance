package com.nubeero.cia.portal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Integration test for {@link RedisPortalLoginStateStore} against a real Redis container (Docker
 * via Testcontainers) — mirrors {@code RedisPortalSessionStoreIT}'s container lifecycle. {@link
 * RedisPortalLoginStateStore}'s only dependency is a {@link JedisPool}, so — like {@code
 * PartnerRateLimitRedisIT} (cia-partner-api) and {@code RedisPartnerUsageRollupStoreIT} (this
 * fix wave) — it is constructed directly against a per-test pool rather than through a Spring
 * context.
 *
 * <p>Proves the contract {@link PortalLoginStateStore} documents: {@link
 * RedisPortalLoginStateStore#save} then {@link RedisPortalLoginStateStore#consume} round-trips the
 * verifier, the atomic {@code GETDEL} makes it single-use (a second {@code consume} of the same
 * {@code state} returns empty), and every key written carries a positive TTL.
 */
@Testcontainers
class RedisPortalLoginStateStoreIT {

    private static final String KEY_PREFIX = "portal-login-state:";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private JedisPool jedisPool;

    private RedisPortalLoginStateStore newStore() {
        jedisPool = new JedisPool(new JedisPoolConfig(), REDIS.getHost(), REDIS.getMappedPort(6379));
        return new RedisPortalLoginStateStore(jedisPool);
    }

    @AfterEach
    void closePool() {
        if (jedisPool != null) {
            jedisPool.close();
        }
    }

    @Test
    void save_thenConsume_returnsTheVerifier_andIsSingleUse() {
        RedisPortalLoginStateStore store = newStore();
        String state = "state-" + UUID.randomUUID();
        String verifier = "verifier-" + UUID.randomUUID();

        store.save(state, verifier);

        Optional<String> first = store.consume(state);
        Optional<String> second = store.consume(state);

        assertThat(first).contains(verifier);
        assertThat(second).isEmpty();
    }

    @Test
    void consume_unknownState_returnsEmpty() {
        RedisPortalLoginStateStore store = newStore();

        assertThat(store.consume("never-saved-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void save_overwritesAnyPriorVerifierForTheSameState() {
        RedisPortalLoginStateStore store = newStore();
        String state = "state-" + UUID.randomUUID();

        store.save(state, "verifier-first");
        store.save(state, "verifier-second");

        assertThat(store.consume(state)).contains("verifier-second");
    }

    @Test
    void save_writesAKeyWithAPositiveTtl() {
        RedisPortalLoginStateStore store = newStore();
        String state = "state-ttl-" + UUID.randomUUID();

        store.save(state, "verifier-abc");

        try (Jedis jedis = jedisPool.getResource()) {
            long ttlSeconds = jedis.ttl(KEY_PREFIX + state);
            assertThat(ttlSeconds)
                    .as("a saved login-state key must carry a positive TTL")
                    .isGreaterThan(0L);
        }
    }
}
