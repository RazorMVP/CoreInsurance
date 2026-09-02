package com.nubeero.cia.partner.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.jedis.cas.JedisBasedProxyManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Redis-backed {@link PartnerBucketStore} — token buckets live in Redis via bucket4j's
 * Jedis {@link ProxyManager}, so every API replica sharing the same Redis instance sees
 * and depletes the SAME per-client budget. Closes backlog
 * {@code partner-ratelimit-redis-distributed}.
 *
 * <p>Reuses the existing {@link JedisPool} bean from {@code RedisClientConfig} — no
 * separate Redis client is added, mirroring {@code RedisPartnerUsageRollupStore} /
 * {@code RedisPortalSessionStore}. Transparent to callers: {@link PartnerRateLimitService}'s
 * public contract (keying, verdict shape, {@code tryConsume}/{@code evict} semantics) is
 * unchanged — only where the bucket state is held changes.
 *
 * <h2>Config vs. resize</h2>
 * A distributed bucket's {@link BucketConfiguration} is only read on first creation of a
 * given Redis key — unlike the in-memory store, {@link #bucketFor} does NOT resize an
 * existing bucket when {@code rpm} changes. {@link PartnerRateLimitService#evict} is the
 * supported path for a tier change to take effect immediately (it calls
 * {@link #evict(String)}, which removes the Redis-side proxy so the next
 * {@link #bucketFor} rebuilds it with the current rpm); absent an explicit evict, a
 * tier change still lands within the service's rpm-cache TTL on the NEXT time this key's
 * bucket is naturally recreated (bucket4j's TTL-based expiry — see {@code calculateTtlMillis}
 * inside {@code JedisBasedProxyManager} — reclaims idle keys).
 */
@Component
@ConditionalOnProperty(name = "cia.partner.rate-limit.store", havingValue = "redis")
class RedisPartnerBucketStore implements PartnerBucketStore {

    private static final String KEY_PREFIX = "partner-rate-limit:";

    private final ProxyManager<byte[]> proxyManager;

    RedisPartnerBucketStore(JedisPool jedisPool) {
        this.proxyManager = JedisBasedProxyManager.builderFor(jedisPool).build();
    }

    @Override
    public Bucket bucketFor(String key, int rpm) {
        Supplier<BucketConfiguration> configSupplier = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(rpm).refillGreedy(rpm, Duration.ofMinutes(1)).build())
                .build();
        return proxyManager.builder().build(redisKey(key), configSupplier);
    }

    @Override
    public void evict(String key) {
        proxyManager.removeProxy(redisKey(key));
    }

    @Override
    public void evictAll() {
        // bucket4j's ProxyManager has no bulk-clear primitive (it's a keyed proxy over
        // Redis, not an owner of a bounded keyspace to SCAN). Not called from any
        // production path today (see PartnerRateLimitService javadoc) — per-key
        // #evict(String) is what a tier change uses. Distributed buckets also
        // self-expire via bucket4j's Redis TTL, so this is a safe no-op rather than a
        // correctness gap.
    }

    private static byte[] redisKey(String key) {
        return (KEY_PREFIX + key).getBytes(StandardCharsets.UTF_8);
    }
}
