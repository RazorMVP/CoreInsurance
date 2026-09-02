package com.nubeero.cia.partner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nubeero.cia.partner.app.PartnerApp;
import com.nubeero.cia.partner.app.PartnerAppRepository;
import com.nubeero.cia.partner.config.PartnerRateLimitService.RateLimitVerdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Integration test proving the Redis-backed {@link PartnerBucketStore} (see
 * {@link RedisPartnerBucketStore}) shares budget across replicas — closes backlog
 * {@code partner-ratelimit-redis-distributed}.
 *
 * <p>Constructs TWO independent {@link PartnerRateLimitService} instances, each with
 * its OWN {@link JedisPool} (simulating two separate API pods), both pointed at the
 * SAME Testcontainers Redis. A single client_id's budget depletes across BOTH
 * instances, proving the bucket lives in Redis rather than per-JVM memory. Mirrors
 * {@code RedisPortalSessionStoreIT} (cia-partner-portal-bff) for the container
 * lifecycle / property wiring.
 *
 * <p>A companion test proves the plain 2-arg constructor (the unit-test / default
 * path) is NOT distributed — two in-memory-backed instances do NOT share budget —
 * so the toggle's behavioural difference is asserted, not just its wiring.
 *
 * <p>Fix round 1: also proves the {@code partner-rate-limit:*} Redis key(s) created by a
 * consume carry a POSITIVE TTL — {@link RedisPartnerBucketStore} previously built its
 * {@code JedisBasedProxyManager} with bucket4j-redis's default
 * {@code ExpirationAfterWriteStrategy.none()} (TTL {@code -1}, i.e. no expiry), which
 * meant every client's Redis key was written to persist FOREVER — an unbounded keyspace
 * leak. This test would have failed against that implementation.
 */
@Testcontainers
@ExtendWith(MockitoExtension.class)
class PartnerRateLimitRedisIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Mock
    private PartnerAppRepository repo;

    /** Every pool this test creates, closed in {@link #closePools} so the test doesn't leak connections. */
    private final List<JedisPool> pools = new ArrayList<>();

    @AfterEach
    void closePools() {
        pools.forEach(JedisPool::close);
        pools.clear();
    }

    private static PartnerApp app(int rpm) {
        return PartnerApp.builder().clientId("x").appName("x").contactEmail("x@x")
                .rateLimitRpm(rpm).active(true).build();
    }

    private JedisPool newPool() {
        JedisPool pool = new JedisPool(new JedisPoolConfig(), REDIS.getHost(), REDIS.getMappedPort(6379));
        pools.add(pool);
        return pool;
    }

    private PartnerRateLimitService redisReplica() {
        PartnerRateLimitProperties props = new PartnerRateLimitProperties();
        props.setDefaultRpm(60);
        return new PartnerRateLimitService(repo, props, new RedisPartnerBucketStore(newPool()));
    }

    @Test
    void budgetIsSharedAcrossReplicas_whenBackedByRedis() {
        String clientId = "shared-client-" + UUID.randomUUID();
        when(repo.findByClientId(eq(clientId))).thenReturn(Optional.of(app(2)));

        PartnerRateLimitService replicaA = redisReplica();
        PartnerRateLimitService replicaB = redisReplica();

        RateLimitVerdict first = replicaA.tryConsume(clientId);
        RateLimitVerdict second = replicaB.tryConsume(clientId);
        RateLimitVerdict third = replicaA.tryConsume(clientId);
        RateLimitVerdict fourth = replicaB.tryConsume(clientId);

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isEqualTo(0);
        // Budget (rpm=2) is already exhausted — replica A's 3rd consume and replica
        // B's 4th consume both see an empty bucket, proving the SAME Redis-held state.
        assertThat(third.allowed()).isFalse();
        assertThat(fourth.allowed()).isFalse();
    }

    @Test
    void distinctClients_remainIsolated_underRedis() {
        String clientA = "client-a-" + UUID.randomUUID();
        String clientB = "client-b-" + UUID.randomUUID();
        when(repo.findByClientId(eq(clientA))).thenReturn(Optional.of(app(1)));
        when(repo.findByClientId(eq(clientB))).thenReturn(Optional.of(app(1)));

        PartnerRateLimitService replicaA = redisReplica();
        PartnerRateLimitService replicaB = redisReplica();

        assertThat(replicaA.tryConsume(clientA).allowed()).isTrue();
        assertThat(replicaB.tryConsume(clientA).allowed()).isFalse(); // clientA's shared budget exhausted

        // clientB is untouched — its own (also shared) bucket still has its token.
        assertThat(replicaB.tryConsume(clientB).allowed()).isTrue();
    }

    @Test
    void inMemoryDefault_doesNotShareBudgetAcrossInstances() {
        String clientId = "not-shared-" + UUID.randomUUID();
        when(repo.findByClientId(eq(clientId))).thenReturn(Optional.of(app(1)));
        PartnerRateLimitProperties props = new PartnerRateLimitProperties();
        props.setDefaultRpm(60);

        // Plain 2-arg constructor — defaults to InMemoryPartnerBucketStore, one map per instance.
        PartnerRateLimitService instanceA = new PartnerRateLimitService(repo, props);
        PartnerRateLimitService instanceB = new PartnerRateLimitService(repo, props);

        assertThat(instanceA.tryConsume(clientId).allowed()).isTrue(); // exhausts instance A's own bucket

        // instance B has never seen this key — its own independent bucket still has its token.
        assertThat(instanceB.tryConsume(clientId).allowed()).isTrue();
    }

    /**
     * Fix round 1 — CRITICAL regression guard: a Redis-backed bucket must carry a TTL, or
     * every client_id that ever calls the partner API leaves a key in Redis forever. Scans
     * the WHOLE keyspace (this container is dedicated to this test class) rather than
     * assuming bucket4j's exact key encoding, so the assertion holds even if bucket4j wraps
     * or prefixes the key we pass it.
     */
    @Test
    void redisKey_carriesPositiveTtl_afterConsume() {
        String clientId = "ttl-check-" + UUID.randomUUID();
        when(repo.findByClientId(eq(clientId))).thenReturn(Optional.of(app(5)));

        RateLimitVerdict verdict = redisReplica().tryConsume(clientId);
        assertThat(verdict.allowed()).isTrue();

        JedisPool inspectionPool = newPool();
        try (Jedis jedis = inspectionPool.getResource()) {
            Set<String> keys = jedis.keys("*");
            assertThat(keys).as("a Redis key should exist after tryConsume").isNotEmpty();
            for (String key : keys) {
                long ttlSeconds = jedis.ttl(key);
                assertThat(ttlSeconds)
                        .as("key '%s' must carry a positive TTL (no TTL == permanent keyspace leak)", key)
                        .isGreaterThan(0L);
            }
        }
    }
}
