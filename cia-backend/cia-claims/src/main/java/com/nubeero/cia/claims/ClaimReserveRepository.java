package com.nubeero.cia.claims;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ClaimReserveRepository extends JpaRepository<ClaimReserve, UUID> {

    Page<ClaimReserve> findAllByClaim_IdAndDeletedAtIsNull(UUID claimId, Pageable pageable);
}
