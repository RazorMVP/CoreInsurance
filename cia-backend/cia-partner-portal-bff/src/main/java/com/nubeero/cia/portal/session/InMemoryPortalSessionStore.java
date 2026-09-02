package com.nubeero.cia.portal.session;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link PortalSessionStore} — the dev/IT default so tests need no Redis unless they
 * opt in via {@code cia.partner-portal.store=redis}. Not suitable for a multi-replica deployment
 * (sessions are process-local); {@link RedisPortalSessionStore} is the real-deployment store.
 */
@Service
@ConditionalOnProperty(name = "cia.partner-portal.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryPortalSessionStore implements PortalSessionStore {

    private final ConcurrentHashMap<String, PortalSession> sessions = new ConcurrentHashMap<>();

    @Override
    public String create(PortalSession session) {
        sessions.put(session.id(), session);
        return session.id();
    }

    @Override
    public Optional<PortalSession> get(String id) {
        PortalSession session = sessions.get(id);
        if (session == null) {
            return Optional.empty();
        }
        if (isExpired(session)) {
            sessions.remove(id, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    @Override
    public void touch(String id) {
        sessions.computeIfPresent(id, (key, session) -> isExpired(session) ? null : slideIdleExpiry(session));
    }

    @Override
    public void delete(String id) {
        sessions.remove(id);
    }

    private static PortalSession slideIdleExpiry(PortalSession session) {
        Instant candidate = Instant.now().plus(IDLE_TTL);
        Instant newIdleExpiry = candidate.isAfter(session.absoluteExpiry()) ? session.absoluteExpiry() : candidate;
        return new PortalSession(
                session.id(),
                session.partnerUserId(),
                session.email(),
                session.displayName(),
                session.accessToken(),
                session.refreshToken(),
                session.absoluteExpiry(),
                newIdleExpiry,
                session.csrfToken());
    }

    private static boolean isExpired(PortalSession session) {
        Instant now = Instant.now();
        return now.isAfter(session.absoluteExpiry()) || now.isAfter(session.idleExpiry());
    }
}
