package com.nubeero.cia.setup.quote.dto;

import com.nubeero.cia.setup.quote.CalcSequence;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QuoteConfigRequest {

    @Min(1) @Max(365)
    private int validityDays;

    @NotNull
    private CalcSequence calcSequence;
}
