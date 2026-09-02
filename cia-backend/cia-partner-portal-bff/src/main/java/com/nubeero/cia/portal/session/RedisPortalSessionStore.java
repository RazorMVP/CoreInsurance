package com.nubeero.cia.portal.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Redis-backed {@link PortalSessionStore} for real deployments — reuses the shared
 * {@code JedisPool} bean from {@code cia-partner-api}'s {@code RedisClientConfig} (no separate
 * Redis client is added here).
 *
 * <p>Each session is a single JSON-serialized value at key {@code portal-session:<id>}. Both TTLs
 * on {@link PortalSession} collapse to one Redis key TTL: on write, the key's expiry is set to
 * whichever of {@code idleExpiry} / {@code absoluteExpiry} is sooner, so Redis itself evicts a
 * session the moment either cutoff passes — no separate reaper needed. {@link #touch(String)}
 * re-reads, slides {@code idleExpiry} forward by {@link #IDLE_TTL} (capped at
 * {@code absoluteExpiry}), and re-writes with a freshly computed TTL. {@link #get(String)} also
 * defensively re-checks both cutoffs against the stored value and evicts on a stale hit, guarding
 * against clock-skew edges around the millisecond the Redis TTL fires.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "cia.partner-portal.store", havingValue = "redis")
public class RedisPortalSessionStore implements PortalSessionStore {

    private static final String KEY_PREFIX = "portal-session:";

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    public RedisPortalSessionStore(JedisPool jedisPool, ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
    }

    @Override
    public String create(PortalSession session) {
        writeWithTtl(session);
        return session.id();
    }

    @Override
    public Optional<PortalSession> get(String id) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(key(id));
            if (json == null) {
                return Optional.empty();
            }
            PortalSession session = readJson(json);
            if (isExpired(session)) {
                jedis.del(key(id));
                return Optional.empty();
            }
            return Optional.of(session);
        }
    }

    @Override
    public void touch(String id) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(key(id));
            if (json == null) {
                return;
            }
            PortalSession session = readJson(json);
            if (isExpired(session)) {
                jedis.del(key(id));
                return;
            }
            writeWithTtl(jedis, slideIdleExpiry(session));
        }
    }

    @Override
    public void delete(String id) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key(id));
        }
    }

    private void writeWithTtl(PortalSession session) {
        try (Jedis jedis = jedisPool.getResource()) {
            writeWithTtl(jedis, session);
        }
    }

    private void writeWithTtl(Jedis jedis, PortalSession session) {
        long ttlMillis = ttlMillis(session);
        if (ttlMillis <= 0) {
            // Already past its cutoff — don't persist a session that would be immediately
            // unreadable; also clears out any prior value under this id.
            jedis.del(key(session.id()));
            return;
        }
        jedis.psetex(key(session.id()), ttlMillis, writeJson(session));
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

    private static long ttlMillis(PortalSession session) {
        Instant cutoff = session.idleExpiry().isAfter(session.absoluteExpiry())
                ? session.absoluteExpiry() : session.idleExpiry();
        return Duration.between(Instant.now(), cutoff).toMillis();
    }

    private static boolean isExpired(PortalSession session) {
        Instant now = Instant.now();
        return now.isAfter(session.absoluteExpiry()) || now.isAfter(session.idleExpiry());
    }

    private static String key(String id) {
        return KEY_PREFIX + id;
    }

    private String writeJson(PortalSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize PortalSession " + session.id(), e);
        }
    }

    private PortalSession readJson(String json) {
        try {
            return objectMapper.readValue(json, PortalSession.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize PortalSession", e);
        }
    }
}
