package com.nubeero.cia.partner.usage;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * In-memory {@link PartnerUsageRollupStore} — the dev/IT default so tests need no Redis unless
 * they opt in via {@code cia.partner-usage.store=redis}. Not suitable for a multi-replica
 * deployment (counters are process-local); {@link RedisPartnerUsageRollupStore} is the
 * real-deployment store.
 */
@Service
@ConditionalOnProperty(name = "cia.partner-usage.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryPartnerUsageRollupStore implements PartnerUsageRollupStore {

    private record DateKey(String tenantId, String clientId, LocalDate date) {
    }

    private record Counters(AtomicLong total, AtomicLong success, AtomicLong clientError, AtomicLong serverError) {
        static Counters zero() {
            return new Counters(new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong());
        }
    }

    private final ConcurrentHashMap<DateKey, Counters> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LocalDate, Set<RollupKey>> keysByDate = new ConcurrentHashMap<>();

    @Override
    public void increment(String tenantId, String clientId, LocalDate date, StatusClass statusClass) {
        DateKey key = new DateKey(tenantId, clientId, date);
        Counters c = counters.computeIfAbsent(key, k -> Counters.zero());
        c.total().incrementAndGet();
        switch (statusClass) {
            case SUCCESS -> c.success().incrementAndGet();
            case CLIENT_ERROR -> c.clientError().incrementAndGet();
            case SERVER_ERROR -> c.serverError().incrementAndGet();
        }
        keysByDate.computeIfAbsent(date, d -> ConcurrentHashMap.newKeySet())
                .add(new RollupKey(tenantId, clientId));
    }

    @Override
    public DailyCounts snapshot(String tenantId, String clientId, LocalDate date) {
        Counters c = counters.get(new DateKey(tenantId, clientId, date));
        if (c == null) {
            return DailyCounts.ZERO;
        }
        return new DailyCounts(c.total().get(), c.success().get(), c.clientError().get(), c.serverError().get());
    }

    @Override
    public Set<RollupKey> keysForDate(LocalDate date) {
        Set<RollupKey> keys = keysByDate.get(date);
        return keys == null ? Set.of() : Collections.unmodifiableSet(keys);
    }
}
