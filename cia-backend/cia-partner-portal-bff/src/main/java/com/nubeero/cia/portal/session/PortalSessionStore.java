package com.nubeero.cia.portal.session;

import java.time.Duration;
import java.util.Optional;

/**
 * Server-side store for {@link PortalSession}s.
 *
 * <p>Two implementations, chosen at runtime by {@code cia.partner-portal.store}:
 * <ul>
 *   <li>{@link InMemoryPortalSessionStore} — {@code in-memory} (default) — dev / IT.</li>
 *   <li>{@link RedisPortalSessionStore} — {@code redis} — real deployments, backed by the shared
 *       {@code JedisPool} bean from {@code cia-partner-api}'s {@code RedisClientConfig}.</li>
 * </ul>
 *
 * <p><b>TTL model:</b> every session carries both an {@code idleExpiry} (sliding, extended by
 * {@link #touch(String)} on each authenticated request) and an {@code absoluteExpiry} (hard
 * cutoff, never extended). {@link #touch(String)} advances {@code idleExpiry} to
 * {@code now + IDLE_TTL}, capped so it never passes {@code absoluteExpiry}. {@link #get(String)}
 * treats a session past either cutoff as absent and evicts it.
 */
public interface PortalSessionStore {

    /** How far {@link #touch(String)} slides {@code idleExpiry} forward on each call. */
    Duration IDLE_TTL = Duration.ofMinutes(15);

    /**
     * Persists {@code session} (keyed by {@link PortalSession#id()}) and returns that id.
     *
     * <p>Callers construct the full {@link PortalSession} — including its opaque id — before
     * calling; the returned value simply echoes {@code session.id()} back for a fluent call site.
     */
    String create(PortalSession session);

    /**
     * Looks up the session by id. Returns {@link Optional#empty()} — and evicts the record — if
     * it does not exist or has passed either its idle or absolute expiry.
     */
    Optional<PortalSession> get(String id);

    /**
     * Slides the session's {@code idleExpiry} forward by {@link #IDLE_TTL}, capped at its
     * {@code absoluteExpiry}. A no-op if the session does not exist or is already expired
     * (evicting it in the latter case).
     */
    void touch(String id);

    /** Removes the session, if present. Always a no-op-safe delete — never throws on a miss. */
    void delete(String id);
}
