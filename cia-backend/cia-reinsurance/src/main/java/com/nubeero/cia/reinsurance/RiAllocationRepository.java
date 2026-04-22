package com.nubeero.cia.reinsurance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiAllocationRepository extends JpaRepository<RiAllocation, UUID> {

    @Query("""
            SELECT a FROM RiAllocation a
            WHERE a.deletedAt IS NULL
              AND (:policyId IS NULL OR a.policyId = :policyId)
              AND (:status IS NULL OR a.status = :status)
            """)
    Page<RiAllocation> findAll(
            @Param("policyId") UUID policyId,
            @Param("status") AllocationStatus status,
            Pageable pageable);

    List<RiAllocation> findByPolicyIdAndDeletedAtIsNull(UUID policyId);

    Optional<RiAllocation> findByIdAndDeletedAtIsNull(UUID id);
}
