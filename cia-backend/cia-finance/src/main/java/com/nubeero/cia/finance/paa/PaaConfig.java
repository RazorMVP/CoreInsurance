package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Per-tenant IFRS 17 accounting policy elections. Singleton — at most one
 * non-deleted row per tenant schema, enforced by {@code uq_paa_config_singleton}
 * in V36 (partial unique index on {@code singleton_marker}).
 *
 * <p>Defaults mirror the most common Nigerian GB carrier setup:
 * <ul>
 *   <li>{@code discountLic = false} — no LIC discounting (short-tail claims, §59(b) exemption)</li>
 *   <li>{@code ociElection = false} — all finance income/expense through P&amp;L</li>
 *   <li>{@code raMethod = CONFIDENCE_LEVEL} — VaR-style risk adjustment</li>
 *   <li>{@code acquisitionCashflowMethod = EXPENSE_AS_INCURRED} — no DAC, the §59(a) simplification</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "paa_config")
public class PaaConfig extends BaseEntity {

    /** Always {@code true}; partners the V36 partial unique index that enforces singleton-per-tenant. */
    @Column(name = "singleton_marker", nullable = false)
    private boolean singletonMarker = true;

    @Column(name = "discount_lic", nullable = false)
    private boolean discountLic = false;

    @Column(name = "discount_rate", precision = 8, scale = 5)
    private BigDecimal discountRate;

    @Column(name = "oci_election", nullable = false)
    private boolean ociElection = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "ra_method", nullable = false, length = 40)
    private RiskAdjustmentMethod raMethod = RiskAdjustmentMethod.CONFIDENCE_LEVEL;

    @Column(name = "ra_confidence_level", precision = 5, scale = 2)
    private BigDecimal raConfidenceLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "acquisition_cashflow_method", nullable = false, length = 40)
    private AcquisitionCashflowMethod acquisitionCashflowMethod = AcquisitionCashflowMethod.EXPENSE_AS_INCURRED;
}
