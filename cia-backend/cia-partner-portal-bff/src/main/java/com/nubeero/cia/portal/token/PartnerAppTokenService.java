package com.nubeero.cia.portal.token;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Mints a partner-app-scoped access token so the Partner Portal BFF can call
 * {@code /partner/v1/**} AS the partner app it is fronting for a logged-in
 * partner-developer user.
 *
 * <p>{@link #tokenFor} fetches the app's {@code client_secret} from Keycloak
 * just-in-time via {@link PartnerClientSecretResolver} (never persisted by
 * us), performs the OAuth2 client-credentials grant against the app's
 * TENANT realm via {@link ClientCredentialsTokenGrantor}, and caches the
 * result per {@code (tenantRealm, clientId)}.
 *
 * <p>The cache mirrors the lazy per-realm decoder cache in {@code
 * TenantIssuerJwtAuthenticationManagerResolver} (cia-auth) — a plain {@link
 * ConcurrentHashMap} built lazily on first use — but is additionally
 * expiry-aware: a cached token is reused only while more than {@link
 * #SAFETY_MARGIN} remains before its {@code exp}; once inside that margin
 * (or past it) the next call re-fetches the secret and re-mints.
 *
 * <p><b>Security invariant:</b> the {@code client_secret} is fetched, used
 * for exactly one grant call, and discarded — it is never stored on this
 * instance, never returned to any caller, and never logged. {@link
 * MintedToken} carries only the access token + its expiry.
 */
@Slf4j
@Service
public class PartnerAppTokenService {

    /** Re-mint once fewer than this much time remains before the cached token's exp. */
    static final Duration SAFETY_MARGIN = Duration.ofSeconds(30);

    private final PartnerClientSecretResolver secretResolver;
    private final ClientCredentialsTokenGrantor tokenGrantor;
    private final Clock clock;
    private final ConcurrentHashMap<String, MintedToken> cache = new ConcurrentHashMap<>();

    public PartnerAppTokenService(PartnerClientSecretResolver secretResolver,
                                   ClientCredentialsTokenGrantor tokenGrantor,
                                   Clock clock) {
        this.secretResolver = secretResolver;
        this.tokenGrantor = tokenGrantor;
        this.clock = clock;
    }

    /**
     * Returns a valid access token for the given partner app, reusing a
     * cached one when it still has more than {@link #SAFETY_MARGIN} left
     * before expiry, otherwise minting a fresh one.
     */
    public MintedToken tokenFor(String tenantRealm, String clientId) {
        String key = cacheKey(tenantRealm, clientId);

        // Fast path: avoid the compute() lock entirely when the cached entry is
        // still fresh — the common case once the cache is warm.
        MintedToken cached = cache.get(key);
        if (isFresh(cached)) {
            return cached;
        }

        return cache.compute(key, (k, existing) ->
                isFresh(existing) ? existing : mint(tenantRealm, clientId));
    }

    /**
     * Drops any cached token for {@code (tenantRealm, clientId)} — called after a secret rotation
     * (Task 8's {@code POST /portal/apps/{id}/credentials/rotate}) so the NEXT call to {@link
     * #tokenFor} is forced to re-mint against the new secret rather than keep serving a token
     * minted under the secret that was just invalidated. A no-op if nothing is cached.
     */
    public void evict(String tenantRealm, String clientId) {
        cache.remove(cacheKey(tenantRealm, clientId));
    }

    private MintedToken mint(String tenantRealm, String clientId) {
        log.debug("Minting partner-app access token for tenant realm '{}', client '{}'",
                tenantRealm, clientId);
        String secret = secretResolver.resolveSecret(tenantRealm, clientId);
        return tokenGrantor.grant(tenantRealm, clientId, secret);
    }

    private boolean isFresh(MintedToken token) {
        return token != null && clock.instant().isBefore(token.expiry().minus(SAFETY_MARGIN));
    }

    private static String cacheKey(String tenantRealm, String clientId) {
        return tenantRealm + "::" + clientId;
    }
}
