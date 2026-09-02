package com.nubeero.cia.portal.auth.dto;

import com.nubeero.cia.portal.developer.dto.PartnerDeveloperGrantResponse;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /portal/auth/me} response body — profile + the Partner Apps this developer is
 * granted on. <strong>Never</strong> carries {@code accessToken}/{@code refreshToken} — the whole
 * point of the token-handler pattern is that the browser never sees them.
 *
 * <p>Reuses {@link PartnerDeveloperGrantResponse} for the apps list rather than a bespoke DTO — it
 * already carries exactly what a grant row needs to display.
 */
public record PortalMeResponse(
        UUID partnerUserId,
        String email,
        String csrfToken,
        List<PartnerDeveloperGrantResponse> apps) {
}
