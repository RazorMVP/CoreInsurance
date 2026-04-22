package com.nubeero.cia.quotation;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "quote_coinsurance_participants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteCoinsuranceParticipant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;

    @Column(name = "insurance_company_id", nullable = false)
    private UUID insuranceCompanyId;

    @Column(name = "insurance_company_name", nullable = false, length = 200)
    private String insuranceCompanyName;

    @Column(name = "share_percentage", nullable = false, precision = 6, scale = 4)
    private BigDecimal sharePercentage;
}
