package com.nubeero.cia.quotation.dto;

import com.nubeero.cia.quotation.AdjustmentFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class AdjustmentEntryRequest {

    @NotNull
    private UUID typeId;

    @NotNull
    private AdjustmentFormat format;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal value;
}
