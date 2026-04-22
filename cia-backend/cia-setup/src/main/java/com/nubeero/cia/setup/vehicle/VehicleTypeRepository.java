package com.nubeero.cia.setup.vehicle;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VehicleTypeRepository extends JpaRepository<VehicleType, UUID> {

    Page<VehicleType> findAllByDeletedAtIsNull(Pageable pageable);
}
