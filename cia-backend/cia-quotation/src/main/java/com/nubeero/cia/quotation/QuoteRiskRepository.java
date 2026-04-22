package com.nubeero.cia.quotation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuoteRiskRepository extends JpaRepository<QuoteRisk, UUID> {

    List<QuoteRisk> findAllByQuoteIdAndDeletedAtIsNull(UUID quoteId);
}
