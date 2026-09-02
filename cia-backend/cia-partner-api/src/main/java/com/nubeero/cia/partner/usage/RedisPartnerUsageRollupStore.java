package com.nubeero.cia.partner.usage;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Redis-backed {@link PartnerUsageRollupStore} for real (multi-replica) deployments — reuses the
 * shared {@code JedisPool} bean from {@code cia-partner-api}'s {@code RedisClientConfig} (no
 * separate Redis client is added here), mirroring {@code RedisPortalSessionStore}.
 *
 * <h2>Shape</h2>
 * Each {@code (tenant, clientId, date)} is a Redis hash at {@code partner-usage:<tenant>:<clientId>:<date>}
 * with fields {@code total}/{@code success}/{@code clientError}/{@code serverError}, incremented
 * atomically via {@code HINCRBY}. A per-date set at {@code partner-usage:index:<date>} tracks every
 * {@code tenant::clientId} member that has recorded traffic that day — {@link #keysForDate} reads
 * this set rather than scanning Redis, so the daily flush cron never needs a {@code SCAN}.
 *
 * <p>Both the data hash and the index set carry a {@link #TTL_SECONDS} expiry (refreshed on every
 * write), so a day's counters self-clean a few days after the flush cron has already durably
 * persisted them into {@link PartnerRequestDaily} — no explicit delete-after-flush step needed.
 */
@Service
@ConditionalOnProperty(name = "cia.partner-usage.store", havingValue = "redis")
public class RedisPartnerUsageRollupStore implements PartnerUsageRollupStore {

    private static final String DATA_PREFIX = "partner-usage:";
    private static final String INDEX_PREFIX = "partner-usage:index:";
    private static final String MEMBER_SEPARATOR = "::";
    /** 3 days — comfortably past the 03:00 UTC flush cron for "yesterday". */
    private static final int TTL_SECONDS = 3 * 24 * 60 * 60;

    private final JedisPool jedisPool;

    public RedisPartnerUsageRollupStore(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public void increment(String tenantId, String clientId, LocalDate date, StatusClass statusClass) {
        String dataKey = dataKey(tenantId, clientId, date);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hincrBy(dataKey, "total", 1);
            jedis.hincrBy(dataKey, fieldFor(statusClass), 1);
            jedis.expire(dataKey, TTL_SECONDS);

            String indexKey = indexKey(date);
            jedis.sadd(indexKey, member(tenantId, clientId));
            jedis.expire(indexKey, TTL_SECONDS);
        }
    }

    @Override
    public DailyCounts snapshot(String tenantId, String clientId, LocalDate date) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> hash = jedis.hgetAll(dataKey(tenantId, clientId, date));
            if (hash.isEmpty()) {
                return DailyCounts.ZERO;
            }
            return new DailyCounts(
                    asLong(hash, "total"), asLong(hash, "success"),
                    asLong(hash, "clientError"), asLong(hash, "serverError"));
        }
    }

    @Override
    public Set<RollupKey> keysForDate(LocalDate date) {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> members = jedis.smembers(indexKey(date));
            Set<RollupKey> keys = new HashSet<>();
            for (String member : members) {
                int sep = member.indexOf(MEMBER_SEPARATOR);
                if (sep < 0) {
                    continue; // malformed member — skip rather than throw, flush cron is best-effort
                }
                keys.add(new RollupKey(member.substring(0, sep), member.substring(sep + MEMBER_SEPARATOR.length())));
            }
            return keys;
        }
    }

    private static long asLong(Map<String, String> hash, String field) {
        String value = hash.get(field);
        return value == null ? 0L : Long.parseLong(value);
    }

    private static String fieldFor(StatusClass statusClass) {
        return switch (statusClass) {
            case SUCCESS -> "success";
            case CLIENT_ERROR -> "clientError";
            case SERVER_ERROR -> "serverError";
        };
    }

    private static String dataKey(String tenantId, String clientId, LocalDate date) {
        return DATA_PREFIX + tenantId + ":" + clientId + ":" + date;
    }

    private static String indexKey(LocalDate date) {
        return INDEX_PREFIX + date;
    }

    private static String member(String tenantId, String clientId) {
        return tenantId + MEMBER_SEPARATOR + clientId;
    }
}
