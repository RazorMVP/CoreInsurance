package com.nubeero.cia.partner.usage;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerRequestDailyRepository extends JpaRepository<PartnerRequestDaily, UUID> {

    Optional<PartnerRequestDaily> findByPartnerAppIdAndUsageDate(UUID partnerAppId, LocalDate usageDate);

    /** Most-recent-first history, capped by the caller via {@code PageRequest.of(0, limit)}. */
    List<PartnerRequestDaily> findByPartnerAppIdOrderByUsageDateDesc(UUID partnerAppId, Pageable pageable);
}
