package com.nubeero.cia.finance.paa;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Summary returned from {@link LicEngine#recognise(UUID)}. Lists the groups
 * touched by this run, the period totals, and (for future slices) any JEs
 * posted. For v1 no JE is posted — see {@link LicEngine} class javadoc.
 */
public record LicRecognitionResult(

    UUID periodId,
    int groupsProcessed,
    BigDecimal totalClaimsIncurred,
    BigDecimal totalClaimsPaid,
    List<GroupRecognitionEntry> entries

) {

    public record GroupRecognitionEntry(
        UUID groupId,
        BigDecimal openingBalance,
        BigDecimal claimsIncurred,
        BigDecimal claimsPaid,
        BigDecimal closingBalance
    ) {}
}
