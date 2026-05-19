package com.nubeero.cia.finance.ifrs9;

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
 * Period-end roll-forward snapshot per holding under IFRS 9. One row per
 * {@code (holding, period)} pair, written by the measurement engines
 * (Slices 3.3-3.5).
 *
 * <p>Roll-forward identity:
 * <pre>
 *   closing = opening
 *           + effective_interest_income
 *           + coupon_received
 *           + fair_value_change_pnl
 *           + fair_value_change_oci
 *           − ecl_movement
 *           − impairment_loss
 *           − disposals
 * </pre>
 *
 * <p>The DB enforces non-negativity on stocks (opening, closing,
 * coupon_received, impairment_loss, disposals). The deltas
 * (effective_interest_income, fair_value_change_*, ecl_movement) can be
 * signed — ECL can reverse, FV can decrease. The engines enforce the
 * identity in code.
 *
 * <p>{@code closing_fair_value} is null for {@link InvestmentClassification#AMORTISED_COST}
 * holdings (no FV measurement needed; carrying = amortised cost).
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
    name = "investment_carrying_value",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_investment_carrying_holding_period",
        columnNames = {"holding_id", "period_id"}
    )
)
public class InvestmentCarryingValue extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "holding_id", nullable = false, updatable = false)
    private InvestmentHolding holding;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "period_id", nullable = false, updatable = false)
    private FiscalPeriod period;

    @Column(name = "opening_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(name = "effective_interest_income", nullable = false, precision = 18, scale = 2)
    private BigDecimal effectiveInterestIncome = BigDecimal.ZERO;

    @Column(name = "coupon_received", nullable = false, precision = 18, scale = 2)
    private BigDecimal couponReceived = BigDecimal.ZERO;

    @Column(name = "fair_value_change_pnl", nullable = false, precision = 18, scale = 2)
    private BigDecimal fairValueChangePnl = BigDecimal.ZERO;

    @Column(name = "fair_value_change_oci", nullable = false, precision = 18, scale = 2)
    private BigDecimal fairValueChangeOci = BigDecimal.ZERO;

    @Column(name = "ecl_movement", nullable = false, precision = 18, scale = 2)
    private BigDecimal eclMovement = BigDecimal.ZERO;

    @Column(name = "impairment_loss", nullable = false, precision = 18, scale = 2)
    private BigDecimal impairmentLoss = BigDecimal.ZERO;

    @Column(name = "disposals", nullable = false, precision = 18, scale = 2)
    private BigDecimal disposals = BigDecimal.ZERO;

    @Column(name = "closing_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal closingBalance = BigDecimal.ZERO;

    /** Null for AMORTISED_COST; populated for FVOCI / FVPL holdings each period. */
    @Column(name = "closing_fair_value", precision = 18, scale = 2)
    private BigDecimal closingFairValue;

    /** Stage at period end. Null for FVPL / FVOCI_EQUITY. 1/2/3 for AC / FVOCI_DEBT. */
    @Column(name = "ecl_stage")
    private Integer eclStage;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "NGN";
}
