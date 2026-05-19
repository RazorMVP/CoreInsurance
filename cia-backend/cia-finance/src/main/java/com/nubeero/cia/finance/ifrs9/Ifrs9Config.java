package com.nubeero.cia.finance.ifrs9;

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
 * Per-tenant IFRS 9 accounting-policy elections. Singleton — at most one
 * non-deleted row per tenant schema, enforced by
 * {@code uq_ifrs9_config_singleton} in V39 (partial unique index on
 * {@code singleton_marker}).
 *
 * <p>Defaults match a typical Nigerian GB insurer setup:
 * <ul>
 *   <li>{@code investmentEclMethod = GENERAL} — 3-stage ECL for
 *       investment debt (§5.5.3-5.5.8).</li>
 *   <li>{@code receivableEclMethod = SIMPLIFIED} — single-stage lifetime
 *       ECL via provision matrix for premium receivables (§5.5.15).</li>
 *   <li>{@code defaultThresholdDaysPastDue = 90} — IFRS 9 rebuttable
 *       presumption (§B5.5.37).</li>
 *   <li>{@code fvociEquityElectionActive = false} — no §5.7.5 election;
 *       equities default to FVPL.</li>
 * </ul>
 *
 * <p>Mirrors {@code paa_config} (Slice 2.1) in shape and singleton pattern.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "ifrs9_config")
public class Ifrs9Config extends BaseEntity {

    /** Always {@code true}; partners the V39 partial unique index that enforces singleton-per-tenant. */
    @Column(name = "singleton_marker", nullable = false)
    private boolean singletonMarker = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "investment_ecl_method", nullable = false, length = 20)
    private EclMethod investmentEclMethod = EclMethod.GENERAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "receivable_ecl_method", nullable = false, length = 20)
    private EclMethod receivableEclMethod = EclMethod.SIMPLIFIED;

    @Column(name = "sicr_threshold_pd_increase", precision = 5, scale = 2)
    private BigDecimal sicrThresholdPdIncrease;

    @Column(name = "sicr_threshold_days_past_due")
    private Integer sicrThresholdDaysPastDue;

    @Column(name = "default_threshold_days_past_due", nullable = false)
    private Integer defaultThresholdDaysPastDue = 90;

    @Column(name = "fvoci_equity_election_active", nullable = false)
    private boolean fvociEquityElectionActive = false;
}
