package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "survey_thresholds")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SurveyThreshold extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "threshold_type", nullable = false, length = 20)
    private SurveyThresholdType thresholdType;

    @Column(name = "min_sum_insured", nullable = false, precision = 18, scale = 2)
    private BigDecimal minSumInsured;
}
