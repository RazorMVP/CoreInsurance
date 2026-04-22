package com.nubeero.cia.setup.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClaimDocumentRequirementRepository extends JpaRepository<ClaimDocumentRequirement, UUID> {

    List<ClaimDocumentRequirement> findAllByProductIdAndDeletedAtIsNull(UUID productId);
}
