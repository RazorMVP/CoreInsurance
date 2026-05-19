package com.nubeero.cia.finance.ifrs9;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Summary returned from {@link PremiumReceivableEclEngine#recognise}.
 *
 * <p>Carries the full provision-matrix breakdown for §B5.5.36 disclosure
 * (the auditor's "show your work" report on lifetime ECL computation).
 */
public record PremiumReceivableEclResult(

    UUID periodId,
    BigDecimal totalOutstanding,
    BigDecimal targetLifetimeEcl,
    BigDecimal priorCumulativeEcl,
    BigDecimal eclMovement,
    /** "INCREASE", "REVERSAL", or "NO_CHANGE" — convenience for reporting. */
    String direction,
    UUID journalEntryId,
    List<BucketBreakdown> buckets

) {

    public record BucketBreakdown(
        String label,
        BigDecimal outstandingAmount,
        BigDecimal defaultRate,
        BigDecimal bucketEcl
    ) {}
}
