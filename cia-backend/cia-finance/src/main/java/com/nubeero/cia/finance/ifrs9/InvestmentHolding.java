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
import java.time.LocalDate;

/**
 * One financial asset held by the insurer. The IFRS 9 measurement engines
 * (Slices 3.3-3.5) read this entity to determine how to measure the asset
 * each period.
 *
 * <p>{@link #classification} is set on acquisition by
 * {@code InvestmentClassificationService} (Slice 3.2) using the SPPI test
 * + business-model criteria. Subsequent reclassifications under
 * §B4.1.26-B4.1.29 update this field AND insert a row in
 * {@code investment_classification_history} as the audit trail.
 *
 * <p>{@link #sppiTestPassed} is meaningful only for {@link AssetType#DEBT}
 * — null for equity / derivative.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "investment_holding")
public class InvestmentHolding extends BaseEntity {

    @Column(name = "isin", length = 12)
    private String isin;

    @Column(name = "security_name", nullable = false, length = 200)
    private String securityName;

    @Column(name = "issuer", length = 200)
    private String issuer;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 20, updatable = false)
    private AssetType assetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 20)
    private InvestmentClassification classification;

    @Column(name = "acquisition_date", nullable = false, updatable = false)
    private LocalDate acquisitionDate;

    @Column(name = "acquisition_cost", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal acquisitionCost;

    @Column(name = "face_value", precision = 18, scale = 2)
    private BigDecimal faceValue;

    @Column(name = "coupon_rate", precision = 8, scale = 5)
    private BigDecimal couponRate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "NGN";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private HoldingStatus status = HoldingStatus.ACTIVE;

    /** Null for non-debt instruments. TRUE if the contractual cashflows satisfy §4.1.3 SPPI. */
    @Column(name = "sppi_test_passed")
    private Boolean sppiTestPassed;

    /** Null for FVPL / FVOCI_EQUITY (ECL doesn't apply). 1/2/3 for AC / FVOCI_DEBT under §5.5.3. */
    @Column(name = "ecl_stage")
    private Integer eclStage;
}
