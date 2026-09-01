package com.nubeero.cia.portal.token;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PartnerAppTokenService} — the JIT partner-app token
 * minter. The Keycloak-admin secret fetch ({@link PartnerClientSecretResolver})
 * and the client-credentials grant ({@link ClientCredentialsTokenGrantor})
 * are both mocked seams so no real Keycloak is involved.
 *
 * <p>Also proves the security invariant from the brief: the {@code
 * client_secret} returned by the mocked resolver never appears as a field
 * on the {@link MintedToken} the service hands back.
 */
@ExtendWith(MockitoExtension.class)
class PartnerAppTokenServiceTest {

    private static final String TENANT_REALM = "tenant_acme";
    private static final String CLIENT_ID = "insurtech-app-xyz";
    private static final String SECRET = "s3cr3t-should-never-leak";

    @Mock private PartnerClientSecretResolver secretResolver;
    @Mock private ClientCredentialsTokenGrantor tokenGrantor;

    /** Mutable clock so tests can advance time past the cached token's expiry. */
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-27T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return now.get(); }
    };

    private PartnerAppTokenService service() {
        return new PartnerAppTokenService(secretResolver, tokenGrantor, clock);
    }

    private MintedToken tokenExpiringIn(Duration ttl) {
        return new MintedToken("access-token-" + ttl, now.get().plus(ttl));
    }

    @Test
    void tokenFor_returnsAMintedToken() {
        when(secretResolver.resolveSecret(TENANT_REALM, CLIENT_ID)).thenReturn(SECRET);
        when(tokenGrantor.grant(eq(TENANT_REALM), eq(CLIENT_ID), eq(SECRET)))
                .thenReturn(tokenExpiringIn(Duration.ofMinutes(5)));

        MintedToken result = service().tokenFor(TENANT_REALM, CLIENT_ID);

        assertThat(result).isNotNull();
        assertThat(result.accessToken()).isNotBlank();
    }

    @Test
    void tokenFor_secondCallWithinCacheWindow_doesNotRefetchSecretOrReHitTokenEndpoint() {
        when(secretResolver.resolveSecret(TENANT_REALM, CLIENT_ID)).thenReturn(SECRET);
        when(tokenGrantor.grant(eq(TENANT_REALM), eq(CLIENT_ID), eq(SECRET)))
                .thenReturn(tokenExpiringIn(Duration.ofMinutes(5)));

        PartnerAppTokenService service = service();
        MintedToken first = service.tokenFor(TENANT_REALM, CLIENT_ID);

        // Well within the cache window (5 min TTL, 30s safety margin) — no time advance.
        MintedToken second = service.tokenFor(TENANT_REALM, CLIENT_ID);

        assertThat(second).isEqualTo(first);
        verify(secretResolver, times(1)).resolveSecret(TENANT_REALM, CLIENT_ID);
        verify(tokenGrantor, times(1)).grant(anyString(), anyString(), anyString());
    }

    @Test
    void tokenFor_afterExpiry_reMintsAndRefetchesTheSecret() {
        when(secretResolver.resolveSecret(TENANT_REALM, CLIENT_ID)).thenReturn(SECRET);
        when(tokenGrantor.grant(eq(TENANT_REALM), eq(CLIENT_ID), eq(SECRET)))
                .thenReturn(new MintedToken("access-token-1", now.get().plus(Duration.ofMinutes(5))))
                .thenReturn(new MintedToken("access-token-2", now.get().plus(Duration.ofMinutes(11))));

        PartnerAppTokenService service = service();
        MintedToken first = service.tokenFor(TENANT_REALM, CLIENT_ID);

        // Advance past expiry (5min TTL + 30s safety margin).
        now.set(now.get().plus(Duration.ofMinutes(6)));

        MintedToken second = service.tokenFor(TENANT_REALM, CLIENT_ID);

        assertThat(second).isNotEqualTo(first);
        assertThat(second.accessToken()).isEqualTo("access-token-2");
        verify(secretResolver, times(2)).resolveSecret(TENANT_REALM, CLIENT_ID);
        verify(tokenGrantor, times(2)).grant(anyString(), anyString(), anyString());
    }

    @Test
    void tokenFor_withinSafetyMargin_treatsTokenAsStaleAndReMints() {
        when(secretResolver.resolveSecret(TENANT_REALM, CLIENT_ID)).thenReturn(SECRET);
        // Expires in 20s — inside the 30s safety margin, so the very first mint already
        // needs a follow-up re-mint on the next call even though "now" hasn't reached exp.
        when(tokenGrantor.grant(eq(TENANT_REALM), eq(CLIENT_ID), eq(SECRET)))
                .thenReturn(tokenExpiringIn(Duration.ofSeconds(20)))
                .thenReturn(tokenExpiringIn(Duration.ofMinutes(5)));

        PartnerAppTokenService service = service();
        service.tokenFor(TENANT_REALM, CLIENT_ID);
        MintedToken second = service.tokenFor(TENANT_REALM, CLIENT_ID);

        assertThat(second.expiry()).isAfter(now.get().plus(Duration.ofMinutes(1)));
        verify(secretResolver, times(2)).resolveSecret(TENANT_REALM, CLIENT_ID);
        verify(tokenGrantor, times(2)).grant(anyString(), anyString(), anyString());
    }

    @Test
    void tokenFor_differentClientIds_areCachedIndependently() {
        String otherClientId = "insurtech-app-other";
        when(secretResolver.resolveSecret(anyString(), anyString())).thenReturn(SECRET);
        when(tokenGrantor.grant(anyString(), anyString(), anyString()))
                .thenReturn(tokenExpiringIn(Duration.ofMinutes(5)));

        PartnerAppTokenService service = service();
        service.tokenFor(TENANT_REALM, CLIENT_ID);
        service.tokenFor(TENANT_REALM, otherClientId);

        verify(secretResolver, times(1)).resolveSecret(TENANT_REALM, CLIENT_ID);
        verify(secretResolver, times(1)).resolveSecret(TENANT_REALM, otherClientId);
        verify(tokenGrantor, times(2)).grant(anyString(), anyString(), anyString());
    }

    @Test
    void mintedToken_neverCarriesTheClientSecret() {
        when(secretResolver.resolveSecret(TENANT_REALM, CLIENT_ID)).thenReturn(SECRET);
        when(tokenGrantor.grant(eq(TENANT_REALM), eq(CLIENT_ID), eq(SECRET)))
                .thenReturn(tokenExpiringIn(Duration.ofMinutes(5)));

        MintedToken result = service().tokenFor(TENANT_REALM, CLIENT_ID);

        // MintedToken has exactly two components — accessToken and expiry — and
        // neither equals (nor contains) the secret the mocked resolver handed back.
        assertThat(result.accessToken()).doesNotContain(SECRET);
        assertThat(result.toString()).doesNotContain(SECRET);
        assertThat(MintedToken.class.getRecordComponents()).hasSize(2);
    }

    @Test
    void grant_neverInvokedWithoutFirstResolvingTheSecretForThatCall() {
        when(secretResolver.resolveSecret(TENANT_REALM, CLIENT_ID)).thenReturn(SECRET);
        when(tokenGrantor.grant(eq(TENANT_REALM), eq(CLIENT_ID), eq(SECRET)))
                .thenReturn(tokenExpiringIn(Duration.ofMinutes(5)));

        service().tokenFor(TENANT_REALM, CLIENT_ID);

        verify(tokenGrantor, never()).grant(anyString(), anyString(), eq((String) null));
        verify(tokenGrantor).grant(TENANT_REALM, CLIENT_ID, SECRET);
    }

    @Test
    void evict_forcesANextCallToReMint_evenWellWithinTheOldTokensCacheWindow() {
        when(secretResolver.resolveSecret(TENANT_REALM, CLIENT_ID)).thenReturn(SECRET);
        when(tokenGrantor.grant(eq(TENANT_REALM), eq(CLIENT_ID), eq(SECRET)))
                .thenReturn(new MintedToken("access-token-pre-rotate", now.get().plus(Duration.ofMinutes(5))))
                .thenReturn(new MintedToken("access-token-post-rotate", now.get().plus(Duration.ofMinutes(5))));

        PartnerAppTokenService service = service();
        MintedToken before = service.tokenFor(TENANT_REALM, CLIENT_ID);

        service.evict(TENANT_REALM, CLIENT_ID);

        // No time advance — without the evict, this would still be well inside the cache window.
        MintedToken after = service.tokenFor(TENANT_REALM, CLIENT_ID);

        assertThat(after.accessToken()).isNotEqualTo(before.accessToken());
        assertThat(after.accessToken()).isEqualTo("access-token-post-rotate");
        verify(secretResolver, times(2)).resolveSecret(TENANT_REALM, CLIENT_ID);
        verify(tokenGrantor, times(2)).grant(anyString(), anyString(), anyString());
    }

    @Test
    void evict_ofAnUncachedKey_isANoOp() {
        // No stubbing needed — evicting something never cached must not throw or interact with
        // either seam.
        service().evict(TENANT_REALM, CLIENT_ID);
    }
}
