package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One leg of a {@link JournalEntry}. Exactly one of {@code debitAmount} /
 * {@code creditAmount} is &gt; 0 (DB CHECK {@code ck_journal_entry_line_amount}).
 *
 * <p>Promoted dimension columns:
 * <ul>
 *   <li>{@code cohortYear} — IFRS 17 cohort accounting (Slice 2.x).</li>
 *   <li>{@code portfolioId} / {@code contractGroupId} — IFRS 17 group-level
 *       roll-ups (Slice 2.x, Slice 2.y).</li>
 *   <li>{@code holdingId} — IFRS 9 per-instrument ECL/income (Slice 3.x).</li>
 * </ul>
 * Each is promoted out of {@code dimensionTags} so it gets a real index and a
 * future FK. {@code dimensionTags} carries any other ad-hoc analytic tags.
 *
 * <p>Slice 1.4 (gateway) populates only the COA account + debit/credit
 * amounts. The dimension columns are filled by later slices that own the
 * relevant aggregates.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "journal_entry_line")
public class JournalEntryLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry journalEntry;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private ChartOfAccount account;

    @Column(name = "debit_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal debitAmount = BigDecimal.ZERO;

    @Column(name = "credit_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "NGN";

    @Column(name = "cohort_year")
    private Integer cohortYear;

    @Column(name = "portfolio_id")
    private UUID portfolioId;

    @Column(name = "contract_group_id")
    private UUID contractGroupId;

    @Column(name = "holding_id")
    private UUID holdingId;

    /**
     * JSONB column. Hibernate 6 maps a {@code Map} via
     * {@link JdbcTypeCode}({@link SqlTypes#JSON}) — round-trips through the
     * Jackson mapper Spring Boot registers by default. Default
     * {@link HashMap} keeps the NOT NULL constraint satisfied without callers
     * having to remember to initialise it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dimension_tags", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> dimensionTags = new HashMap<>();
}
