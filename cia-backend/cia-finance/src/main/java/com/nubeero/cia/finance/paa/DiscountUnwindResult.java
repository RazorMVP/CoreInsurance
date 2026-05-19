package com.nubeero.cia.finance.paa;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Summary returned from {@link DiscountUnwindEngine#recognise(UUID)}.
 *
 * <p>{@code routing} reflects the {@link PaaConfig#isOciElection()} election
 * at the time of the run — TRUE means the JEs hit account 3430 (OCI), FALSE
 * means they hit 5520 (P&amp;L insurance finance expense). For tenants where
 * {@code discount_lic = FALSE} the engine is a no-op and returns
 * {@code groupsProcessed = 0}.
 */
public record DiscountUnwindResult(

    UUID periodId,
    /** TRUE if {@code paa_config.discount_lic} was FALSE → engine no-op. */
    boolean discountingDisabled,
    /** Routing label: "P&L" or "OCI". Null when discounting is disabled. */
    String routing,
    int groupsProcessed,
    int groupsWithJournalEntry,
    BigDecimal totalUnwind,
    List<GroupUnwindEntry> entries

) {

    public record GroupUnwindEntry(
        UUID groupId,
        BigDecimal openingBalance,
        BigDecimal unwindAmount,
        BigDecimal closingBalance,
        UUID journalEntryId
    ) {}
}
