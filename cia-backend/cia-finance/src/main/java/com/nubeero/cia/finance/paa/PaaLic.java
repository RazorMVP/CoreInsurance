package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.entity.BaseEntity;
import com.nubeero.cia.finance.gl.FiscalPeriod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Liability for Incurred Claims roll-forward, one row per (group, period).
 * Captures the LIC movement during the period under the IFRS 17 PAA:
 *
 * <pre>
 *   opening_balance
 *     + claims_incurred
 *     − claims_paid
 *     + case_reserve_change
 *     + ibnr_change
 *     + risk_adjustment_change
 *     + discount_unwind
 *     = closing_balance
 * </pre>
 *
 * <p>The {@code LicEngine} (Slice 2.4) enforces this identity in code; the
 * DB enforces non-negativity invariants only. Discounting is optional under
 * §59(b) — the tenant's election lives on {@link PaaConfig}.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
    name = "paa_lic",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_paa_lic_group_period",
        columnNames = {"group_id", "period_id"}
    )
)
public class PaaLic extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private GroupOfContracts group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "period_id", nullable = false)
    private FiscalPeriod period;

    @Column(name = "opening_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(name = "claims_incurred", nullable = false, precision = 18, scale = 2)
    private BigDecimal claimsIncurred = BigDecimal.ZERO;

    @Column(name = "claims_paid", nullable = false, precision = 18, scale = 2)
    private BigDecimal claimsPaid = BigDecimal.ZERO;

    @Column(name = "case_reserve_change", nullable = false, precision = 18, scale = 2)
    private BigDecimal caseReserveChange = BigDecimal.ZERO;

    @Column(name = "ibnr_estimate", nullable = false, precision = 18, scale = 2)
    private BigDecimal ibnrEstimate = BigDecimal.ZERO;

    @Column(name = "ibnr_change", nullable = false, precision = 18, scale = 2)
    private BigDecimal ibnrChange = BigDecimal.ZERO;

    @Column(name = "risk_adjustment", nullable = false, precision = 18, scale = 2)
    private BigDecimal riskAdjustment = BigDecimal.ZERO;

    @Column(name = "risk_adjustment_change", nullable = false, precision = 18, scale = 2)
    private BigDecimal riskAdjustmentChange = BigDecimal.ZERO;

    @Column(name = "discount_unwind", nullable = false, precision = 18, scale = 2)
    private BigDecimal discountUnwind = BigDecimal.ZERO;

    @Column(name = "closing_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal closingBalance = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "NGN";
}
