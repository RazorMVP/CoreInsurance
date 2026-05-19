package com.nubeero.cia.finance.ifrs9;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvestmentCarryingValueRepository extends JpaRepository<InvestmentCarryingValue, UUID> {

    Optional<InvestmentCarryingValue> findByHoldingIdAndPeriodIdAndDeletedAtIsNull(UUID holdingId, UUID periodId);

    List<InvestmentCarryingValue> findByHoldingIdAndDeletedAtIsNullOrderByPeriodIdAsc(UUID holdingId);

    List<InvestmentCarryingValue> findByPeriodIdAndDeletedAtIsNullOrderByHoldingIdAsc(UUID periodId);

    boolean existsByPeriodIdAndDeletedAtIsNull(UUID periodId);
}
