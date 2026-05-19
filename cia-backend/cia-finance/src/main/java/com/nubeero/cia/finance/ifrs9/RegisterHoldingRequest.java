package com.nubeero.cia.finance.ifrs9;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Wire contract for {@code POST /api/v1/finance/ifrs9/holdings} — register a
 * new financial-asset holding. {@link InvestmentClassificationService} runs
 * the SPPI + business-model logic to determine the §4.1 classification
 * automatically; the request carries the admin's inputs only.
 *
 * <h2>Required vs optional by asset type</h2>
 * <ul>
 *   <li><b>DEBT / MONEY_MARKET</b>: {@code sppiTestPassed} and
 *       {@code businessModel} are required. {@code faceValue},
 *       {@code couponRate}, {@code maturityDate} are typically present.</li>
 *   <li><b>EQUITY</b>: {@code sppiTestPassed}, {@code businessModel},
 *       {@code couponRate}, {@code maturityDate} are all ignored (the DB
 *       CHECK forbids the latter two). {@code fvociEquityElection}
 *       drives FVPL vs FVOCI_EQUITY.</li>
 *   <li><b>DERIVATIVE</b>: always classified FVPL regardless of inputs.</li>
 * </ul>
 */
public record RegisterHoldingRequest(

    @Size(max = 12)
    String isin,

    @NotBlank
    @Size(max = 200)
    String securityName,

    @Size(max = 200)
    String issuer,

    @NotNull
    AssetType assetType,

    @NotNull
    LocalDate acquisitionDate,

    @NotNull
    @DecimalMin("0.00")
    BigDecimal acquisitionCost,

    BigDecimal faceValue,

    BigDecimal couponRate,

    LocalDate maturityDate,

    @Size(max = 3)
    String currencyCode,

    /** Required for DEBT / MONEY_MARKET; ignored for EQUITY / DERIVATIVE. */
    Boolean sppiTestPassed,

    /** Required for DEBT / MONEY_MARKET; ignored for EQUITY / DERIVATIVE. */
    BusinessModel businessModel,

    /** Honoured only when {@code assetType = EQUITY}: TRUE → FVOCI_EQUITY (§5.7.5), FALSE → FVPL. */
    boolean fvociEquityElection

) {}
