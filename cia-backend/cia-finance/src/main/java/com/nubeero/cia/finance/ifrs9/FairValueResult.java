package com.nubeero.cia.finance.ifrs9;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Summary returned from {@link FairValueEngine#recognise(java.util.UUID, java.util.Map)}.
 *
 * <p>Per-holding entry includes the routing destination ("PnL" or "OCI") so
 * disclosure consumers can roll up by income-statement vs equity-reserve
 * impact without re-querying the underlying JE lines.
 */
public record FairValueResult(

    UUID periodId,
    int holdingsProcessed,
    int holdingsWithJournalEntry,
    BigDecimal totalFairValueChangePnl,
    BigDecimal totalFairValueChangeOci,
    List<HoldingFairValueEntry> entries

) {

    public record HoldingFairValueEntry(
        UUID holdingId,
        String securityName,
        InvestmentClassification classification,
        /** "PnL" for FVPL classifications; "OCI" for FVOCI debt/equity. */
        String routing,
        BigDecimal preFairValueBalance,
        BigDecimal newFairValue,
        BigDecimal fairValueChange,
        UUID journalEntryId
    ) {}
}
