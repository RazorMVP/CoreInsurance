package com.nubeero.cia.endorsement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EndorsementRepository extends JpaRepository<Endorsement, UUID> {

    Optional<Endorsement> findByIdAndDeletedAtIsNull(UUID id);

    Page<Endorsement> findAllByDeletedAtIsNull(Pageable pageable);

    Page<Endorsement> findAllByPolicyIdAndDeletedAtIsNull(UUID policyId, Pageable pageable);

    Page<Endorsement> findAllByStatusAndDeletedAtIsNull(EndorsementStatus status, Pageable pageable);

    Page<Endorsement> findAllByCustomerIdAndDeletedAtIsNull(UUID customerId, Pageable pageable);

    /**
     * Scalar projection (customer id + denormalised name) for the refund
     * beneficiary resolver. Loading the full {@link Endorsement} inside the
     * payment write-tx pulls its {@code @OneToMany(cascade=ALL, orphanRemoval=true)}
     * {@code risks} collection into the session; the voucher-PDF template
     * query's autoflush then throws "Found shared references to a collection:
     * Endorsement.risks" and rolls back the payment. cia-log 2026-06-28.
     */
    @Query("SELECT e.customerId AS customerId, e.customerName AS customerName "
            + "FROM Endorsement e WHERE e.id = :id AND e.deletedAt IS NULL")
    Optional<EndorsementBeneficiaryView> findBeneficiaryView(@Param("id") UUID id);

    /** Projection for {@link #findBeneficiaryView(UUID)}. */
    interface EndorsementBeneficiaryView {
        UUID getCustomerId();
        String getCustomerName();
    }
}
