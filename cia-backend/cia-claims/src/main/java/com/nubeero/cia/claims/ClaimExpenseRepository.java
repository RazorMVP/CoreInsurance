package com.nubeero.cia.claims;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClaimExpenseRepository extends JpaRepository<ClaimExpense, UUID> {

    Optional<ClaimExpense> findByIdAndDeletedAtIsNull(UUID id);

    Page<ClaimExpense> findAllByClaim_IdAndDeletedAtIsNull(UUID claimId, Pageable pageable);
}
