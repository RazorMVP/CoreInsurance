package com.nubeero.cia.setup.quote.dto;

import lombok.Builder;
import lombok.Value;
import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class AdjustmentTypeResponse {
    UUID    id;
    String  name;
    Instant createdAt;
}
