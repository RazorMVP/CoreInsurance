package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ri_allocations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiAllocation extends BaseEntity {

    @Column(name = "allocation_number", unique = true, nullable = false, length = 30)
    private String allocationNumber;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "policy_number", nullable = false, length = 60)
    private String policyNumber;

    // Populated when triggered by an endorsement
    @Column(name = "endorsement_id")
    private UUID endorsementId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "treaty_id")
    private RiTreaty treaty;

    @Enumerated(EnumType.STRING)
    @Column(name = "treaty_type", nullable = false, length = 30)
    private TreatyType treatyType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private AllocationStatus status = AllocationStatus.DRAFT;

    @Column(name = "our_share_sum_insured", nullable = false, precision = 18, scale = 2)
    private BigDecimal ourShareSumInsured;

    @Column(name = "retained_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal retainedAmount;

    @Column(name = "ceded_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal cededAmount = BigDecimal.ZERO;

    @Column(name = "excess_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal excessAmount = BigDecimal.ZERO;

    @Column(name = "our_share_premium", nullable = false, precision = 18, scale = 2)
    private BigDecimal ourSharePremium;

    @Column(name = "retained_premium", nullable = false, precision = 18, scale = 2)
    private BigDecimal retainedPremium;

    @Column(name = "ceded_premium", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal cededPremium = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "NGN";

    @OneToMany(mappedBy = "allocation", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RiAllocationLine> lines = new ArrayList<>();
}
