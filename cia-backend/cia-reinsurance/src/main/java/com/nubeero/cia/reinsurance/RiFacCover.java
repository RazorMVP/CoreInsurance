package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "ri_fac_covers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiFacCover extends BaseEntity {

    @Column(name = "fac_reference", unique = true, nullable = false, length = 50)
    private String facReference;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "policy_number", nullable = false, length = 60)
    private String policyNumber;

    @Column(name = "reinsurance_company_id", nullable = false)
    private UUID reinsuranceCompanyId;

    @Column(name = "reinsurance_company_name", nullable = false, length = 200)
    private String reinsuranceCompanyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private FacCoverStatus status = FacCoverStatus.PENDING;

    @Column(name = "sum_insured_ceded", nullable = false, precision = 18, scale = 2)
    private BigDecimal sumInsuredCeded;

    @Column(name = "premium_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal premiumRate;

    @Column(name = "premium_ceded", nullable = false, precision = 18, scale = 2)
    private BigDecimal premiumCeded;

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

    @Column(name = "offer_slip_reference", length = 100)
    private String offerSlipReference;

    @Column(columnDefinition = "TEXT")
    private String terms;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;
}
