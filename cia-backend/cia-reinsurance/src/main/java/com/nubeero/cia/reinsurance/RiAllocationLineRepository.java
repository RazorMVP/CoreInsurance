package com.nubeero.cia.reinsurance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RiAllocationLineRepository extends JpaRepository<RiAllocationLine, UUID> {

    List<RiAllocationLine> findByAllocationId(UUID allocationId);
}
