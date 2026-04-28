package com.nubeero.cia.quotation;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "quote_risks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteRisk extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "sum_insured", nullable = false, precision = 18, scale = 2)
    private BigDecimal sumInsured;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal rate;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal grossPremium;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal premium;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "loadings", columnDefinition = "jsonb")
    @Builder.Default
    private List<AdjustmentEntry> loadings = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "discounts", columnDefinition = "jsonb")
    @Builder.Default
    private List<AdjustmentEntry> discounts = new ArrayList<>();

    @Column(name = "section_id")
    private UUID sectionId;

    @Column(name = "section_name", length = 100)
    private String sectionName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_details", columnDefinition = "jsonb")
    private Map<String, Object> riskDetails;

    @Column(name = "order_no", nullable = false)
    private int orderNo;
}
