package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps a sub-ledger event type to the COA accounts it debits and credits, plus
 * a narrative template applied at post time. Seeded by V33; SYSTEM rows are
 * immutable from the service layer (same pattern as
 * {@link ChartOfAccount} and {@code report_definition}).
 *
 * <p>The DB UNIQUE on {@code source_event_type} (declared in V31) means each
 * event type has exactly one rule — supports only 2-line postings. Compound
 * events ({@code FacPremiumCededEvent} with 3 lines) bypass this table and
 * build the JE directly in {@link SubledgerPostingService}.
 *
 * <p>{@code debitAccountCode} and {@code creditAccountCode} are codes (not
 * UUIDs) because V31's FK is to {@code chart_of_account.code} so the seed SQL
 * stays readable. {@link SubledgerPostingService} converts both to
 * {@link ChartOfAccount} via {@link ChartOfAccountService} at post time.
 *
 * <p>{@code narrativeTemplate} accepts {@link java.lang.String#format} style
 * placeholders ({@code %s}) — interpolation values are positional, supplied by
 * each listener method.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "posting_rule")
public class PostingRule extends BaseEntity {

    @Column(name = "source_event_type", nullable = false, unique = true, length = 60, updatable = false)
    private String sourceEventType;

    @Column(name = "debit_account_code", nullable = false, length = 20)
    private String debitAccountCode;

    @Column(name = "credit_account_code", nullable = false, length = 20)
    private String creditAccountCode;

    @Column(name = "narrative_template", columnDefinition = "TEXT")
    private String narrativeTemplate;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
