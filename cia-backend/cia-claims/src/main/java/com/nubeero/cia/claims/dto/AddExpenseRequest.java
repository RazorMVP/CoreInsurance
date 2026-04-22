package com.nubeero.cia.claims.dto;

import com.nubeero.cia.claims.ClaimExpenseType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AddExpenseRequest(
        @NotNull ClaimExpenseType expenseType,
        UUID vendorId,
        @NotBlank String vendorName,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String description
) {}
