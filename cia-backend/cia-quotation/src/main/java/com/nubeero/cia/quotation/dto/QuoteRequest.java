package com.nubeero.cia.quotation.dto;

import com.nubeero.cia.quotation.BusinessType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class QuoteRequest {

    @NotNull
    private UUID customerId;

    @NotNull
    private UUID productId;

    private UUID brokerId;

    @NotNull
    private BusinessType businessType;

    @NotNull
    private LocalDate policyStartDate;

    @NotNull
    private LocalDate policyEndDate;

    private String notes;

    @NotEmpty
    @Valid
    private List<QuoteRiskRequest> risks;

    @Valid
    private List<AdjustmentEntryRequest> quoteLoadings = new ArrayList<>();

    @Valid
    private List<AdjustmentEntryRequest> quoteDiscounts = new ArrayList<>();

    private List<String> selectedClauseIds = new ArrayList<>();

    @Valid
    private List<QuoteCoinsuranceParticipantRequest> coinsuranceParticipants;
}
