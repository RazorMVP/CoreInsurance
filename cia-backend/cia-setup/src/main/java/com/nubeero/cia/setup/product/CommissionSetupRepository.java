package com.nubeero.cia.setup.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CommissionSetupRepository extends JpaRepository<CommissionSetup, UUID> {

    List<CommissionSetup> findAllByProductIdAndDeletedAtIsNull(UUID productId);
}
