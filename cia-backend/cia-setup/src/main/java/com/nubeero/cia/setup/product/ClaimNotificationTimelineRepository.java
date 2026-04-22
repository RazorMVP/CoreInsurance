package com.nubeero.cia.setup.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClaimNotificationTimelineRepository extends JpaRepository<ClaimNotificationTimeline, UUID> {

    Optional<ClaimNotificationTimeline> findByProductIdAndDeletedAtIsNull(UUID productId);
}
