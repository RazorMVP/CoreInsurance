package com.nubeero.cia.partner.config;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.partner.app.PartnerApp;
import com.nubeero.cia.partner.app.PartnerAppRepository;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Per-client token-bucket rate limiter for the partner API.
 *
 * <p>Replaces the previous single global {@code bucket4j} filter (one shared
 * {@code /partner/v1/.*} bucket for every partner — so one abusive partner could
 * exhaust the budget for all). Each client_id now gets its <em>own</em> bucket
 * sized to that partner's {@code PartnerApp.rateLimitRpm} (set per plan tier at
 * provisioning), so partners are isolated and tiered.
 *
 * <h2>Keying</h2>
 * Buckets and the rpm cache are keyed by {@code tenantId + ":" + clientId} — a
 * client_id is only unique within its tenant realm, and pooled request threads
 * cross tenants, so a tenant-blind key would be a cross-tenant correctness bug
 * (the same lesson as {@code FiscalPeriodLookupCache}).
 *
 * <h2>Store</h2>
 * Bucket storage is delegated to a {@link PartnerBucketStore}: the default
 * {@link InMemoryPartnerBucketStore} (correct for a single instance and for dev/test)
 * or, behind {@code cia.partner.rate-limit.store=redis}, {@link RedisPartnerBucketStore}
 * — buckets shared across every replica via the existing {@code bucket4j-redis} Jedis
 * {@code ProxyManager} (closes {@code partner-ratelimit-redis-distributed}). Swapping the
 * store is transparent: this class's public API and the rate-limit contract are unchanged,
 * only where a client's bucket lives changes.
 *
 * <h2>rpm freshness</h2>
 * The partner's rpm is cached per key with a short TTL (avoids a DB read per
 * request); a tier change therefore takes effect within the TTL, or immediately
 * via {@link #evict(String)} / {@link #evictAll()}.
 */
@Service
public class PartnerRateLimitService {

    private static final long RPM_CACHE_TTL_MILLIS = 60_000L;

    private final PartnerAppRepository partnerAppRepository;
    private final PartnerRateLimitProperties properties;
    private final PartnerBucketStore bucketStore;

    private final ConcurrentHashMap<String, CachedRpm> rpmCache = new ConcurrentHashMap<>();

    /**
     * Plain-Java constructor — used directly by unit tests (no Spring context) and
     * defaults to the single-instance {@link InMemoryPartnerBucketStore}.
     */
    public PartnerRateLimitService(PartnerAppRepository partnerAppRepository,
                                   PartnerRateLimitProperties properties) {
        this(partnerAppRepository, properties, new InMemoryPartnerBucketStore());
    }

    /** Spring-wired constructor — {@code bucketStore} is whichever store the toggle activated. */
    @Autowired
    public PartnerRateLimitService(PartnerAppRepository partnerAppRepository,
                                   PartnerRateLimitProperties properties,
                                   PartnerBucketStore bucketStore) {
        this.partnerAppRepository = partnerAppRepository;
        this.properties = properties;
        this.bucketStore = bucketStore;
    }

    private record CachedRpm(int rpm, long cachedAtMillis) {}

    /** Outcome of consuming one token for a request. */
    public record RateLimitVerdict(boolean allowed, long limit, long remaining,
                                   long retryAfterSeconds, long resetEpochSeconds) {}

    /**
     * Consume one token from {@code clientId}'s bucket (creating/resizing it from
     * the partner's current rpm). Never throws — an unresolvable client falls back
     * to {@code properties.defaultRpm}.
     */
    public RateLimitVerdict tryConsume(String clientId) {
        int rpm = resolveRpm(clientId);
        String key = key(clientId);
        Bucket bucket = bucketStore.bucketFor(key, rpm);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        long nowSeconds = System.currentTimeMillis() / 1000L;
        long waitSeconds = (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0);
        return new RateLimitVerdict(
                probe.isConsumed(),
                rpm,
                Math.max(0, probe.getRemainingTokens()),
                Math.max(1, waitSeconds),
                nowSeconds + Math.max(0, waitSeconds));
    }

    /** Drop the cached rpm + bucket for a client (call when its tier/rpm changes). */
    public void evict(String clientId) {
        String key = key(clientId);
        bucketStore.evict(key);
        rpmCache.remove(key);
    }

    public void evictAll() {
        bucketStore.evictAll();
        rpmCache.clear();
    }

    private int resolveRpm(String clientId) {
        String key = key(clientId);
        CachedRpm cached = rpmCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.cachedAtMillis() < RPM_CACHE_TTL_MILLIS) {
            return cached.rpm();
        }
        int rpm = partnerAppRepository.findByClientId(clientId)
                .filter(PartnerApp::isActive)
                .map(PartnerApp::getRateLimitRpm)
                .filter(r -> r > 0)
                .orElse(properties.getDefaultRpm());
        rpmCache.put(key, new CachedRpm(rpm, System.currentTimeMillis()));
        return rpm;
    }

    private static String key(String clientId) {
        String tenant = TenantContext.getTenantId();
        return (tenant == null ? "_" : tenant) + ":" + clientId;
    }
}
