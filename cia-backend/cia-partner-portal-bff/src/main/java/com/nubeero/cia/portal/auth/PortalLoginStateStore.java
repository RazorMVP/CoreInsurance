package com.nubeero.cia.portal.auth;

import java.time.Duration;
import java.util.Optional;

/**
 * Server-side, single-use store for the PKCE {@code code_verifier} generated at
 * {@code /portal/auth/login}, keyed by the anti-CSRF {@code state} value.
 *
 * <p><b>The verifier never leaves the server.</b> (Fix round 1 — SPEC gap.) The browser is handed
 * only the {@code state} value, in the short-lived {@code cia_portal_login_state} cookie — that
 * cookie exists purely to bind the OAuth {@code state} round-trip to the browser that started the
 * flow (classic login-CSRF mitigation: {@code /callback} checks the cookie's {@code state} against
 * the query-string {@code state} Keycloak echoes back). The verifier itself — the PKCE secret that
 * actually authorises the code exchange — is looked up here by {@code state} and never travels
 * through the browser at all.
 *
 * <p>Two implementations, chosen at runtime by {@code cia.partner-portal.store} — the same
 * property {@link com.nubeero.cia.portal.session.PortalSessionStore} uses, since both stores are
 * the same kind of ephemeral BFF-local state and should move together:
 * <ul>
 *   <li>{@link InMemoryPortalLoginStateStore} — {@code in-memory} (default) — dev / IT.</li>
 *   <li>{@link RedisPortalLoginStateStore} — {@code redis} — real (possibly multi-replica)
 *       deployments, backed by the shared {@code JedisPool} bean from {@code cia-partner-api}'s
 *       {@code RedisClientConfig}.</li>
 * </ul>
 */
public interface PortalLoginStateStore {

    /** How long an unclaimed {@code (state, verifier)} pair survives before it's unusable. */
    Duration TTL = Duration.ofMinutes(10);

    /** Persists {@code codeVerifier} under {@code state}, replacing any prior entry for it. */
    void save(String state, String codeVerifier);

    /**
     * Atomically retrieves and deletes the verifier stored under {@code state} — single-use, so a
     * replayed {@code state} (e.g. the callback URL revisited, or an authorization-code-injection
     * attempt) always misses on the second attempt. Returns {@link Optional#empty()} if the state
     * is unknown, already consumed, or past {@link #TTL}.
     */
    Optional<String> consume(String state);
}
