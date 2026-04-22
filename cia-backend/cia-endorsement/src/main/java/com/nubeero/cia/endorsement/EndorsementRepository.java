package com.nubeero.cia.endorsement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EndorsementRepository extends JpaRepository<Endorsement, UUID> {

    Optional<Endorsement> findByIdAndDeletedAtIsNull(UUID id);

    Page<Endorsement> findAllByDeletedAtIsNull(Pageable pageable);

    Page<Endorsement> findAllByPolicyIdAndDeletedAtIsNull(UUID policyId, Pageable pageable);

    Page<Endorsement> findAllByStatusAndDeletedAtIsNull(EndorsementStatus status, Pageable pageable);

    Page<Endorsement> findAllByCustomerIdAndDeletedAtIsNull(UUID customerId, Pageable pageable);
}
