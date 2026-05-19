package com.nubeero.cia.finance.ifrs9;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Summary returned from {@link InvestmentEclEngine#recognise}. One entry
 * per holding the engine touched (whether or not a JE was posted).
 *
 * <p>{@code totalEclIncrease} + {@code totalEclReversal} together give the
 * absolute movement; the signed sum is on the result level via
 * {@code totalEclMovement}.
 */
public record EclRecognitionResult(

    UUID periodId,
    int holdingsProcessed,
    int holdingsWithJournalEntry,
    BigDecimal totalEclIncrease,
    BigDecimal totalEclReversal,
    BigDecimal totalEclMovement,
    List<HoldingEclEntry> entries

) {

    public record HoldingEclEntry(
        UUID holdingId,
        String securityName,
        InvestmentClassification classification,
        Integer priorStage,
        Integer newStage,
        BigDecimal priorEcl,
        BigDecimal newEcl,
        BigDecimal eclMovement,
        UUID journalEntryId
    ) {}
}
