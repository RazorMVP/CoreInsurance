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
 * Liability for Remaining Coverage roll-forward, one row per (group, period).
 * Captures the LRC movement during the period under the IFRS 17 PAA:
 *
 * <pre>
 *   opening_balance
 *     + premium_received
 *     − premium_earned
 *     + acquisition_costs_deferred
 *     − acquisition_costs_amortised
 *     + loss_component_change
 *     = closing_balance
 * </pre>
 *
 * <p>The {@code LrcEngine} (Slice 2.3) enforces this identity in code; the
 * DB enforces non-negativity invariants only.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
    name = "paa_lrc",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_paa_lrc_group_period",
        columnNames = {"group_id", "period_id"}
    )
)
public class PaaLrc extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private GroupOfContracts group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "period_id", nullable = false)
    private FiscalPeriod period;

    @Column(name = "opening_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(name = "premium_received", nullable = false, precision = 18, scale = 2)
    private BigDecimal premiumReceived = BigDecimal.ZERO;

    @Column(name = "premium_earned", nullable = false, precision = 18, scale = 2)
    private BigDecimal premiumEarned = BigDecimal.ZERO;

    @Column(name = "acquisition_costs_deferred", nullable = false, precision = 18, scale = 2)
    private BigDecimal acquisitionCostsDeferred = BigDecimal.ZERO;

    @Column(name = "acquisition_costs_amortised", nullable = false, precision = 18, scale = 2)
    private BigDecimal acquisitionCostsAmortised = BigDecimal.ZERO;

    @Column(name = "loss_component", nullable = false, precision = 18, scale = 2)
    private BigDecimal lossComponent = BigDecimal.ZERO;

    @Column(name = "loss_component_change", nullable = false, precision = 18, scale = 2)
    private BigDecimal lossComponentChange = BigDecimal.ZERO;

    @Column(name = "closing_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal closingBalance = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "NGN";
}
