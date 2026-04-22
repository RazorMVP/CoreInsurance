package com.nubeero.cia.quotation.dto;

import com.nubeero.cia.quotation.BusinessType;
import com.nubeero.cia.quotation.QuoteStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
public class QuoteSummaryResponse {
    private UUID id;
    private String quoteNumber;
    private QuoteStatus status;
    private UUID customerId;
    private String customerName;
    private String productName;
    private String classOfBusinessName;
    private String brokerName;
    private BusinessType businessType;
    private LocalDate policyStartDate;
    private LocalDate policyEndDate;
    private BigDecimal netPremium;
    private Instant expiresAt;
    private Instant createdAt;
}
