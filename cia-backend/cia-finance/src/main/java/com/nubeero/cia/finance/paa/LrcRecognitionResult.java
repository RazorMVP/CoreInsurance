package com.nubeero.cia.finance.paa;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Summary returned from {@link LrcEngine#recognise(UUID)}. Lists the groups
 * touched by this run, the total premium earned during the period, and the
 * journal entries posted.
 *
 * <p>Groups with zero earned premium for the period are deliberately
 * omitted — no JE is posted for them, so listing them would only inflate
 * the audit trail without information.
 */
public record LrcRecognitionResult(

    UUID periodId,
    int groupsProcessed,
    int groupsWithJournalEntry,
    BigDecimal totalPremiumEarned,
    List<GroupRecognitionEntry> entries

) {

    /**
     * One row per (group, period) the engine touched. {@code journalEntryId}
     * is null when {@code earned} is zero (e.g. a group whose only policy
     * starts in the next period).
     */
    public record GroupRecognitionEntry(
        UUID groupId,
        BigDecimal openingBalance,
        BigDecimal premiumReceived,
        BigDecimal premiumEarned,
        BigDecimal closingBalance,
        UUID journalEntryId
    ) {}
}
