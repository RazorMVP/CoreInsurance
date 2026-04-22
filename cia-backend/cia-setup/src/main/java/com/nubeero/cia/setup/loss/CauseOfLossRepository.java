package com.nubeero.cia.setup.loss;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CauseOfLossRepository extends JpaRepository<CauseOfLoss, UUID> {

    Page<CauseOfLoss> findAllByDeletedAtIsNull(Pageable pageable);

    List<CauseOfLoss> findAllByNatureOfLossIdAndDeletedAtIsNull(UUID natureOfLossId);
}
