package com.nubeero.cia.partner.config;

import io.github.bucket4j.Bucket;

/**
 * Where a {@link PartnerRateLimitService} token bucket actually lives. Extracted so the
 * service's public contract (keying, rpm resolution, {@code tryConsume}/{@code evict}
 * semantics) stays identical while the storage swaps between a single-instance
 * {@link InMemoryPartnerBucketStore} (default) and the distributed
 * {@link RedisPartnerBucketStore} (behind {@code cia.partner.rate-limit.store=redis}).
 *
 * <p>Package-private — this is an internal seam, not part of the module's public API.
 */
interface PartnerBucketStore {

    /**
     * Return the bucket for {@code key}, creating it sized to {@code rpm} if absent.
     * Implementations MAY resize an existing bucket when {@code rpm} has changed for
     * that key (the in-memory store does; the Redis store relies on {@link #evict}
     * for that, since a distributed bucket's config is only read on first creation).
     */
    Bucket bucketFor(String key, int rpm);

    /** Drop any state held for {@code key} so the next {@link #bucketFor} call rebuilds it. */
    void evict(String key);

    /** Drop all state. Best-effort — see implementation notes for caveats. */
    void evictAll();
}
