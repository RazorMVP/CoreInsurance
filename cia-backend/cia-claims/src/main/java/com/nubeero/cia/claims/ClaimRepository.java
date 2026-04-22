package com.nubeero.cia.claims;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {

    Optional<Claim> findByIdAndDeletedAtIsNull(UUID id);

    Page<Claim> findAllByDeletedAtIsNull(Pageable pageable);

    Page<Claim> findAllByStatusAndDeletedAtIsNull(ClaimStatus status, Pageable pageable);

    Page<Claim> findAllByCustomerIdAndDeletedAtIsNull(UUID customerId, Pageable pageable);

    Page<Claim> findAllByPolicyIdAndDeletedAtIsNull(UUID policyId, Pageable pageable);

    @Query("""
            SELECT c FROM Claim c WHERE c.deletedAt IS NULL AND (
                LOWER(c.claimNumber) LIKE LOWER(CONCAT('%', :q, '%')) OR
                LOWER(c.customerName) LIKE LOWER(CONCAT('%', :q, '%')) OR
                LOWER(c.policyNumber) LIKE LOWER(CONCAT('%', :q, '%'))
            )
            """)
    Page<Claim> search(@Param("q") String query, Pageable pageable);
}
