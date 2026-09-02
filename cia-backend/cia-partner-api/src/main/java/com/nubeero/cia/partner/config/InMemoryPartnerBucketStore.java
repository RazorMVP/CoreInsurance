package com.nubeero.cia.partner.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default, single-instance {@link PartnerBucketStore} — token buckets held in a
 * {@link ConcurrentHashMap}. Correct for a single replica and for dev/test; buckets
 * are NOT shared across replicas (see {@link RedisPartnerBucketStore} for the
 * distributed alternative). {@code matchIfMissing = true} — this is the store used
 * unless {@code cia.partner.rate-limit.store=redis} is set.
 */
@Component
@ConditionalOnProperty(name = "cia.partner.rate-limit.store", havingValue = "in-memory", matchIfMissing = true)
class InMemoryPartnerBucketStore implements PartnerBucketStore {

    /** A bucket plus the rpm it was built with, so a tier change rebuilds it. */
    private record BucketEntry(int rpm, Bucket bucket) {}

    private final ConcurrentHashMap<String, BucketEntry> buckets = new ConcurrentHashMap<>();

    @Override
    public Bucket bucketFor(String key, int rpm) {
        BucketEntry entry = buckets.compute(key, (k, existing) ->
                (existing == null || existing.rpm() != rpm) ? new BucketEntry(rpm, newBucket(rpm)) : existing);
        return entry.bucket();
    }

    @Override
    public void evict(String key) {
        buckets.remove(key);
    }

    @Override
    public void evictAll() {
        buckets.clear();
    }

    private static Bucket newBucket(int rpm) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(rpm)
                .refillGreedy(rpm, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
