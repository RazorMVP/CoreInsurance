package com.nubeero.cia.api.partnerportal;

import com.nubeero.cia.portal.auth.PortalAuthController;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5 fix round 2 — pins the invariant that let the {@code redirect_uri} mismatch land twice:
 * the value {@link PartnerPortalBootstrapProperties#getRedirectUris()} registers on the
 * {@code cia-partner-portal} Keycloak client MUST equal what {@link PortalAuthController}
 * actually sends as {@code redirect_uri} at authorize/token time — the BFF's own {@code
 * /portal/auth/callback} endpoint, never the SPA origin. Both sides now reference the same
 * {@link PortalAuthController#CALLBACK_PATH} literal, so a future edit to the callback path
 * without updating this default fails this test instead of silently drifting again.
 *
 * <p>The host/port half of the default (dev backend port 8090) isn't independently derivable from
 * a constant — it mirrors {@code cia-api}'s {@code application.yml} {@code server.port} default —
 * so this test asserts against that literal directly rather than pretending to verify it from
 * first principles; a port change is a one-line, deliberately-visible diff in this test file.
 */
class PartnerPortalBootstrapPropertiesRedirectUriTest {

    @Test
    void redirectUris_defaultMatchesBffCallbackUrl_notSpaOrigin() {
        var props = new PartnerPortalBootstrapProperties();

        assertThat(props.getRedirectUris())
                .as("PartnerPortalBootstrapProperties.redirectUris must equal exactly what "
                        + "PortalAuthController sends as redirect_uri — Keycloak rejects a "
                        + "token/authorize call whose redirect_uri doesn't exactly match a "
                        + "registered one")
                .containsExactly("http://localhost:8090" + PortalAuthController.CALLBACK_PATH);
    }
}
