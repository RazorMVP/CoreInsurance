package com.nubeero.cia.claims;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClaimInspectionRepository extends JpaRepository<ClaimInspection, UUID> {

    Optional<ClaimInspection> findByClaimIdAndDeletedAtIsNull(UUID claimId);
}
