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

    /**
     * Scalar projection of the two fields a beneficiary resolver needs
     * (customer id + denormalised name). Loading the full {@link Claim} entity
     * inside a write transaction (e.g. payment posting → voucher-PDF gen) pulls
     * its {@code @OneToMany(cascade=ALL, orphanRemoval=true)} collections
     * (notably {@code documents}) into the session; the next autoflush then
     * throws "Found shared references to a collection: Claim.documents" and
     * rolls back the payment. This projection never materialises the entity or
     * its collections. See cia-log 2026-06-28.
     */
    @Query("SELECT c.customerId AS customerId, c.customerName AS customerName "
            + "FROM Claim c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<ClaimBeneficiaryView> findBeneficiaryView(@Param("id") UUID id);

    /** Projection for {@link #findBeneficiaryView(UUID)}. */
    interface ClaimBeneficiaryView {
        UUID getCustomerId();
        String getCustomerName();
    }
}
