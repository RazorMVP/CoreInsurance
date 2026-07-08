package com.nubeero.cia.reinsurance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RiFacInwardRepository extends JpaRepository<RiFacInward, UUID> {

    @Query("""
            SELECT f FROM RiFacInward f
            WHERE f.deletedAt IS NULL
              AND (:cedingCompanyId IS NULL OR f.cedingCompanyId = :cedingCompanyId)
              AND (:classId IS NULL OR f.classOfBusinessId = :classId)
              AND (:status IS NULL OR f.status = :status)
            """)
    Page<RiFacInward> findAll(
            @Param("cedingCompanyId") UUID cedingCompanyId,
            @Param("classId") UUID classOfBusinessId,
            @Param("status") RiFacInwardStatus status,
            Pageable pageable);

    Optional<RiFacInward> findByIdAndDeletedAtIsNull(UUID id);
}
