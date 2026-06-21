package com.nubeero.cia.partner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nubeero.cia.partner.app.PartnerApp;
import com.nubeero.cia.partner.app.PartnerAppRepository;
import com.nubeero.cia.partner.config.PartnerRateLimitService.RateLimitVerdict;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PartnerRateLimitService} — the per-client token-bucket
 * limiter that replaced the single global {@code bucket4j} bucket. Verifies that
 * each client's bucket is sized to its {@code PartnerApp.rateLimitRpm}, that
 * clients are isolated, that an unknown client falls back to the default rpm, and
 * that {@link PartnerRateLimitService#evict} re-sizes a bucket on a tier change.
 *
 * <p>Pure unit test (real bucket4j buckets, mocked repo) — no Spring, no DB. Part
 * of the {@code partner-ratelimit-per-client} backlog slice.
 */
@ExtendWith(MockitoExtension.class)
class PartnerRateLimitServiceTest {

    @Mock PartnerAppRepository repo;

    private PartnerRateLimitService service() {
        PartnerRateLimitProperties props = new PartnerRateLimitProperties();
        props.setDefaultRpm(60);
        return new PartnerRateLimitService(repo, props);
    }

    private static PartnerApp app(int rpm, boolean active) {
        return PartnerApp.builder().clientId("x").appName("x").contactEmail("x@x")
                .rateLimitRpm(rpm).active(active).build();
    }

    @Test
    void capacityComesFromRateLimitRpm_thirdRequestDeniedWhenRpmIsTwo() {
        when(repo.findByClientId(eq("c-a"))).thenReturn(Optional.of(app(2, true)));
        PartnerRateLimitService svc = service();

        RateLimitVerdict first = svc.tryConsume("c-a");
        RateLimitVerdict second = svc.tryConsume("c-a");
        RateLimitVerdict third = svc.tryConsume("c-a");

        assertThat(first.allowed()).isTrue();
        assertThat(first.limit()).isEqualTo(2);
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isEqualTo(0);
        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void clientsAreIsolated_oneExhaustedDoesNotAffectAnother() {
        when(repo.findByClientId(eq("c-a"))).thenReturn(Optional.of(app(1, true)));
        when(repo.findByClientId(eq("c-b"))).thenReturn(Optional.of(app(1, true)));
        PartnerRateLimitService svc = service();

        assertThat(svc.tryConsume("c-a").allowed()).isTrue();   // c-a exhausts its 1 token
        assertThat(svc.tryConsume("c-a").allowed()).isFalse();

        // c-b is untouched — its own bucket still has its token.
        assertThat(svc.tryConsume("c-b").allowed()).isTrue();
    }

    @Test
    void unknownClient_fallsBackToDefaultRpm() {
        when(repo.findByClientId(eq("ghost"))).thenReturn(Optional.empty());
        PartnerRateLimitService svc = service();

        RateLimitVerdict v = svc.tryConsume("ghost");
        assertThat(v.allowed()).isTrue();
        assertThat(v.limit()).isEqualTo(60); // default-rpm
    }

    @Test
    void inactivePartner_fallsBackToDefaultRpm() {
        when(repo.findByClientId(eq("dormant"))).thenReturn(Optional.of(app(5, false)));
        PartnerRateLimitService svc = service();

        assertThat(svc.tryConsume("dormant").limit()).isEqualTo(60);
    }

    @Test
    void evict_rebuildsBucketWithNewRpm() {
        // First resolution sees rpm=2; after evict the next resolution sees rpm=5.
        when(repo.findByClientId(eq("c-a")))
                .thenReturn(Optional.of(app(2, true)))
                .thenReturn(Optional.of(app(5, true)));
        PartnerRateLimitService svc = service();

        svc.tryConsume("c-a");
        svc.tryConsume("c-a");
        assertThat(svc.tryConsume("c-a").allowed()).isFalse(); // rpm=2 exhausted

        svc.evict("c-a");

        RateLimitVerdict afterEvict = svc.tryConsume("c-a");
        assertThat(afterEvict.allowed()).isTrue();
        assertThat(afterEvict.limit()).isEqualTo(5);           // rebuilt at the new tier
    }
}
