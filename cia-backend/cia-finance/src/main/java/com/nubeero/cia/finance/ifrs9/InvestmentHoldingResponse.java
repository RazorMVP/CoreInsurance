package com.nubeero.cia.finance.ifrs9;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Wire response for an {@link InvestmentHolding}. Mirrors the entity
 * shape but is a record so callers don't take an accidental dependency
 * on JPA infrastructure.
 */
public record InvestmentHoldingResponse(

    UUID id,
    String isin,
    String securityName,
    String issuer,
    AssetType assetType,
    InvestmentClassification classification,
    LocalDate acquisitionDate,
    BigDecimal acquisitionCost,
    BigDecimal faceValue,
    BigDecimal couponRate,
    LocalDate maturityDate,
    String currencyCode,
    HoldingStatus status,
    Boolean sppiTestPassed,
    Integer eclStage

) {

    public static InvestmentHoldingResponse from(InvestmentHolding h) {
        return new InvestmentHoldingResponse(
            h.getId(),
            h.getIsin(),
            h.getSecurityName(),
            h.getIssuer(),
            h.getAssetType(),
            h.getClassification(),
            h.getAcquisitionDate(),
            h.getAcquisitionCost(),
            h.getFaceValue(),
            h.getCouponRate(),
            h.getMaturityDate(),
            h.getCurrencyCode(),
            h.getStatus(),
            h.getSppiTestPassed(),
            h.getEclStage());
    }
}
