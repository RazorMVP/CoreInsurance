package com.nubeero.cia.partner.usage;

import java.time.LocalDate;
import java.util.Set;

/**
 * Live per-day request counters for the partner API — the fast-write side of the request
 * telemetry pipeline. {@link com.nubeero.cia.partner.config.PartnerRequestMetricsFilter}
 * increments one of these on every {@code /partner/v1/**} response; {@code
 * PartnerUsageRollupFlushService}'s daily 03:00 UTC cron drains a finished day's counters into
 * the durable {@link PartnerRequestDaily} table; {@code PortalUsageService}
 * (cia-partner-portal-bff) reads {@link #snapshot} directly for "today" (never yet flushed).
 *
 * <p>Two implementations, chosen at runtime by {@code cia.partner-usage.store} — mirrors the
 * Task-4 {@code PortalSessionStore} toggle:
 * <ul>
 *   <li>{@link InMemoryPartnerUsageRollupStore} — {@code in-memory} (default) — dev / IT.</li>
 *   <li>{@link RedisPartnerUsageRollupStore} — {@code redis} — real deployments, backed by the
 *       shared {@code JedisPool} bean from {@code cia-partner-api}'s {@code RedisClientConfig}.</li>
 * </ul>
 *
 * <p>Keyed by {@code (tenantId, clientId, date)} — a client_id is only unique within its tenant
 * realm (same lesson as {@code PartnerRateLimitService}), and "date" is the UTC calendar day the
 * request landed on (never the server's local zone — see {@link #today()}).
 */
public interface PartnerUsageRollupStore {

    /** Record one request's outcome for {@code (tenantId, clientId, date)}. Always atomic. */
    void increment(String tenantId, String clientId, LocalDate date, StatusClass statusClass);

    /** The current counters for {@code (tenantId, clientId, date)} — all-zero if nothing recorded. */
    DailyCounts snapshot(String tenantId, String clientId, LocalDate date);

    /**
     * Every {@code (tenantId, clientId)} pair with at least one recorded request on {@code date} —
     * the enumeration the daily flush cron walks to know what to upsert into {@link
     * PartnerRequestDaily}. An empty set is a legitimate "no traffic that day" result, not an error.
     */
    Set<RollupKey> keysForDate(LocalDate date);

    /** The UTC calendar day every store implementation buckets counters by. */
    static LocalDate today() {
        return LocalDate.now(java.time.ZoneOffset.UTC);
    }

    /** Identifies one app's traffic within one tenant — {@code clientId} is tenant-scoped, not global. */
    record RollupKey(String tenantId, String clientId) {
    }

    /** Immutable snapshot of one day's counters. {@link #ZERO} is the "nothing recorded" value. */
    record DailyCounts(long total, long success, long clientError, long serverError) {
        public static final DailyCounts ZERO = new DailyCounts(0, 0, 0, 0);
    }

    /** The three buckets an HTTP response status is classified into. */
    enum StatusClass {
        SUCCESS, CLIENT_ERROR, SERVER_ERROR;

        /** {@code < 400} → SUCCESS (incl. redirects), {@code 400-499} → CLIENT_ERROR, else SERVER_ERROR. */
        public static StatusClass fromHttpStatus(int status) {
            if (status >= 500) {
                return SERVER_ERROR;
            }
            if (status >= 400) {
                return CLIENT_ERROR;
            }
            return SUCCESS;
        }
    }
}
