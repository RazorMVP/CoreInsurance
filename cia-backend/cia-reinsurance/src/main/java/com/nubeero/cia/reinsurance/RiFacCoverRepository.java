package com.nubeero.cia.reinsurance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RiFacCoverRepository extends JpaRepository<RiFacCover, UUID> {

    @Query("""
            SELECT f FROM RiFacCover f
            WHERE f.deletedAt IS NULL
              AND (:policyId IS NULL OR f.policyId = :policyId)
              AND (:status IS NULL OR f.status = :status)
              AND (:riCoId IS NULL OR f.reinsuranceCompanyId = :riCoId)
            """)
    Page<RiFacCover> findAll(
            @Param("policyId") UUID policyId,
            @Param("status") FacCoverStatus status,
            @Param("riCoId") UUID reinsuranceCompanyId,
            Pageable pageable);

    Optional<RiFacCover> findByIdAndDeletedAtIsNull(UUID id);
}
