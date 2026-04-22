package com.nubeero.cia.claims;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "claim_reserves")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimReserve extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "previous_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal previousAmount = BigDecimal.ZERO;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;
}
