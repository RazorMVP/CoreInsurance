package com.nubeero.cia.setup.loss;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NatureOfLossRepository extends JpaRepository<NatureOfLoss, UUID> {

    Page<NatureOfLoss> findAllByDeletedAtIsNull(Pageable pageable);
}
