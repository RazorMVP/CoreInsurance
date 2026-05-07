package com.nubeero.cia.common.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class PremiumCalculator {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private PremiumCalculator() {
    }

    public static BigDecimal grossPremium(BigDecimal sumInsured, BigDecimal ratePercent) {
        requireNonNull(sumInsured, "sumInsured");
        requireNonNull(ratePercent, "ratePercent");
        return sumInsured.multiply(ratePercent)
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal netPremium(BigDecimal grossPremium, BigDecimal discount) {
        return grossPremium.subtract(effectiveDiscount(grossPremium, discount))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal effectiveDiscount(BigDecimal grossPremium, BigDecimal discount) {
        requireNonNull(grossPremium, "grossPremium");
        BigDecimal effectiveDiscount = discount == null ? BigDecimal.ZERO : discount;
        if (effectiveDiscount.compareTo(BigDecimal.ZERO) < 0) {
            effectiveDiscount = BigDecimal.ZERO;
        }
        if (effectiveDiscount.compareTo(grossPremium) > 0) {
            effectiveDiscount = grossPremium;
        }
        return effectiveDiscount.setScale(2, RoundingMode.HALF_UP);
    }

    private static void requireNonNull(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
