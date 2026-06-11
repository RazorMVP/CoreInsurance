package com.nubeero.cia.api.platform;

import com.nubeero.cia.auth.TenantActivationLookup;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code public.tenants}-backed activation lookup with a short-TTL cache + explicit eviction.
 *
 * <p>{@code PlatformTenantService} evicts on suspend/activate so the change is reflected
 * immediately without waiting for TTL expiry. The schema-qualified {@code public.tenants}
 * read is deliberate — it is independent of the borrowed connection's {@code search_path}
 * and therefore safe to call regardless of which tenant schema is currently active.
 */
@Component
public class RegistryTenantActivationLookup implements TenantActivationLookup {

    private record Entry(boolean active, Instant at) {}

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final Duration ttl;

    public RegistryTenantActivationLookup(
            JdbcTemplate jdbc,
            @Value("${cia.platform.tenant-allowlist.cache-ttl-seconds:60}") long ttlSeconds) {
        this.jdbc = jdbc;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public boolean isActive(String realm) {
        Entry e = cache.get(realm);
        if (e != null && Duration.between(e.at(), Instant.now()).compareTo(ttl) < 0) {
            return e.active();
        }
        Boolean active = jdbc.query(
            "SELECT active FROM public.tenants WHERE schema_name = ?",
            rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE,
            realm);
        boolean result = Boolean.TRUE.equals(active);
        cache.put(realm, new Entry(result, Instant.now()));
        return result;
    }

    @Override
    public void evict(String realm) {
        cache.remove(realm);
    }
}
