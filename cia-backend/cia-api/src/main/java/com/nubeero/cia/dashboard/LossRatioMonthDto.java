package com.nubeero.cia.dashboard;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class LossRatioMonthDto {
    String month;         // "Jan 26", "Feb 26", etc.
    BigDecimal premium;
    BigDecimal claims;
    BigDecimal lossRatioPct;
}
