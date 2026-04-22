package com.nubeero.cia.setup.vehicle;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VehicleModelRepository extends JpaRepository<VehicleModel, UUID> {

    List<VehicleModel> findAllByMakeIdAndDeletedAtIsNull(UUID makeId);

    Page<VehicleModel> findAllByMakeIdAndDeletedAtIsNull(UUID makeId, Pageable pageable);
}
