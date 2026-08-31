package com.nubeero.cia.portal.developer.dto;

import com.nubeero.cia.portal.grant.GrantRole;
import com.nubeero.cia.portal.grant.PartnerPortalGrant;

import java.time.Instant;
import java.util.UUID;

/** Response shape for a single {@link PartnerPortalGrant} row, returned by invite/list. */
public record PartnerDeveloperGrantResponse(
        UUID id,
        UUID partnerAppId,
        UUID partnerUserId,
        String email,
        GrantRole role,
        Instant createdAt
) {

    public static PartnerDeveloperGrantResponse from(PartnerPortalGrant grant) {
        return new PartnerDeveloperGrantResponse(
                grant.getId(),
                grant.getPartnerAppId(),
                grant.getPartnerUserId(),
                grant.getPartnerUserEmail(),
                grant.getRole(),
                grant.getCreatedAt());
    }
}
