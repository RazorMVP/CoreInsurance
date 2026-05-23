package com.nubeero.cia.setup.company.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class PasswordPolicyRequest {

    @Min(value = 4,   message = "minLength must be ≥ 4")
    @Max(value = 256, message = "minLength must be ≤ 256")
    private int minLength;

    @Min(value = 4,   message = "maxLength must be ≥ 4")
    @Max(value = 256, message = "maxLength must be ≤ 256")
    private int maxLength;

    private boolean requireUppercase;
    private boolean requireLowercase;
    private boolean requireNumbers;
    private boolean requireSpecial;

    @Min(value = 0,    message = "expiryDays must be ≥ 0 (0 = never expires)")
    @Max(value = 3650, message = "expiryDays must be ≤ 3650 (10 years)")
    private int expiryDays;

    @Min(value = 1,   message = "maxFailedAttempts must be ≥ 1")
    @Max(value = 100, message = "maxFailedAttempts must be ≤ 100")
    private int maxFailedAttempts;

    @AssertTrue(message = "maxLength must be ≥ minLength")
    public boolean isLengthRangeValid() {
        return maxLength >= minLength;
    }
}
