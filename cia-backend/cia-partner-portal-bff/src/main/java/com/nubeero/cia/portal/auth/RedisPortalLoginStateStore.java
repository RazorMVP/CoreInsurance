package com.nubeero.cia.portal.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;

/**
 * Redis-backed {@link PortalLoginStateStore} for real (possibly multi-replica) deployments —
 * reuses the shared {@code JedisPool} bean from {@code cia-partner-api}'s {@code
 * RedisClientConfig}, mirroring {@code RedisPortalSessionStore}.
 *
 * <p>{@code GETDEL} makes {@link #consume(String)} atomic — no separate read-then-delete race
 * where a replayed callback could win a lookup between the check and the delete.
 */
@Service
@ConditionalOnProperty(name = "cia.partner-portal.store", havingValue = "redis")
public class RedisPortalLoginStateStore implements PortalLoginStateStore {

    private static final String KEY_PREFIX = "portal-login-state:";

    private final JedisPool jedisPool;

    public RedisPortalLoginStateStore(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public void save(String state, String codeVerifier) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.psetex(key(state), TTL.toMillis(), codeVerifier);
        }
    }

    @Override
    public Optional<String> consume(String state) {
        try (Jedis jedis = jedisPool.getResource()) {
            return Optional.ofNullable(jedis.getDel(key(state)));
        }
    }

    private static String key(String state) {
        return KEY_PREFIX + state;
    }
}
