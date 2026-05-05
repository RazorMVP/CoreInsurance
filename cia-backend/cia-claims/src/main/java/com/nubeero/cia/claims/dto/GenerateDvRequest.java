package com.nubeero.cia.claims.dto;

import com.nubeero.cia.claims.DvType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Generate-DV payload — captures the type of Discharge Voucher to issue
 * (own damage / third party / ex-gratia) and an optional override of the
 * amount. When {@code amount} is null the service falls back to the
 * claim's existing {@code approvedAmount}.
 */
public record GenerateDvRequest(
        @NotNull DvType dvType,
        @Positive BigDecimal amount
) {}
