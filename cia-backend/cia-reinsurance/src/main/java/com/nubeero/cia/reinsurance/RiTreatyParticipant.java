package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ri_treaty_participants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiTreatyParticipant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "treaty_id", nullable = false)
    private RiTreaty treaty;

    @Column(name = "reinsurance_company_id", nullable = false)
    private UUID reinsuranceCompanyId;

    @Column(name = "reinsurance_company_name", nullable = false, length = 200)
    private String reinsuranceCompanyName;

    @Column(name = "share_percentage", nullable = false, precision = 7, scale = 4)
    private BigDecimal sharePercentage;

    // 1, 2, 3… for SURPLUS treaties (null for QS and XOL)
    @Column(name = "surplus_line")
    private Integer surplusLine;

    @Column(name = "is_lead", nullable = false)
    @Builder.Default
    private boolean lead = false;

    @Column(name = "commission_rate", nullable = false, precision = 7, scale = 4)
    @Builder.Default
    private BigDecimal commissionRate = BigDecimal.ZERO;
}
