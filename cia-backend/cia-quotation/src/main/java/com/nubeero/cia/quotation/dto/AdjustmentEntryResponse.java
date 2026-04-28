package com.nubeero.cia.quotation.dto;

import com.nubeero.cia.quotation.AdjustmentFormat;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

@Value
@Builder
public class AdjustmentEntryResponse {
    UUID             typeId;
    String           typeName;
    AdjustmentFormat format;
    BigDecimal       value;
    BigDecimal       computedAmount;
}
