package com.nubeero.cia.setup.loss;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ClaimReserveCategoryRepository extends JpaRepository<ClaimReserveCategory, UUID> {

    Page<ClaimReserveCategory> findAllByDeletedAtIsNull(Pageable pageable);
}
