package com.nubeero.cia.finance.ifrs9;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InvestmentClassificationHistoryRepository
        extends JpaRepository<InvestmentClassificationHistory, UUID> {

    List<InvestmentClassificationHistory> findByHoldingIdAndDeletedAtIsNullOrderByReclassificationDateAsc(UUID holdingId);
}
