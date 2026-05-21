package com.nubeero.cia.finance.gl;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire DTO for {@link PostingRule}, enriched with the human-readable account
 * names looked up via {@link ChartOfAccountService#findByCode(String)}.
 *
 * <p>Slice F5.7. The frontend page is a read-only admin viewer; carrying the
 * names server-side spares the client a second round-trip (or an in-memory
 * COA join) and keeps both sides in lock-step on what each code means today.
 */
public record PostingRuleResponse(
    UUID id,
    String sourceEventType,
    String debitAccountCode,
    String debitAccountName,
    String creditAccountCode,
    String creditAccountName,
    String narrativeTemplate,
    boolean active,
    Instant createdAt
) {

    /**
     * Build the response from a {@link PostingRule} entity and a resolver
     * function (typically {@link ChartOfAccountService#findByCode(String)}'s
     * method reference) used to enrich both account codes with their names.
     */
    public static PostingRuleResponse from(PostingRule rule,
                                            java.util.function.Function<String, String> accountNameByCode) {
        return new PostingRuleResponse(
            rule.getId(),
            rule.getSourceEventType(),
            rule.getDebitAccountCode(),
            accountNameByCode.apply(rule.getDebitAccountCode()),
            rule.getCreditAccountCode(),
            accountNameByCode.apply(rule.getCreditAccountCode()),
            rule.getNarrativeTemplate(),
            rule.isActive(),
            rule.getCreatedAt());
    }
}
