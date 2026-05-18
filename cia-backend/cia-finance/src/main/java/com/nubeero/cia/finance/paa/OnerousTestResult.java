package com.nubeero.cia.finance.paa;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Summary returned from {@link OnerousContractTestEngine#test(UUID)}. Each
 * entry corresponds to one group whose paa_lrc row was present for the
 * period. {@code totalLossComponentIncrease} is the sum of positive deltas
 * (newly-recognised loss); {@code totalLossComponentReversal} is the sum
 * of absolute values of negative deltas (loss-component reversed).
 *
 * <p>Module 12 Phase 2 Slice 2.7.
 */
public record OnerousTestResult(

    UUID periodId,
    int groupsTested,
    int groupsWithLossComponentChange,
    BigDecimal totalLossComponentIncrease,
    BigDecimal totalLossComponentReversal,
    List<GroupOnerousEntry> entries

) {

    public record GroupOnerousEntry(
        UUID groupId,
        BigDecimal cumulativeEarned,
        BigDecimal cumulativeIncurred,
        BigDecimal priorLossComponent,
        BigDecimal newLossComponent,
        BigDecimal lossComponentChange,
        UUID journalEntryId
    ) {}
}
