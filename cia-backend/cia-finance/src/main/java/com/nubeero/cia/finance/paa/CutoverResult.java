package com.nubeero.cia.finance.paa;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Summary returned from {@link FacPaaCutoverService#runCutover(UUID)}.
 * FAC / IFRS-17 PAA workstream Task 5 (modified-prospective transition).
 *
 * <p>Mirrors {@link LrcRecognitionResult}'s shape.
 */
public record CutoverResult(

    UUID periodId,
    int contractsGrouped,
    BigDecimal totalCatchUpEarned,
    List<CutoverEntry> entries

) {

    /**
     * One row per in-force FAC contract the cutover grouped and caught up.
     * {@code catchUpEarned} is zero (and no JE posted) when the contract's
     * cover started within/after the cutover period — nothing pre-dates the
     * transition to catch up.
     */
    public record CutoverEntry(
        ContractType contractType,
        UUID contractId,
        UUID groupId,
        BigDecimal catchUpEarned
    ) {}
}
