package com.nubeero.cia.partner.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.partner.usage.PartnerUsageRollupStore.DailyCounts;
import com.nubeero.cia.partner.usage.PartnerUsageRollupStore.RollupKey;
import com.nubeero.cia.partner.usage.PartnerUsageRollupStore.StatusClass;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Integration test for {@link RedisPartnerUsageRollupStore} against a real Redis container
 * (Docker via Testcontainers) — mirrors {@code RedisPortalSessionStoreIT} (cia-partner-portal-bff)
 * for the container lifecycle and {@code PartnerRateLimitRedisIT} (this module) for direct
 * construction against a per-test {@link JedisPool} — {@link RedisPartnerUsageRollupStore}'s only
 * dependency is the pool, so no Spring context is needed.
 *
 * <p>Proves the counters {@link PartnerUsageRollupStore#increment} writes read back correctly via
 * {@link PartnerUsageRollupStore#snapshot} (total/success/clientError/serverError), and that {@link
 * PartnerUsageRollupStore#keysForDate} keys traffic by day — the same {@code (tenantId, clientId)}
 * pair recorded on two different dates shows up under each date independently, never conflated.
 */
@Testcontainers
class RedisPartnerUsageRollupStoreIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private JedisPool jedisPool;

    private RedisPartnerUsageRollupStore newStore() {
        jedisPool = new JedisPool(new JedisPoolConfig(), REDIS.getHost(), REDIS.getMappedPort(6379));
        return new RedisPartnerUsageRollupStore(jedisPool);
    }

    @AfterEach
    void closePool() {
        if (jedisPool != null) {
            jedisPool.close();
        }
    }

    @Test
    void increment_thenSnapshot_countersReadBackCorrectly() {
        RedisPartnerUsageRollupStore store = newStore();
        String tenantId = "tenant_acme";
        String clientId = "client-" + UUID.randomUUID();
        // Each test uses its own date — the @Container Redis (and its per-date index set) is
        // shared across every test method in this class, so a date collision would leak this
        // test's client into another test's keysForDate() assertion.
        LocalDate date = LocalDate.of(2026, 8, 27);

        store.increment(tenantId, clientId, date, StatusClass.SUCCESS);
        store.increment(tenantId, clientId, date, StatusClass.SUCCESS);
        store.increment(tenantId, clientId, date, StatusClass.CLIENT_ERROR);
        store.increment(tenantId, clientId, date, StatusClass.SERVER_ERROR);

        DailyCounts counts = store.snapshot(tenantId, clientId, date);

        assertThat(counts.total()).isEqualTo(4);
        assertThat(counts.success()).isEqualTo(2);
        assertThat(counts.clientError()).isEqualTo(1);
        assertThat(counts.serverError()).isEqualTo(1);
    }

    @Test
    void snapshot_withNoRecordedTraffic_returnsZero() {
        RedisPartnerUsageRollupStore store = newStore();

        DailyCounts counts = store.snapshot("tenant_acme", "unused-client-" + UUID.randomUUID(),
                LocalDate.of(2026, 8, 26));

        assertThat(counts).isEqualTo(DailyCounts.ZERO);
    }

    @Test
    void keysForDate_isolatesTrafficByDate_andEnumeratesEveryClientThatRecordedThatDay() {
        RedisPartnerUsageRollupStore store = newStore();
        String tenantId = "tenant_acme";
        String clientA = "client-a-" + UUID.randomUUID();
        String clientB = "client-b-" + UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 8, 25);
        LocalDate yesterday = today.minusDays(1);

        store.increment(tenantId, clientA, today, StatusClass.SUCCESS);
        store.increment(tenantId, clientB, today, StatusClass.SUCCESS);
        store.increment(tenantId, clientA, yesterday, StatusClass.SUCCESS);

        Set<RollupKey> todaysKeys = store.keysForDate(today);
        Set<RollupKey> yesterdaysKeys = store.keysForDate(yesterday);

        assertThat(todaysKeys).containsExactlyInAnyOrder(
                new RollupKey(tenantId, clientA), new RollupKey(tenantId, clientB));
        assertThat(yesterdaysKeys).containsExactly(new RollupKey(tenantId, clientA));
    }

    @Test
    void keysForDate_withNoTraffic_returnsEmptySet() {
        RedisPartnerUsageRollupStore store = newStore();

        assertThat(store.keysForDate(LocalDate.of(2020, 1, 1))).isEmpty();
    }
}
