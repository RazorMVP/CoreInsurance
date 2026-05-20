package com.nubeero.cia.finance.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Read model for one journal entry line. The COA fields are surfaced
 * alongside the id so callers don't have to issue a follow-up lookup just
 * to render the trial balance / JE inquiry page.
 */
public record JournalEntryLineResponse(
    UUID id,
    Integer lineNo,
    UUID accountId,
    String accountCode,
    String accountName,
    BigDecimal debitAmount,
    BigDecimal creditAmount,
    String currencyCode,
    Integer cohortYear,
    UUID portfolioId,
    UUID contractGroupId,
    UUID holdingId,
    UUID classOfBusinessId,
    Map<String, Object> dimensionTags
) {}
