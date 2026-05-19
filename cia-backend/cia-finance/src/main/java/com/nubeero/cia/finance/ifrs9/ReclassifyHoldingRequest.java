package com.nubeero.cia.finance.ifrs9;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Wire contract for {@code POST /api/v1/finance/ifrs9/holdings/{id}/reclassify}.
 *
 * <p>IFRS 9 §B4.1.26-§B4.1.29 reclassification is a rare event — only
 * permitted when the business model itself changes — and is heavily
 * scrutinised by auditors. The request requires both a free-text
 * {@code reason} and an explicit {@code approvedBy} so the audit trail
 * captures who authorised the change and why.
 */
public record ReclassifyHoldingRequest(

    @NotNull
    InvestmentClassification newClassification,

    @NotNull
    LocalDate reclassificationDate,

    @NotBlank
    @Size(max = 2000)
    String reason,

    @NotBlank
    @Size(max = 100)
    String approvedBy

) {}
