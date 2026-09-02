package com.nubeero.cia.portal.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link PortalLoginStateStore} — the dev/IT default, mirroring {@code
 * InMemoryPortalSessionStore}'s conditional. Not suitable for a multi-replica deployment (state is
 * process-local — a login started on one pod would fail its callback on another); {@link
 * RedisPortalLoginStateStore} is the real-deployment store.
 */
@Service
@ConditionalOnProperty(name = "cia.partner-portal.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryPortalLoginStateStore implements PortalLoginStateStore {

    private record Entry(String codeVerifier, Instant expiry) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void save(String state, String codeVerifier) {
        entries.put(state, new Entry(codeVerifier, Instant.now().plus(TTL)));
    }

    @Override
    public Optional<String> consume(String state) {
        Entry entry = entries.remove(state);
        if (entry == null || Instant.now().isAfter(entry.expiry())) {
            return Optional.empty();
        }
        return Optional.of(entry.codeVerifier());
    }
}
