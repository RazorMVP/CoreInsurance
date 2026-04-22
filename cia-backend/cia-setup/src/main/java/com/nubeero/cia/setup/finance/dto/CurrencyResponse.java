package com.nubeero.cia.setup.finance.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class CurrencyResponse {
    private UUID id;
    private String code;
    private String name;
    private String symbol;
    private boolean isDefault;
    private Instant createdAt;
    private Instant updatedAt;
}
