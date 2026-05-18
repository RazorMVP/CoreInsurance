package com.nubeero.cia.finance.paa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaaLicRepository extends JpaRepository<PaaLic, UUID> {

    Optional<PaaLic> findByGroupIdAndPeriodIdAndDeletedAtIsNull(UUID groupId, UUID periodId);

    List<PaaLic> findByGroupIdAndDeletedAtIsNullOrderByPeriodIdAsc(UUID groupId);

    List<PaaLic> findByPeriodIdAndDeletedAtIsNullOrderByGroupIdAsc(UUID periodId);
}
