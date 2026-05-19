package com.nubeero.cia.finance.ifrs9;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Summary returned from {@link AmortisedCostEngine#recognise(UUID)}. One
 * entry per holding the engine touched. Holdings that aren't eligible
 * (FVPL / FVOCI_EQUITY / DERIVATIVE / non-ACTIVE / no coupon rate) are
 * omitted entirely; holdings outside the period's active window are
 * present with {@code interestIncome = 0} so disclosure roll-ups don't
 * silently drop them.
 */
public record AmortisedCostResult(

    UUID periodId,
    int holdingsProcessed,
    int holdingsWithJournalEntry,
    BigDecimal totalInterestIncome,
    List<HoldingInterestEntry> entries

) {

    public record HoldingInterestEntry(
        UUID holdingId,
        String securityName,
        InvestmentClassification classification,
        BigDecimal openingBalance,
        BigDecimal interestIncome,
        BigDecimal closingBalance,
        UUID journalEntryId
    ) {}
}
