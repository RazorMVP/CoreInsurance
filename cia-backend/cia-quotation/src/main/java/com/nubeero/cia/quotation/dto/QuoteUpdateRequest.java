package com.nubeero.cia.quotation.dto;

import com.nubeero.cia.quotation.BusinessType;
import jakarta.validation.Valid;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
public class QuoteUpdateRequest {

    private UUID         brokerId;
    private BusinessType businessType;
    private LocalDate    policyStartDate;
    private LocalDate    policyEndDate;
    private String       notes;

    @Valid
    private List<QuoteRiskRequest>                    risks;

    @Valid
    private List<AdjustmentEntryRequest>              quoteLoadings;

    @Valid
    private List<AdjustmentEntryRequest>              quoteDiscounts;

    private List<String>                              selectedClauseIds;

    @Valid
    private List<QuoteCoinsuranceParticipantRequest>  coinsuranceParticipants;
}
