package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "commission_setups")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommissionSetup extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "broker_type", nullable = false, length = 50)
    private String brokerType;

    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal rate;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;
}
