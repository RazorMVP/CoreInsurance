package com.nubeero.cia.finance.dto;

import com.nubeero.cia.finance.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PostReceiptRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate paymentDate,
        @NotNull PaymentMethod paymentMethod,
        UUID bankId,
        String bankName,
        String chequeNumber,
        String narration
) {}
