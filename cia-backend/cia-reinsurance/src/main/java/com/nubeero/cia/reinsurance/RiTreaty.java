package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ri_treaties")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiTreaty extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "treaty_type", nullable = false, length = 30)
    private TreatyType treatyType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private TreatyStatus status = TreatyStatus.DRAFT;

    @Column(name = "treaty_year", nullable = false)
    private int treatyYear;

    // Scope — null = applies to all products/classes
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "class_of_business_id")
    private UUID classOfBusinessId;

    // SURPLUS-specific
    @Column(name = "retention_limit", precision = 18, scale = 2)
    private BigDecimal retentionLimit;

    @Column(name = "surplus_capacity", precision = 18, scale = 2)
    private BigDecimal surplusCapacity;

    // XOL-specific
    @Column(name = "xol_per_risk_retention", precision = 18, scale = 2)
    private BigDecimal xolPerRiskRetention;

    @Column(name = "xol_per_risk_limit", precision = 18, scale = 2)
    private BigDecimal xolPerRiskLimit;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "NGN";

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(columnDefinition = "TEXT")
    private String description;

    @OneToMany(mappedBy = "treaty", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RiTreatyParticipant> participants = new ArrayList<>();
}
