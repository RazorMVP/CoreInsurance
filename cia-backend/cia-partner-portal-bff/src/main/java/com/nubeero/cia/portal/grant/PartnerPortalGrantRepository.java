package com.nubeero.cia.portal.grant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartnerPortalGrantRepository extends JpaRepository<PartnerPortalGrant, UUID> {

    List<PartnerPortalGrant> findByPartnerUserIdAndDeletedAtIsNull(UUID partnerUserId);

    Optional<PartnerPortalGrant> findByPartnerUserIdAndPartnerAppIdAndDeletedAtIsNull(
            UUID partnerUserId, UUID partnerAppId);

    List<PartnerPortalGrant> findByPartnerAppIdAndDeletedAtIsNull(UUID partnerAppId);
}
