package com.nubeero.cia.finance.paa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaaLrcRepository extends JpaRepository<PaaLrc, UUID> {

    Optional<PaaLrc> findByGroupIdAndPeriodIdAndDeletedAtIsNull(UUID groupId, UUID periodId);

    List<PaaLrc> findByGroupIdAndDeletedAtIsNullOrderByPeriodIdAsc(UUID groupId);

    List<PaaLrc> findByPeriodIdAndDeletedAtIsNullOrderByGroupIdAsc(UUID periodId);
}
