package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ri_allocation_lines")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiAllocationLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "allocation_id", nullable = false)
    private RiAllocation allocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id")
    private RiTreatyParticipant participant;

    @Column(name = "reinsurance_company_id", nullable = false)
    private UUID reinsuranceCompanyId;

    @Column(name = "reinsurance_company_name", nullable = false, length = 200)
    private String reinsuranceCompanyName;

    @Column(name = "share_percentage", nullable = false, precision = 7, scale = 4)
    private BigDecimal sharePercentage;

    @Column(name = "ceded_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal cededAmount;

    @Column(name = "ceded_premium", nullable = false, precision = 18, scale = 2)
    private BigDecimal cededPremium;

    @Column(name = "commission_rate", nullable = false, precision = 7, scale = 4)
    @Builder.Default
    private BigDecimal commissionRate = BigDecimal.ZERO;

    @Column(name = "commission_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal commissionAmount = BigDecimal.ZERO;
}
