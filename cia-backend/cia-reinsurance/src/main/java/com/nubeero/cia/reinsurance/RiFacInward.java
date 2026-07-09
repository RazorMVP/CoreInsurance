package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.entity.BaseEntity;
import com.nubeero.cia.common.entity.LockableByPeriod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "ri_fac_inwards")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiFacInward extends BaseEntity implements LockableByPeriod {

    @Column(name = "fac_inward_reference", unique = true, nullable = false, length = 50)
    private String facInwardReference;

    @Column(name = "ceding_company_id", nullable = false)
    private UUID cedingCompanyId;

    @Column(name = "ceding_company_name", nullable = false, length = 200)
    private String cedingCompanyName;

    @Column(name = "class_of_business_id", nullable = false)
    private UUID classOfBusinessId;

    @Column(name = "class_of_business_name", nullable = false, length = 200)
    private String classOfBusinessName;

    @Column(name = "risk_description", columnDefinition = "TEXT")
    private String riskDescription;

    @Column(name = "sum_insured", nullable = false, precision = 18, scale = 2)
    private BigDecimal sumInsured;

    @Column(name = "our_share_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal ourSharePct;

    @Column(name = "accepted_sum_insured", nullable = false, precision = 18, scale = 2)
    private BigDecimal acceptedSumInsured;

    @Column(name = "premium_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal premiumRate;

    @Column(name = "gross_premium", nullable = false, precision = 18, scale = 2)
    private BigDecimal grossPremium;

    @Column(name = "commission_rate", nullable = false, precision = 7, scale = 4)
    @Builder.Default
    private BigDecimal commissionRate = BigDecimal.ZERO;

    @Column(name = "commission_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal commissionAmount = BigDecimal.ZERO;

    @Column(name = "net_premium", nullable = false, precision = 18, scale = 2)
    private BigDecimal netPremium;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "NGN";

    @Column(name = "cover_from", nullable = false)
    private LocalDate coverFrom;

    @Column(name = "cover_to", nullable = false)
    private LocalDate coverTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private RiFacInwardStatus status = RiFacInwardStatus.ACTIVE;

    @Column(name = "renewed_from_id")
    private UUID renewedFromId;

    @Column(name = "guaranty_document_path", length = 500)
    private String guarantyDocumentPath;

    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    /**
     * Period-lock anchor = <strong>booking date</strong> = {@code createdAt}, the
     * timestamp the inward FAC hits the books. It must <strong>not</strong> be
     * {@code coverFrom}/{@code coverTo} — those are the business-<em>effective</em>
     * cover period, and anchoring the lock on them would let a backdated cover
     * period be wrongly evaluated against a closed fiscal period (breaking lock
     * semantics — see CLAUDE.md §Period-Lock Design "getLockDate returns the
     * booking date, not the business-effective date").
     *
     * <p>Inward FAC is created live with no approval step, so its booking date is
     * the creation timestamp. Mirrors the created-fresh sibling idiom in
     * {@code DebitNote}/{@code CreditNote}: return {@code null} until auditing has
     * populated {@code createdAt} (the interceptor treats a null lock date as
     * "allow" — the pre-persist window is never in a closed period).
     */
    @Override
    public LocalDate getLockDate() {
        return getCreatedAt() == null ? null : getCreatedAt().atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
