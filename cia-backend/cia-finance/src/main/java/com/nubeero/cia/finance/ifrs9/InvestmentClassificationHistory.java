package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Type-2 SCD audit trail for IFRS 9 §B4.1.26-B4.1.29 reclassifications
 * — the rare events that move a holding between AMORTISED_COST / FVOCI /
 * FVPL because the underlying business model changed.
 *
 * <p>Routine state (the holding's current classification) lives on
 * {@link InvestmentHolding#getClassification()}. This entity is the
 * append-only audit record auditors will sample. The
 * {@code previous_classification != new_classification} CHECK in V39
 * prevents no-op rows.
 *
 * <p>One row per reclassification event. A holding that's never been
 * reclassified has zero rows here.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "investment_classification_history")
public class InvestmentClassificationHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "holding_id", nullable = false, updatable = false)
    private InvestmentHolding holding;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_classification", nullable = false, length = 20, updatable = false)
    private InvestmentClassification previousClassification;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_classification", nullable = false, length = 20, updatable = false)
    private InvestmentClassification newClassification;

    @Column(name = "reclassification_date", nullable = false, updatable = false)
    private LocalDate reclassificationDate;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Column(name = "approved_by", nullable = false, length = 100, updatable = false)
    private String approvedBy;
}
