package com.nubeero.cia.setup.vehicle;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VehicleMakeRepository extends JpaRepository<VehicleMake, UUID> {
    Optional<VehicleMake> findByNameIgnoreCaseAndDeletedAtIsNull(String name);
    Page<VehicleMake> findAllByDeletedAtIsNull(Pageable pageable);
}
