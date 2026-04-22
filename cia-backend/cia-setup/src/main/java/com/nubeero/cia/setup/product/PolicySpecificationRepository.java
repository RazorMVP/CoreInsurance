package com.nubeero.cia.setup.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PolicySpecificationRepository extends JpaRepository<PolicySpecification, UUID> {

    Optional<PolicySpecification> findByProductIdAndDeletedAtIsNull(UUID productId);
}
