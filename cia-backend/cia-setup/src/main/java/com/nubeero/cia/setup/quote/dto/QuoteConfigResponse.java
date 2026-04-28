package com.nubeero.cia.setup.quote.dto;

import com.nubeero.cia.setup.quote.CalcSequence;
import lombok.Builder;
import lombok.Value;
import java.util.UUID;

@Value
@Builder
public class QuoteConfigResponse {
    UUID         id;
    int          validityDays;
    CalcSequence calcSequence;
}
